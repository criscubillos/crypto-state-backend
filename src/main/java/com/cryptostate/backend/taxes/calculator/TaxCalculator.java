package com.cryptostate.backend.taxes.calculator;

import com.cryptostate.backend.transaction.model.NormalizedTransaction;

import java.util.List;

/**
 * Contrato para calculadoras de impuestos por país.
 * Para agregar un nuevo país: implementar esta interfaz y registrar en TaxCalculatorRegistry.
 */
public interface TaxCalculator {
    /** Código de país ISO 3166-1 alpha-2 que maneja esta calculadora */
    String getCountryCode();

    TaxResult calculate(List<NormalizedTransaction> transactions, int year);
}
