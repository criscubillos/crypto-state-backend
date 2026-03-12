package com.cryptostate.backend.exchange.repository;

import com.cryptostate.backend.exchange.model.ExchangeConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExchangeConnectionRepository extends JpaRepository<ExchangeConnection, UUID> {
    List<ExchangeConnection> findByUserIdAndActiveTrue(UUID userId);
    Optional<ExchangeConnection> findByIdAndUserId(UUID id, UUID userId);
    boolean existsByUserIdAndExchangeId(UUID userId, String exchangeId);
}
