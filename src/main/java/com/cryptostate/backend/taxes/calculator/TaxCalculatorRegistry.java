package com.cryptostate.backend.taxes.calculator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class TaxCalculatorRegistry {

    private final Map<String, TaxCalculator> calculators;

    public TaxCalculatorRegistry(List<TaxCalculator> calculators) {
        this.calculators = calculators.stream()
                .collect(Collectors.toMap(TaxCalculator::getCountryCode, Function.identity()));
        log.info("Calculadoras de impuestos registradas para países: {}", this.calculators.keySet());
    }

    public Optional<TaxCalculator> getForCountry(String countryCode) {
        return Optional.ofNullable(calculators.get(countryCode.toUpperCase()));
    }

    public boolean supportsCountry(String countryCode) {
        return calculators.containsKey(countryCode.toUpperCase());
    }
}
