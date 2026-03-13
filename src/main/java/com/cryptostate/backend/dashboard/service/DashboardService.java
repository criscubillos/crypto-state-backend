package com.cryptostate.backend.dashboard.service;

import com.cryptostate.backend.common.util.MemcachedService;
import com.cryptostate.backend.dashboard.dto.DashboardSummary;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final EntityManager entityManager;
    private final MemcachedService memcachedService;

    public DashboardSummary getSummary(UUID userId) {
        String cacheKey = MemcachedService.dashboardKey(userId.toString());
        DashboardSummary cached = memcachedService.get(cacheKey);
        if (cached != null) return cached;

        DashboardSummary summary = buildSummary(userId);
        memcachedService.set(cacheKey, 600, summary);
        return summary;
    }

    private DashboardSummary buildSummary(UUID userId) {
        String uid = userId.toString();

        // 1. Totales globales
        // El volumen usa quantity*price para spot; para futuros (sin quantity/price) usa ABS(realized_pnl) como proxy
        Object[] totals = (Object[]) entityManager.createNativeQuery("""
                SELECT COUNT(*),
                       COALESCE(SUM(COALESCE(realized_pnl_usd, realized_pnl)), 0),
                       COALESCE(SUM(COALESCE(fee_usd, fee)), 0),
                       COALESCE(SUM(
                           CASE WHEN quantity IS NOT NULL AND price IS NOT NULL AND quantity * price > 0
                                THEN quantity * price
                                ELSE ABS(COALESCE(realized_pnl, 0))
                           END
                       ), 0)
                FROM normalized_transactions
                WHERE user_id = :userId
                """)
                .setParameter("userId", UUID.fromString(uid))
                .getSingleResult();

        long   count  = ((Number) totals[0]).longValue();
        BigDecimal pnl    = (BigDecimal) totals[1];
        BigDecimal fees   = (BigDecimal) totals[2];
        BigDecimal volume = (BigDecimal) totals[3];

        // 2. PnL mensual — todos los tipos que tengan realized_pnl (spot, futuros, etc.)
        @SuppressWarnings("unchecked")
        List<Object[]> monthlyRows = entityManager.createNativeQuery("""
                SELECT to_char(timestamp, 'YYYY-MM') as month,
                       COALESCE(SUM(COALESCE(realized_pnl_usd, realized_pnl)), 0) as pnl
                FROM normalized_transactions
                WHERE user_id = :userId
                  AND realized_pnl IS NOT NULL
                  AND realized_pnl <> 0
                GROUP BY month
                ORDER BY month
                """)
                .setParameter("userId", UUID.fromString(uid))
                .getResultList();

        List<DashboardSummary.MonthlyPnl> monthlyPnl = new ArrayList<>();
        for (Object[] row : monthlyRows) {
            monthlyPnl.add(new DashboardSummary.MonthlyPnl(
                    (String) row[0],
                    (BigDecimal) row[1]
            ));
        }

        // 3. Top 5 activos por volumen — usa ABS(realized_pnl) como proxy cuando no hay quantity*price
        @SuppressWarnings("unchecked")
        List<Object[]> assetRows = entityManager.createNativeQuery("""
                SELECT base_asset,
                       COALESCE(SUM(
                           CASE WHEN quantity IS NOT NULL AND price IS NOT NULL AND quantity * price > 0
                                THEN quantity * price
                                ELSE ABS(COALESCE(COALESCE(realized_pnl_usd, realized_pnl), 0))
                           END
                       ), 0) as volume,
                       COALESCE(SUM(COALESCE(realized_pnl_usd, realized_pnl)), 0) as pnl
                FROM normalized_transactions
                WHERE user_id = :userId
                  AND base_asset IS NOT NULL
                GROUP BY base_asset
                ORDER BY volume DESC
                LIMIT 5
                """)
                .setParameter("userId", UUID.fromString(uid))
                .getResultList();

        List<DashboardSummary.AssetSummary> topAssets = new ArrayList<>();
        for (Object[] row : assetRows) {
            topAssets.add(new DashboardSummary.AssetSummary(
                    (String) row[0],
                    (BigDecimal) row[1],
                    (BigDecimal) row[2]
            ));
        }

        log.info("Dashboard summary generado para userId={}: {} tx, pnl={}", userId, count, pnl);
        return new DashboardSummary(count, pnl, fees, volume, monthlyPnl, topAssets);
    }
}
