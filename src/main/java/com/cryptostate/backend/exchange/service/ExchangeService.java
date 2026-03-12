package com.cryptostate.backend.exchange.service;

import com.cryptostate.backend.auth.model.User;
import com.cryptostate.backend.auth.repository.UserRepository;
import com.cryptostate.backend.common.exception.ApiException;
import com.cryptostate.backend.common.util.EncryptionService;
import com.cryptostate.backend.common.util.MemcachedService;
import com.cryptostate.backend.exchange.adapter.ExchangeAdapterRegistry;
import com.cryptostate.backend.exchange.dto.CreateConnectionRequest;
import com.cryptostate.backend.exchange.dto.ConnectionResponse;
import com.cryptostate.backend.exchange.model.ExchangeConnection;
import com.cryptostate.backend.exchange.model.SyncJob;
import com.cryptostate.backend.exchange.repository.ExchangeConnectionRepository;
import com.cryptostate.backend.exchange.repository.SyncJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final CloudflareQueueService cloudflareQueueService;
    private final ExchangeAdapterRegistry adapterRegistry;
    private final MemcachedService memcachedService;

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

        // Publicar mensaje mínimo a Cloudflare Queue
        cloudflareQueueService.publishSyncMessage(userId, conn.getExchangeId());

        // Invalidar dashboard en cache
        memcachedService.delete(MemcachedService.dashboardKey(userId));

        return job;
    }

    public SyncJob getSyncJob(String userId, String jobId) {
        return syncJobRepository
                .findByIdAndUserId(UUID.fromString(jobId), UUID.fromString(userId))
                .orElseThrow(() -> ApiException.notFound("Job no encontrado"));
    }
}
