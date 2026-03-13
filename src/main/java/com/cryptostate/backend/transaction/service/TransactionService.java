package com.cryptostate.backend.transaction.service;

import com.cryptostate.backend.common.util.MemcachedService;
import com.cryptostate.backend.transaction.dto.TopTransactionsResponse;
import com.cryptostate.backend.transaction.dto.TransactionResponse;
import com.cryptostate.backend.transaction.dto.TransactionTotals;
import com.cryptostate.backend.transaction.model.NormalizedTransaction;
import com.cryptostate.backend.transaction.model.TransactionType;
import com.cryptostate.backend.transaction.repository.TransactionRepository;
import com.cryptostate.backend.transaction.repository.TransactionSpecs;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final MemcachedService memcachedService;
    private final EntityManager entityManager;

    public record UpsertResult(int total, int newCount, int updatedCount) {}

    @Transactional
    public UpsertResult upsertAll(List<NormalizedTransaction> transactions) {
        int newCount = 0;
        int updatedCount = 0;
        for (NormalizedTransaction tx : transactions) {
            boolean[] isNew = {false};
            transactionRepository
                    .findByUserIdAndConnectionIdAndExternalId(tx.getUserId(), tx.getConnectionId(), tx.getExternalId())
                    .ifPresentOrElse(
                            existing -> {
                                existing.setRawData(tx.getRawData());
                                existing.setRealizedPnl(tx.getRealizedPnl());
                                existing.setRealizedPnlUsd(tx.getRealizedPnlUsd());
                                existing.setFeeUsd(tx.getFeeUsd());
                                transactionRepository.save(existing);
                            },
                            () -> { transactionRepository.save(tx); isNew[0] = true; }
                    );
            if (isNew[0]) newCount++; else updatedCount++;
        }
        log.info("Upsert completado: {} total ({} nuevas, {} actualizadas)", transactions.size(), newCount, updatedCount);
        return new UpsertResult(transactions.size(), newCount, updatedCount);
    }

    public Page<TransactionResponse> findFiltered(UUID userId, UUID connectionId, String exchangeId,
            List<TransactionType> types, Instant from, Instant to, Pageable pageable) {
        return transactionRepository
                .findAll(TransactionSpecs.filter(userId, connectionId, exchangeId, types, from, to), pageable)
                .map(TransactionResponse::from);
    }

    public TransactionTotals getTotals(UUID userId, UUID connectionId, String exchangeId,
            List<TransactionType> types, Instant from, Instant to) {

        String cacheKey = "tx_totals:" + userId + ":" + connectionId + ":" + exchangeId + ":" + types + ":" + from + ":" + to;
        TransactionTotals cached = memcachedService.get(cacheKey);
        if (cached != null) return cached;

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<NormalizedTransaction> root = cq.from(NormalizedTransaction.class);

        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(root.get("userId"), userId));
        if (connectionId != null)
            predicates.add(cb.equal(root.get("connectionId"), connectionId));
        if (exchangeId != null && !exchangeId.isBlank())
            predicates.add(cb.equal(root.get("exchangeId"), exchangeId));
        if (types != null && !types.isEmpty())
            predicates.add(root.get("type").in(types));
        if (from != null)
            predicates.add(cb.greaterThanOrEqualTo(root.get("timestamp"), from));
        if (to != null)
            predicates.add(cb.lessThanOrEqualTo(root.get("timestamp"), to));

        cq.multiselect(
                cb.count(root),
                cb.coalesce(cb.sum(
                    cb.coalesce(root.<BigDecimal>get("realizedPnlUsd"), root.<BigDecimal>get("realizedPnl"))
                ), BigDecimal.ZERO),
                cb.coalesce(cb.sum(
                    cb.coalesce(root.<BigDecimal>get("feeUsd"), root.<BigDecimal>get("fee"))
                ), BigDecimal.ZERO),
                cb.coalesce(cb.sum(cb.prod(root.<BigDecimal>get("quantity"), root.<BigDecimal>get("price"))), BigDecimal.ZERO)
        ).where(predicates.toArray(new Predicate[0]));

        Object[] row = entityManager.createQuery(cq).getSingleResult();
        TransactionTotals totals = new TransactionTotals(
                ((Number) row[0]).longValue(),
                (BigDecimal) row[1],
                (BigDecimal) row[2],
                (BigDecimal) row[3]
        );

        memcachedService.set(cacheKey, 60, totals);
        return totals;
    }

    public List<NormalizedTransaction> findForTaxYear(UUID userId, int year) {
        Instant from = Instant.parse(year + "-01-01T00:00:00Z");
        Instant to   = Instant.parse(year + "-12-31T23:59:59Z");
        return transactionRepository
                .findByUserIdAndTimestampBetweenOrderByTimestampAsc(userId, from, to);
    }

    public TopTransactionsResponse getTopTransactions(
            UUID userId, UUID connectionId, String exchangeId, Integer year, Integer month, String asset) {

        Instant from = null;
        Instant to   = null;
        String period = "all";

        if (year != null && month != null) {
            LocalDate start = LocalDate.of(year, month, 1);
            LocalDate end   = start.withDayOfMonth(start.lengthOfMonth());
            from   = start.atStartOfDay(ZoneOffset.UTC).toInstant();
            to     = end.atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
            period = year + "-" + String.format("%02d", month);
        } else if (year != null) {
            from   = LocalDate.of(year, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
            to     = LocalDate.of(year, 12, 31).atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
            period = String.valueOf(year);
        }

        List<TransactionType> assetTypeFilter = null; // no filter by type here
        Specification<NormalizedTransaction> spec = asset != null && !asset.isBlank()
                ? TransactionSpecs.filter(userId, connectionId, exchangeId, null, from, to)
                    .and((root, query, cb) -> cb.equal(root.get("baseAsset"), asset))
                : TransactionSpecs.filter(userId, connectionId, exchangeId, null, from, to);
        List<NormalizedTransaction> all = transactionRepository.findAll(spec);

        List<NormalizedTransaction> withPnl = all.stream()
                .filter(tx -> tx.getRealizedPnl() != null
                        && tx.getRealizedPnl().compareTo(BigDecimal.ZERO) != 0)
                .toList();

        BigDecimal totalAbsPnl = withPnl.stream()
                .map(tx -> tx.getRealizedPnl().abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalPnl = withPnl.stream()
                .map(NormalizedTransaction::getRealizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Totales en USD (cuando esté disponible)
        BigDecimal totalPnlUsd = withPnl.stream()
                .filter(tx -> tx.getRealizedPnlUsd() != null)
                .map(NormalizedTransaction::getRealizedPnlUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalAbsPnlUsd = withPnl.stream()
                .filter(tx -> tx.getRealizedPnlUsd() != null)
                .map(tx -> tx.getRealizedPnlUsd().abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Ordenar por G/P USD cuando esté disponible, fallback a G/P nativo
        List<TopTransactionsResponse.TopTransactionItem> topGains = withPnl.stream()
                .filter(tx -> tx.getRealizedPnl().compareTo(BigDecimal.ZERO) > 0)
                .sorted(Comparator.comparing((NormalizedTransaction tx) ->
                        tx.getRealizedPnlUsd() != null ? tx.getRealizedPnlUsd() : tx.getRealizedPnl()
                ).reversed())
                .limit(10)
                .map(tx -> toTopItem(tx, totalAbsPnlUsd.compareTo(BigDecimal.ZERO) > 0 ? totalAbsPnlUsd : totalAbsPnl, totalAbsPnlUsd.compareTo(BigDecimal.ZERO) > 0))
                .toList();

        List<TopTransactionsResponse.TopTransactionItem> topLosses = withPnl.stream()
                .filter(tx -> tx.getRealizedPnl().compareTo(BigDecimal.ZERO) < 0)
                .sorted(Comparator.comparing((NormalizedTransaction tx) ->
                        tx.getRealizedPnlUsd() != null ? tx.getRealizedPnlUsd() : tx.getRealizedPnl()
                ))
                .limit(10)
                .map(tx -> toTopItem(tx, totalAbsPnlUsd.compareTo(BigDecimal.ZERO) > 0 ? totalAbsPnlUsd : totalAbsPnl, totalAbsPnlUsd.compareTo(BigDecimal.ZERO) > 0))
                .toList();

        return new TopTransactionsResponse(topGains, topLosses, totalPnl, totalAbsPnl, totalPnlUsd, totalAbsPnlUsd, period);
    }

    private TopTransactionsResponse.TopTransactionItem toTopItem(
            NormalizedTransaction tx, BigDecimal totalAbsRef, boolean useUsd) {
        BigDecimal pnlPct = BigDecimal.ZERO;
        BigDecimal pnlRef = (useUsd && tx.getRealizedPnlUsd() != null)
                ? tx.getRealizedPnlUsd() : tx.getRealizedPnl();
        if (totalAbsRef.compareTo(BigDecimal.ZERO) > 0) {
            pnlPct = pnlRef.abs()
                    .multiply(new BigDecimal("100"))
                    .divide(totalAbsRef, 4, RoundingMode.HALF_UP);
        }
        return new TopTransactionsResponse.TopTransactionItem(
                tx.getId().toString(),
                tx.getTimestamp(),
                tx.getType().name(),
                tx.getBaseAsset(),
                tx.getQuoteAsset(),
                tx.getRealizedPnl(),
                tx.getRealizedPnlUsd(),
                tx.getFee(),
                tx.getFeeAsset(),
                pnlPct,
                tx.getExchangeId(),
                tx.getConnectionId() != null ? tx.getConnectionId().toString() : null
        );
    }
}
