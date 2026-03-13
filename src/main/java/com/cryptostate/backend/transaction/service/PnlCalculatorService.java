package com.cryptostate.backend.transaction.service;

import com.cryptostate.backend.transaction.model.NormalizedTransaction;
import com.cryptostate.backend.transaction.model.TransactionType;
import com.cryptostate.backend.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Calcula el PnL realizado por operación usando método FIFO por activo.
 * Actualiza el campo realizedPnl de cada SPOT_SELL en la BD.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PnlCalculatorService {

    private final TransactionRepository transactionRepository;

    @Transactional
    public void recalculateForUser(UUID userId) {
        List<NormalizedTransaction> all = transactionRepository
                .findByUserIdOrderByTimestampAsc(userId);

        // FIFO lots por activo: deque de [quantityRestante, precioCosto]
        Map<String, Deque<BigDecimal[]>> lots = new HashMap<>();
        int updated = 0;

        for (NormalizedTransaction tx : all) {
            if (tx.getBaseAsset() == null || tx.getQuantity() == null || tx.getPrice() == null) continue;

            String asset = tx.getBaseAsset();

            if (tx.getType() == TransactionType.SPOT_BUY) {
                lots.computeIfAbsent(asset, k -> new ArrayDeque<>())
                        .addLast(new BigDecimal[]{ tx.getQuantity(), tx.getPrice() });

            } else if (tx.getType() == TransactionType.SPOT_SELL) {
                BigDecimal remaining = tx.getQuantity();
                BigDecimal costBasis = BigDecimal.ZERO;
                Deque<BigDecimal[]> queue = lots.getOrDefault(asset, new ArrayDeque<>());

                while (remaining.compareTo(BigDecimal.ZERO) > 0 && !queue.isEmpty()) {
                    BigDecimal[] lot = queue.peekFirst();
                    BigDecimal available = lot[0];
                    BigDecimal used = available.min(remaining);
                    costBasis = costBasis.add(used.multiply(lot[1]));
                    remaining = remaining.subtract(used);

                    if (used.compareTo(available) == 0) {
                        queue.pollFirst();
                    } else {
                        lot[0] = available.subtract(used);
                    }
                }

                BigDecimal proceeds = tx.getPrice().multiply(tx.getQuantity());
                BigDecimal pnl = proceeds.subtract(costBasis).setScale(8, RoundingMode.HALF_UP);
                tx.setRealizedPnl(pnl);
                transactionRepository.save(tx);
                updated++;
            }
        }

        log.info("PnL FIFO recalculado para userId={}: {} ventas actualizadas de {} transacciones",
                userId, updated, all.size());
    }
}
