package com.cryptostate.backend.taxes.calculator;

import com.cryptostate.backend.transaction.model.NormalizedTransaction;
import com.cryptostate.backend.transaction.model.TransactionType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Calculadora de impuestos para Chile — SII Formulario 22.
 *
 * Método: FIFO (First In, First Out) por activo.
 * Tributa como "mayor valor en enajenación de bienes" (criptoactivos).
 *
 * TODO:
 * - Integrar conversión USD → CLP usando tasa de cambio histórica (API CMF/SBIF)
 * - Mapear a recuadros exactos del F22 vigente para el año tributario
 */
@Slf4j
@Component
public class ChileTaxCalculator implements TaxCalculator {

    @Override
    public String getCountryCode() {
        return "CL";
    }

    @Override
    public TaxResult calculate(List<NormalizedTransaction> transactions, int year) {
        log.info("Calculando impuestos Chile para año={}, transacciones={}", year, transactions.size());

        // Separar compras y ventas por activo
        Map<String, LinkedList<NormalizedTransaction>> buysByAsset = new HashMap<>();
        List<TaxResult.AssetSummary> assetSummaries = new ArrayList<>();

        BigDecimal totalGains = BigDecimal.ZERO;
        BigDecimal totalLosses = BigDecimal.ZERO;

        // 1. Agrupar compras por activo (FIFO queue)
        for (NormalizedTransaction tx : transactions) {
            if (tx.getType() == TransactionType.SPOT_BUY && tx.getBaseAsset() != null) {
                buysByAsset.computeIfAbsent(tx.getBaseAsset(), k -> new LinkedList<>()).add(tx);
            }
        }

        // 2. Procesar ventas con FIFO
        Map<String, BigDecimal> gainsByAsset = new HashMap<>();
        for (NormalizedTransaction tx : transactions) {
            if (tx.getType() != TransactionType.SPOT_SELL || tx.getBaseAsset() == null) continue;

            String asset = tx.getBaseAsset();
            BigDecimal quantityToSell = tx.getQuantity() != null ? tx.getQuantity() : BigDecimal.ZERO;
            BigDecimal proceeds = tx.getPrice() != null && tx.getQuantity() != null
                    ? tx.getPrice().multiply(tx.getQuantity()) : BigDecimal.ZERO;

            BigDecimal costBasis = computeFifoCostBasis(
                    buysByAsset.getOrDefault(asset, new LinkedList<>()), quantityToSell);

            BigDecimal gain = proceeds.subtract(costBasis).setScale(8, RoundingMode.HALF_UP);
            gainsByAsset.merge(asset, gain, BigDecimal::add);
        }

        // 3. Agregar resultados por activo
        for (Map.Entry<String, BigDecimal> entry : gainsByAsset.entrySet()) {
            BigDecimal gain = entry.getValue();
            if (gain.compareTo(BigDecimal.ZERO) > 0) totalGains = totalGains.add(gain);
            else totalLosses = totalLosses.add(gain.abs());

            // TODO: obtener cost basis real desde buysByAsset
            assetSummaries.add(new TaxResult.AssetSummary(
                    entry.getKey(), BigDecimal.ZERO, BigDecimal.ZERO, gain, 0));
        }

        BigDecimal netGain = totalGains.subtract(totalLosses);

        // TODO: convertir netGain a CLP con tasa de cambio promedio del año
        BigDecimal netGainClp = netGain; // placeholder

        // TODO: mapear a recuadros exactos del F22 del SII para el año tributario dado
        Map<String, BigDecimal> f22Fields = Map.of(
                "mayor_valor_enajenacion", netGain.max(BigDecimal.ZERO),
                "perdidas_deducibles", totalLosses
        );

        return new TaxResult("CL", year, totalGains, totalLosses, netGain,
                netGainClp, "CLP", assetSummaries, f22Fields);
    }

    private BigDecimal computeFifoCostBasis(LinkedList<NormalizedTransaction> buys, BigDecimal quantityToSell) {
        BigDecimal remaining = quantityToSell;
        BigDecimal totalCost = BigDecimal.ZERO;

        Iterator<NormalizedTransaction> it = buys.iterator();
        while (it.hasNext() && remaining.compareTo(BigDecimal.ZERO) > 0) {
            NormalizedTransaction buy = it.next();
            if (buy.getQuantity() == null || buy.getPrice() == null) continue;

            BigDecimal available = buy.getQuantity();
            BigDecimal used = available.min(remaining);
            totalCost = totalCost.add(used.multiply(buy.getPrice()));
            remaining = remaining.subtract(used);

            if (used.compareTo(available) == 0) {
                it.remove();
            } else {
                buy.setQuantity(available.subtract(used));
            }
        }
        return totalCost;
    }
}
