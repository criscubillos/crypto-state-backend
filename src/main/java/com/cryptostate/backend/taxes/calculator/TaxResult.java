package com.cryptostate.backend.taxes.calculator;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record TaxResult(
    String countryCode,
    int year,
    BigDecimal totalGainsUsd,
    BigDecimal totalLossesUsd,
    BigDecimal netGainUsd,
    /** Para Chile: valores en CLP */
    BigDecimal netGainLocalCurrency,
    String localCurrency,
    /** Detalle por activo */
    List<AssetSummary> assetSummaries,
    /** Recuadros del formulario (específico por país) */
    Map<String, BigDecimal> taxFormFields
) {
    public record AssetSummary(
        String asset,
        BigDecimal totalCostBasis,
        BigDecimal totalProceeds,
        BigDecimal realizedGainUsd,
        int tradeCount
    ) {}
}
