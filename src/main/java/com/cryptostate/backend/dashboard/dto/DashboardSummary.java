package com.cryptostate.backend.dashboard.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

public record DashboardSummary(
        long transactionCount,
        BigDecimal totalRealizedPnl,
        BigDecimal totalFees,
        BigDecimal totalVolume,
        List<MonthlyPnl> monthlyPnl,
        List<AssetSummary> topAssets
) implements Serializable {
    public record MonthlyPnl(String month, BigDecimal pnl) implements Serializable {}
    public record AssetSummary(String asset, BigDecimal volume, BigDecimal pnl) implements Serializable {}
}
