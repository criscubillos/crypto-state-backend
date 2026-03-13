package com.cryptostate.backend.exchange.worker;

import com.cryptostate.backend.common.util.EncryptionService;
import com.cryptostate.backend.common.util.MemcachedService;
import com.cryptostate.backend.exchange.adapter.ExchangeAdapter;
import com.cryptostate.backend.exchange.adapter.ExchangeAdapterRegistry;
import com.cryptostate.backend.exchange.event.SyncRequestEvent;
import com.cryptostate.backend.exchange.model.ExchangeConnection;
import com.cryptostate.backend.exchange.model.SyncJob;
import com.cryptostate.backend.exchange.repository.ExchangeConnectionRepository;
import com.cryptostate.backend.exchange.repository.SyncJobRepository;
import com.cryptostate.backend.transaction.model.NormalizedTransaction;
import com.cryptostate.backend.transaction.service.PnlCalculatorService;
import com.cryptostate.backend.transaction.service.TransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class SyncWorker {

    private final ExchangeConnectionRepository connectionRepository;
    private final SyncJobRepository syncJobRepository;
    private final ExchangeAdapterRegistry adapterRegistry;
    private final EncryptionService encryptionService;
    private final TransactionService transactionService;
    private final PnlCalculatorService pnlCalculatorService;
    private final MemcachedService memcachedService;

    /**
     * Se ejecuta en un hilo del syncExecutor DESPUÉS de que la transacción
     * que publicó el evento haya sido commiteada (AFTER_COMMIT).
     * Así el SyncJob ya existe en BD cuando el worker lo busca.
     */
    @Async("syncExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void onSyncRequest(SyncRequestEvent event) {
        log.info("SyncWorker procesando: jobId={} exchange={} userId={}",
                event.jobId(), event.exchangeId(), event.userId());

        SyncJob job = syncJobRepository.findById(UUID.fromString(event.jobId()))
                .orElseGet(() -> {
                    log.warn("SyncJob no encontrado: {}", event.jobId());
                    return null;
                });
        if (job == null) return;

        job.setStatus(SyncJob.SyncStatus.PROCESSING);
        syncJobRepository.save(job);

        ExchangeConnection conn = connectionRepository
                .findByIdAndUserId(UUID.fromString(event.connectionId()), UUID.fromString(event.userId()))
                .orElse(null);

        if (conn == null) {
            failJob(job, "Conexión no encontrada");
            return;
        }

        try {
            ExchangeAdapter adapter = adapterRegistry.get(event.exchangeId());
            String apiKey    = encryptionService.decrypt(conn.getApiKeyEncrypted());
            String apiSecret = encryptionService.decrypt(conn.getApiSecretEncrypted());

            Instant from = conn.getLastSyncAt() != null
                    ? conn.getLastSyncAt()
                    : Instant.parse("2025-01-01T00:00:00Z");
            Instant to = Instant.now();
            Instant syncStart = Instant.now();

            UUID connectionUuid = conn.getId();
            List<NormalizedTransaction> txs = adapter.fetchAndNormalize(apiKey, apiSecret, from, to, event.userId());
            txs.forEach(tx -> tx.setConnectionId(connectionUuid));

            // Conteo por tipo para el resumen
            java.util.Map<String, Long> byType = txs.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            tx -> tx.getType().name(), java.util.stream.Collectors.counting()));

            TransactionService.UpsertResult result = transactionService.upsertAll(txs);
            pnlCalculatorService.recalculateForUser(UUID.fromString(event.userId()));

            conn.setLastSyncAt(to);
            connectionRepository.save(conn);

            job.setStatus(SyncJob.SyncStatus.DONE);
            job.setCompletedAt(to);
            syncJobRepository.save(job);

            memcachedService.invalidateUserDataCache(event.userId());

            long durationSec = ChronoUnit.SECONDS.between(syncStart, Instant.now());
            String connLabel = conn.getLabel() != null ? conn.getLabel() : conn.getExchangeId();
            log.info("╔══════════════════════════════════════════════════════");
            log.info("║ SYNC COMPLETADO");
            log.info("║  jobId    : {}", event.jobId());
            log.info("║  Exchange : {} ({})", event.exchangeId(), connLabel);
            log.info("║  Duración : {} segundos", durationSec);
            log.info("║  Total    : {} transacciones ({} nuevas, {} actualizadas)",
                    result.total(), result.newCount(), result.updatedCount());
            if (!byType.isEmpty()) {
                log.info("║  Por tipo : {}", byType);
            }
            log.info("╚══════════════════════════════════════════════════════");

        } catch (Exception e) {
            log.error("Sync fallido: jobId={} error={}", event.jobId(), e.getMessage(), e);
            failJob(job, e.getMessage());
        }
    }

    private void failJob(SyncJob job, String error) {
        job.setStatus(SyncJob.SyncStatus.FAILED);
        job.setError(error != null && error.length() > 1000 ? error.substring(0, 1000) : error);
        job.setCompletedAt(Instant.now());
        syncJobRepository.save(job);
    }
}
