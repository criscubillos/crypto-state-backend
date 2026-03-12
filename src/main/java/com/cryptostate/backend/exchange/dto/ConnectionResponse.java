package com.cryptostate.backend.exchange.dto;

import com.cryptostate.backend.exchange.model.ExchangeConnection;

import java.time.Instant;

public record ConnectionResponse(
    String id,
    String exchangeId,
    String label,
    boolean active,
    Instant lastSyncAt,
    Instant createdAt
) {
    /** Las API keys NUNCA se incluyen en la respuesta */
    public static ConnectionResponse from(ExchangeConnection conn) {
        return new ConnectionResponse(
                conn.getId().toString(),
                conn.getExchangeId(),
                conn.getLabel(),
                conn.isActive(),
                conn.getLastSyncAt(),
                conn.getCreatedAt()
        );
    }
}
