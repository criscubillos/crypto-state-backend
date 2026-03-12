package com.cryptostate.backend.transaction.service;

import com.cryptostate.backend.transaction.model.NormalizedTransaction;
import com.cryptostate.backend.transaction.model.TransactionType;
import com.cryptostate.backend.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;

    /**
     * Persiste una lista de transacciones normalizadas, desduplicando por
     * (userId, exchangeId, externalId). Si ya existe, actualiza rawData.
     */
    @Transactional
    public int upsertAll(List<NormalizedTransaction> transactions) {
        int saved = 0;
        for (NormalizedTransaction tx : transactions) {
            transactionRepository
                    .findByUserIdAndExchangeIdAndExternalId(tx.getUserId(), tx.getExchangeId(), tx.getExternalId())
                    .ifPresentOrElse(
                            existing -> {
                                existing.setRawData(tx.getRawData());
                                // actualizar campos que pudieran cambiar
                                existing.setRealizedPnl(tx.getRealizedPnl());
                                transactionRepository.save(existing);
                            },
                            () -> {
                                transactionRepository.save(tx);
                            }
                    );
            saved++;
        }
        log.info("Upsert de {} transacciones completado", saved);
        return saved;
    }

    public Page<NormalizedTransaction> findFiltered(UUID userId, String exchangeId,
            TransactionType type, Instant from, Instant to, Pageable pageable) {
        return transactionRepository.findFiltered(userId, exchangeId, type, from, to, pageable);
    }

    public List<NormalizedTransaction> findForTaxYear(UUID userId, int year) {
        Instant from = Instant.parse(year + "-01-01T00:00:00Z");
        Instant to   = Instant.parse(year + "-12-31T23:59:59Z");
        return transactionRepository
                .findByUserIdAndTimestampBetweenOrderByTimestampAsc(userId, from, to);
    }
}
