package com.cryptostate.backend.transaction.dto;

import java.math.BigDecimal;

public record TransactionTotals(
        long count,
        BigDecimal totalRealizedPnl,
        BigDecimal totalFees,
        BigDecimal totalVolume
) {}
