package com.cryptostate.backend.transaction.repository;

import com.cryptostate.backend.transaction.model.NormalizedTransaction;
import com.cryptostate.backend.transaction.model.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<NormalizedTransaction, UUID> {

    /** Para deduplicación al importar */
    Optional<NormalizedTransaction> findByUserIdAndExchangeIdAndExternalId(
        UUID userId, String exchangeId, String externalId);

    /** Listado filtrable para la vista de transacciones */
    @Query("""
        SELECT t FROM NormalizedTransaction t
        WHERE t.userId = :userId
          AND (:exchangeId IS NULL OR t.exchangeId = :exchangeId)
          AND (:type IS NULL OR t.type = :type)
          AND (:from IS NULL OR t.timestamp >= :from)
          AND (:to IS NULL OR t.timestamp <= :to)
        ORDER BY t.timestamp DESC
        """)
    Page<NormalizedTransaction> findFiltered(
        UUID userId, String exchangeId, TransactionType type,
        Instant from, Instant to, Pageable pageable);

    /** Para cálculo de impuestos: transacciones de un año */
    List<NormalizedTransaction> findByUserIdAndTimestampBetweenOrderByTimestampAsc(
        UUID userId, Instant from, Instant to);

    /** Exchanges que el usuario tiene datos */
    @Query("SELECT DISTINCT t.exchangeId FROM NormalizedTransaction t WHERE t.userId = :userId")
    List<String> findDistinctExchangesByUserId(UUID userId);
}
