package com.cryptostate.backend.transaction.service;

import com.cryptostate.backend.common.util.MemcachedService;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final MemcachedService memcachedService;
    private final EntityManager entityManager;

    @Transactional
    public int upsertAll(List<NormalizedTransaction> transactions) {
        int saved = 0;
        for (NormalizedTransaction tx : transactions) {
            transactionRepository
                    .findByUserIdAndExchangeIdAndExternalId(tx.getUserId(), tx.getExchangeId(), tx.getExternalId())
                    .ifPresentOrElse(
                            existing -> {
                                existing.setRawData(tx.getRawData());
                                existing.setRealizedPnl(tx.getRealizedPnl());
                                transactionRepository.save(existing);
                            },
                            () -> transactionRepository.save(tx)
                    );
            saved++;
        }
        log.info("Upsert de {} transacciones completado", saved);
        return saved;
    }

    public Page<TransactionResponse> findFiltered(UUID userId, String exchangeId,
            TransactionType type, Instant from, Instant to, Pageable pageable) {
        return transactionRepository
                .findAll(TransactionSpecs.filter(userId, exchangeId, type, from, to), pageable)
                .map(TransactionResponse::from);
    }

    public TransactionTotals getTotals(UUID userId, String exchangeId,
            TransactionType type, Instant from, Instant to) {

        String cacheKey = "tx_totals:" + userId + ":" + exchangeId + ":" + type + ":" + from + ":" + to;
        TransactionTotals cached = memcachedService.get(cacheKey);
        if (cached != null) return cached;

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<NormalizedTransaction> root = cq.from(NormalizedTransaction.class);

        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(root.get("userId"), userId));
        if (exchangeId != null && !exchangeId.isBlank())
            predicates.add(cb.equal(root.get("exchangeId"), exchangeId));
        if (type != null)
            predicates.add(cb.equal(root.get("type"), type));
        if (from != null)
            predicates.add(cb.greaterThanOrEqualTo(root.get("timestamp"), from));
        if (to != null)
            predicates.add(cb.lessThanOrEqualTo(root.get("timestamp"), to));

        cq.multiselect(
                cb.count(root),
                cb.coalesce(cb.sum(root.<BigDecimal>get("realizedPnl")), BigDecimal.ZERO),
                cb.coalesce(cb.sum(root.<BigDecimal>get("fee")), BigDecimal.ZERO),
                cb.coalesce(cb.sum(cb.prod(root.<BigDecimal>get("quantity"), root.<BigDecimal>get("price"))), BigDecimal.ZERO)
        ).where(predicates.toArray(new Predicate[0]));

        Object[] row = entityManager.createQuery(cq).getSingleResult();
        TransactionTotals totals = new TransactionTotals(
                ((Number) row[0]).longValue(),
                (BigDecimal) row[1],
                (BigDecimal) row[2],
                (BigDecimal) row[3]
        );

        memcachedService.set(cacheKey, 300, totals);
        return totals;
    }

    public List<NormalizedTransaction> findForTaxYear(UUID userId, int year) {
        Instant from = Instant.parse(year + "-01-01T00:00:00Z");
        Instant to   = Instant.parse(year + "-12-31T23:59:59Z");
        return transactionRepository
                .findByUserIdAndTimestampBetweenOrderByTimestampAsc(userId, from, to);
    }
}
