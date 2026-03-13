package com.cryptostate.backend.exchange.service;

import com.cryptostate.backend.auth.model.User;
import com.cryptostate.backend.auth.repository.UserRepository;
import com.cryptostate.backend.common.exception.ApiException;
import com.cryptostate.backend.common.util.EncryptionService;
import com.cryptostate.backend.common.util.MemcachedService;
import com.cryptostate.backend.exchange.adapter.ExchangeAdapter;
import com.cryptostate.backend.exchange.adapter.ExchangeAdapterRegistry;
import com.cryptostate.backend.exchange.dto.ConnectionResponse;
import com.cryptostate.backend.exchange.dto.CreateConnectionRequest;
import com.cryptostate.backend.exchange.dto.DirectSyncResult;
import com.cryptostate.backend.exchange.dto.ImportResult;
import com.cryptostate.backend.exchange.event.ImportRequestEvent;
import com.cryptostate.backend.exchange.event.SyncRequestEvent;
import com.cryptostate.backend.exchange.importer.ExchangeImporterRegistry;
import com.cryptostate.backend.exchange.model.ExchangeConnection;
import com.cryptostate.backend.exchange.model.SyncJob;
import com.cryptostate.backend.exchange.repository.ExchangeConnectionRepository;
import com.cryptostate.backend.exchange.repository.SyncJobRepository;
import com.cryptostate.backend.transaction.model.NormalizedTransaction;
import com.cryptostate.backend.transaction.service.PnlCalculatorService;
import com.cryptostate.backend.transaction.service.TransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeService {

    private final ExchangeConnectionRepository connectionRepository;
    private final SyncJobRepository syncJobRepository;
    private final UserRepository userRepository;
    private final EncryptionService encryptionService;
    private final ExchangeAdapterRegistry adapterRegistry;
    private final ExchangeImporterRegistry importerRegistry;
    private final MemcachedService memcachedService;
    private final TransactionService transactionService;
    private final PnlCalculatorService pnlCalculatorService;
    private final ApplicationEventPublisher eventPublisher;

    // ── Connections ──────────────────────────────────────────────────────────

    @Transactional
    public ConnectionResponse createConnection(String userId, CreateConnectionRequest req) {
        if (!adapterRegistry.supports(req.exchangeId())) {
            throw ApiException.badRequest("Exchange no soportado: " + req.exchangeId());
        }

        User user = userRepository.findById(UUID.fromString(userId))
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));

        ExchangeConnection conn = ExchangeConnection.builder()
                .user(user)
                .exchangeId(req.exchangeId().toLowerCase())
                .apiKeyEncrypted(encryptionService.encrypt(req.apiKey()))
                .apiSecretEncrypted(encryptionService.encrypt(req.apiSecret()))
                .label(req.label())
                .build();

        connectionRepository.save(conn);
        log.info("Conexión creada: userId={} exchange={}", userId, req.exchangeId());
        return ConnectionResponse.from(conn);
    }

    public List<ConnectionResponse> listConnections(String userId) {
        return connectionRepository.findByUserIdAndActiveTrue(UUID.fromString(userId))
                .stream().map(ConnectionResponse::from).toList();
    }

    @Transactional
    public void deleteConnection(String userId, String connectionId) {
        ExchangeConnection conn = connectionRepository
                .findByIdAndUserId(UUID.fromString(connectionId), UUID.fromString(userId))
                .orElseThrow(() -> ApiException.notFound("Conexión no encontrada"));
        conn.setActive(false);
        log.info("Conexión desactivada: id={}", connectionId);
    }

    @Transactional
    public ConnectionResponse updateConnection(String userId, String connectionId,
            String newApiKey, String newApiSecret, String label, Boolean resetSync) {
        ExchangeConnection conn = connectionRepository
                .findByIdAndUserId(UUID.fromString(connectionId), UUID.fromString(userId))
                .orElseThrow(() -> ApiException.notFound("Conexión no encontrada"));

        if (newApiKey != null && !newApiKey.isBlank()) {
            conn.setApiKeyEncrypted(encryptionService.encrypt(newApiKey));
        }
        if (newApiSecret != null && !newApiSecret.isBlank()) {
            conn.setApiSecretEncrypted(encryptionService.encrypt(newApiSecret));
        }
        if (label != null) {
            conn.setLabel(label.isBlank() ? null : label);
        }
        if (Boolean.TRUE.equals(resetSync)) {
            conn.setLastSyncAt(null);
            log.info("lastSyncAt reseteado para conexión id={}", connectionId);
        }
        log.info("Conexión actualizada: id={}", connectionId);
        return ConnectionResponse.from(conn);
    }

    // ── Sync ─────────────────────────────────────────────────────────────────

    @Transactional
    public SyncJob triggerSync(String userId, String connectionId) {
        ExchangeConnection conn = connectionRepository
                .findByIdAndUserId(UUID.fromString(connectionId), UUID.fromString(userId))
                .orElseThrow(() -> ApiException.notFound("Conexión no encontrada"));

        if (!conn.isActive()) {
            throw ApiException.badRequest("La conexión está desactivada");
        }

        User user = userRepository.findById(UUID.fromString(userId))
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));

        SyncJob job = syncJobRepository.save(SyncJob.builder()
                .user(user)
                .exchangeId(conn.getExchangeId())
                .build());

        // Publicar evento local — procesado por SyncWorker tras el commit
        eventPublisher.publishEvent(new SyncRequestEvent(
                userId, conn.getId().toString(), job.getId().toString(), conn.getExchangeId()));

        return job;
    }

    public SyncJob getSyncJob(String userId, String jobId) {
        return syncJobRepository
                .findByIdAndUserId(UUID.fromString(jobId), UUID.fromString(userId))
                .orElseThrow(() -> ApiException.notFound("Job no encontrado"));
    }

    // ── Sync directo (sin Cloudflare Queue) ───────────────────────────────────

    @Transactional
    public DirectSyncResult triggerDirectSync(String userId, String connectionId) {
        ExchangeConnection conn = connectionRepository
                .findByIdAndUserId(UUID.fromString(connectionId), UUID.fromString(userId))
                .orElseThrow(() -> ApiException.notFound("Conexión no encontrada"));

        if (!conn.isActive()) {
            throw ApiException.badRequest("La conexión está desactivada");
        }

        User user = userRepository.findById(UUID.fromString(userId))
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));

        SyncJob job = syncJobRepository.save(SyncJob.builder()
                .user(user)
                .exchangeId(conn.getExchangeId())
                .status(SyncJob.SyncStatus.PROCESSING)
                .build());

        try {
            ExchangeAdapter adapter = adapterRegistry.get(conn.getExchangeId());

            String apiKey    = encryptionService.decrypt(conn.getApiKeyEncrypted());
            String apiSecret = encryptionService.decrypt(conn.getApiSecretEncrypted());

            Instant from = conn.getLastSyncAt() != null
                    ? conn.getLastSyncAt()
                    : Instant.parse("2025-01-01T00:00:00Z");
            Instant to   = Instant.now();

            List<NormalizedTransaction> txs = adapter.fetchAndNormalize(apiKey, apiSecret, from, to, userId);
            txs.forEach(tx -> tx.setConnectionId(conn.getId()));
            TransactionService.UpsertResult result = transactionService.upsertAll(txs);
            pnlCalculatorService.recalculateForUser(UUID.fromString(userId));

            conn.setLastSyncAt(to);

            job.setStatus(SyncJob.SyncStatus.DONE);
            job.setCompletedAt(to);
            syncJobRepository.save(job);

            memcachedService.invalidateUserDataCache(userId);

            log.info("Sync directo completado: userId={} exchange={} txs={} ({} nuevas, {} actualizadas)",
                    userId, conn.getExchangeId(), result.total(), result.newCount(), result.updatedCount());
            return new DirectSyncResult(job.getId().toString(), "DONE", conn.getExchangeId(), result.total(), null);

        } catch (Exception e) {
            job.setStatus(SyncJob.SyncStatus.FAILED);
            job.setError(e.getMessage());
            job.setCompletedAt(Instant.now());
            syncJobRepository.save(job);
            log.error("Sync directo fallido: userId={} exchange={} error={}", userId, conn.getExchangeId(), e.getMessage());
            return new DirectSyncResult(job.getId().toString(), "FAILED", conn.getExchangeId(), 0, e.getMessage());
        }
    }

    // ── Import desde Excel ────────────────────────────────────────────────────

    @Transactional
    public SyncJob queueImport(String userId, String connectionId, byte[] fileBytes) {
        ExchangeConnection conn = connectionRepository
                .findByIdAndUserId(UUID.fromString(connectionId), UUID.fromString(userId))
                .orElseThrow(() -> ApiException.notFound("Conexión no encontrada"));

        if (!conn.isActive()) {
            throw ApiException.badRequest("La conexión está desactivada");
        }

        User user = userRepository.findById(UUID.fromString(userId))
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));

        SyncJob job = syncJobRepository.save(SyncJob.builder()
                .user(user)
                .exchangeId(conn.getExchangeId())
                .build());

        eventPublisher.publishEvent(new ImportRequestEvent(
                userId, conn.getId().toString(), job.getId().toString(),
                conn.getExchangeId(), fileBytes));

        log.info("Import Excel encolado: userId={} exchange={} jobId={}",
                userId, conn.getExchangeId(), job.getId());
        return job;
    }

    @Transactional
    public ImportResult directImport(String userId, String connectionId, byte[] fileBytes) {
        ExchangeConnection conn = connectionRepository
                .findByIdAndUserId(UUID.fromString(connectionId), UUID.fromString(userId))
                .orElseThrow(() -> ApiException.notFound("Conexión no encontrada"));

        if (!conn.isActive()) {
            throw ApiException.badRequest("La conexión está desactivada");
        }

        User user = userRepository.findById(UUID.fromString(userId))
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));

        SyncJob job = syncJobRepository.save(SyncJob.builder()
                .user(user)
                .exchangeId(conn.getExchangeId())
                .status(SyncJob.SyncStatus.PROCESSING)
                .build());

        try {
            var importer = importerRegistry.get(conn.getExchangeId());
            Instant to = Instant.now();

            List<NormalizedTransaction> txs = importer.parse(
                    new ByteArrayInputStream(fileBytes),
                    UUID.fromString(userId),
                    conn.getId());
            TransactionService.UpsertResult result = transactionService.upsertAll(txs);
            pnlCalculatorService.recalculateForUser(UUID.fromString(userId));

            conn.setLastSyncAt(to);

            job.setStatus(SyncJob.SyncStatus.DONE);
            job.setCompletedAt(to);
            syncJobRepository.save(job);

            memcachedService.invalidateUserDataCache(userId);

            log.info("Import directo completado: userId={} exchange={} txs={} ({} nuevas, {} actualizadas)",
                    userId, conn.getExchangeId(), result.total(), result.newCount(), result.updatedCount());
            return new ImportResult(job.getId().toString(), "DONE", conn.getExchangeId(), result.total(), null);

        } catch (Exception e) {
            job.setStatus(SyncJob.SyncStatus.FAILED);
            job.setError(e.getMessage());
            job.setCompletedAt(Instant.now());
            syncJobRepository.save(job);
            log.error("Import directo fallido: userId={} exchange={} error={}", userId, conn.getExchangeId(), e.getMessage());
            return new ImportResult(job.getId().toString(), "FAILED", conn.getExchangeId(), 0, e.getMessage());
        }
    }
}
