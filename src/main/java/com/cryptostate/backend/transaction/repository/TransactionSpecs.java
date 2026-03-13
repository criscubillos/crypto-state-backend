package com.cryptostate.backend.transaction.repository;

import com.cryptostate.backend.transaction.model.NormalizedTransaction;
import com.cryptostate.backend.transaction.model.TransactionType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TransactionSpecs {

    public static Specification<NormalizedTransaction> filter(
            UUID userId, UUID connectionId, String exchangeId, List<TransactionType> types, Instant from, Instant to) {
        return (root, query, cb) -> {
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
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
