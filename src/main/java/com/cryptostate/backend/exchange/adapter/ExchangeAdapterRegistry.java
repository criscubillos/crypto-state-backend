package com.cryptostate.backend.exchange.adapter;

import com.cryptostate.backend.common.exception.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ExchangeAdapterRegistry {

    private final Map<String, ExchangeAdapter> adapters;

    /** Spring inyecta automáticamente todos los beans que implementan ExchangeAdapter */
    public ExchangeAdapterRegistry(List<ExchangeAdapter> adapters) {
        this.adapters = adapters.stream()
                .collect(Collectors.toMap(ExchangeAdapter::getExchangeId, Function.identity()));
        log.info("Exchanges registrados: {}", this.adapters.keySet());
    }

    public ExchangeAdapter get(String exchangeId) {
        ExchangeAdapter adapter = adapters.get(exchangeId.toLowerCase());
        if (adapter == null) {
            throw ApiException.badRequest("Exchange no soportado: " + exchangeId);
        }
        return adapter;
    }

    public boolean supports(String exchangeId) {
        return adapters.containsKey(exchangeId.toLowerCase());
    }

    public java.util.Set<String> supportedExchanges() {
        return adapters.keySet();
    }
}
