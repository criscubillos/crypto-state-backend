package com.cryptostate.backend.exchange.importer;

import com.cryptostate.backend.common.exception.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class ExchangeImporterRegistry {

    private final List<ExchangeImporter> importers;

    /** Spring inyecta automáticamente todos los beans que implementan ExchangeImporter */
    public ExchangeImporterRegistry(List<ExchangeImporter> importers) {
        this.importers = importers;
        log.info("Importadores Excel registrados: {}",
                importers.stream().map(ExchangeImporter::exchangeId).toList());
    }

    public ExchangeImporter get(String exchangeId) {
        return importers.stream()
                .filter(i -> i.exchangeId().equals(exchangeId))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest("No hay importador para: " + exchangeId));
    }

    public boolean supports(String exchangeId) {
        return importers.stream().anyMatch(i -> i.exchangeId().equals(exchangeId));
    }
}
