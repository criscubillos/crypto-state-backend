package com.cryptostate.backend.transaction.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record TopTransactionsResponse(
    List<TopTransactionItem> topGains,
    List<TopTransactionItem> topLosses,
    BigDecimal totalPnl,
    BigDecimal totalAbsPnl,
    BigDecimal totalPnlUsd,
    BigDecimal totalAbsPnlUsd,
    String period
) {
    public record TopTransactionItem(
        String id,
        Instant timestamp,
        String type,
        String baseAsset,
        String quoteAsset,
        BigDecimal realizedPnl,
        BigDecimal realizedPnlUsd,
        BigDecimal fee,
        String feeAsset,
        BigDecimal pnlPct,
        String exchangeId,
        String connectionId
    ) {}
}
