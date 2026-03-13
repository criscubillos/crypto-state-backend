package com.cryptostate.backend.transaction.dto;

import com.cryptostate.backend.transaction.model.NormalizedTransaction;
import com.cryptostate.backend.transaction.model.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionResponse(
        String id,
        String connectionId,
        String exchangeId,
        String externalId,
        TransactionType type,
        String baseAsset,
        String quoteAsset,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal fee,
        String feeAsset,
        BigDecimal realizedPnl,
        BigDecimal realizedPnlUsd,
        BigDecimal feeUsd,
        Instant timestamp
) {
    public static TransactionResponse from(NormalizedTransaction tx) {
        return new TransactionResponse(
                tx.getId().toString(),
                tx.getConnectionId() != null ? tx.getConnectionId().toString() : null,
                tx.getExchangeId(),
                tx.getExternalId(),
                tx.getType(),
                tx.getBaseAsset(),
                tx.getQuoteAsset(),
                tx.getQuantity(),
                tx.getPrice(),
                tx.getFee(),
                tx.getFeeAsset(),
                tx.getRealizedPnl(),
                tx.getRealizedPnlUsd(),
                tx.getFeeUsd(),
                tx.getTimestamp()
        );
    }
}
