package com.cryptostate.backend.exchange.adapter;

import com.cryptostate.backend.transaction.model.NormalizedTransaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Adaptador para Bybit.
 *
 * Endpoints utilizados (Bybit API v5):
 * - GET /v5/execution/list              (trade history)
 * - GET /v5/asset/transfer/query-asset-info
 * - GET /v5/asset/deposit/query-record  (depósitos)
 * - GET /v5/asset/withdraw/query-record (retiros)
 *
 * TODO: Implementar firma HMAC-SHA256 requerida por Bybit API.
 */
@Slf4j
@Component
public class BybitAdapter implements ExchangeAdapter {

    private static final String BASE_URL = "https://api.bybit.com";
    private static final String EXCHANGE_ID = "bybit";

    private final WebClient webClient;

    public BybitAdapter(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl(BASE_URL).build();
    }

    @Override
    public String getExchangeId() {
        return EXCHANGE_ID;
    }

    @Override
    public List<NormalizedTransaction> fetchAndNormalize(
            String apiKey, String apiSecret, Instant from, Instant to, String userId) {
        log.info("Sincronizando Bybit para userId={}", userId);
        List<NormalizedTransaction> result = new ArrayList<>();

        // TODO: implementar llamadas firmadas a Bybit API v5
        // 1. Execution list (spot + futures)
        // 2. Earn/staking history
        // 3. Depósitos y retiros
        // Normalizar cada tipo a NormalizedTransaction

        return result;
    }
}
