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
import com.cryptostate.backend.exchange.event.SyncRequestEvent;
import com.cryptostate.backend.exchange.model.ExchangeConnection;
import com.cryptostate.backend.exchange.model.SyncJob;
import com.cryptostate.backend.exchange.repository.ExchangeConnectionRepository;
import com.cryptostate.backend.exchange.repository.SyncJobRepository;
import com.cryptostate.backend.transaction.model.NormalizedTransaction;
import com.cryptostate.backend.transaction.service.TransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final MemcachedService memcachedService;
    private final TransactionService transactionService;
    private final ApplicationEventPublisher eventPublisher;

    // ── Connections ──────────────────────────────────────────────────────────

    @Transactional
    public ConnectionResponse createConnection(String userId, CreateConnectionRequest req) {
        if (!adapterRegistry.supports(req.exchangeId())) {
            throw ApiException.badRequest("Exchange no soportado: " + req.exchangeId());
        }

        User user = userRepository.findById(UUID.fromString(userId))
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));

        // Una conexión por exchange por usuario
        if (connectionRepository.existsByUserIdAndExchangeId(UUID.fromString(userId), req.exchangeId())) {
            throw ApiException.conflict("Ya existe una conexión con " + req.exchangeId() + ". Elimínala primero.");
        }

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
            String newApiKey, String newApiSecret, String label) {
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
                    : Instant.now().minus(90, java.time.temporal.ChronoUnit.DAYS);
            Instant to   = Instant.now();

            List<NormalizedTransaction> txs = adapter.fetchAndNormalize(apiKey, apiSecret, from, to, userId);
            int saved = transactionService.upsertAll(txs);

            conn.setLastSyncAt(to);

            job.setStatus(SyncJob.SyncStatus.DONE);
            job.setCompletedAt(to);
            syncJobRepository.save(job);

            memcachedService.delete(MemcachedService.dashboardKey(userId));

            log.info("Sync directo completado: userId={} exchange={} txs={}", userId, conn.getExchangeId(), saved);
            return new DirectSyncResult(job.getId().toString(), "DONE", conn.getExchangeId(), saved, null);

        } catch (Exception e) {
            job.setStatus(SyncJob.SyncStatus.FAILED);
            job.setError(e.getMessage());
            job.setCompletedAt(Instant.now());
            syncJobRepository.save(job);
            log.error("Sync directo fallido: userId={} exchange={} error={}", userId, conn.getExchangeId(), e.getMessage());
            return new DirectSyncResult(job.getId().toString(), "FAILED", conn.getExchangeId(), 0, e.getMessage());
        }
    }
}
