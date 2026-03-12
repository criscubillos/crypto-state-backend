package com.cryptostate.backend.exchange.adapter;

import com.cryptostate.backend.transaction.model.NormalizedTransaction;

import java.time.Instant;
import java.util.List;

/**
 * Contrato que debe implementar cada adaptador de exchange.
 * Para agregar un nuevo exchange: crear una clase que implemente esta interfaz
 * y anotarla con {@code @Component} — el registry la detecta automáticamente.
 */
public interface ExchangeAdapter {

    /** Identificador único del exchange. Ej: "binance", "bingx", "bybit" */
    String getExchangeId();

    /**
     * Descarga y normaliza todas las transacciones del exchange en el rango dado.
     *
     * @param apiKey     API key en texto plano (ya desencriptada)
     * @param apiSecret  API secret en texto plano (ya desencriptado)
     * @param from       inicio del período (inclusive)
     * @param to         fin del período (inclusive)
     * @param userId     UUID del usuario propietario
     * @return lista de transacciones normalizadas
     */
    List<NormalizedTransaction> fetchAndNormalize(
        String apiKey, String apiSecret, Instant from, Instant to, String userId);
}
