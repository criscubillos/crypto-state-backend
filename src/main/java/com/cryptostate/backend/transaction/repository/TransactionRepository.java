package com.cryptostate.backend.transaction.repository;

import com.cryptostate.backend.transaction.model.NormalizedTransaction;
import com.cryptostate.backend.transaction.model.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository
        extends JpaRepository<NormalizedTransaction, UUID>,
                JpaSpecificationExecutor<NormalizedTransaction> {

    /** Para deduplicación al importar */
    Optional<NormalizedTransaction> findByUserIdAndConnectionIdAndExternalId(
        UUID userId, UUID connectionId, String externalId);

    /** Para cálculo de impuestos: transacciones de un año */
    List<NormalizedTransaction> findByUserIdAndTimestampBetweenOrderByTimestampAsc(
        UUID userId, Instant from, Instant to);

    /** Para cálculo FIFO: todas las transacciones del usuario ordenadas por tiempo */
    List<NormalizedTransaction> findByUserIdOrderByTimestampAsc(UUID userId);

    /** Exchanges que el usuario tiene datos */
    @Query("SELECT DISTINCT t.exchangeId FROM NormalizedTransaction t WHERE t.userId = :userId")
    List<String> findDistinctExchangesByUserId(UUID userId);
}
