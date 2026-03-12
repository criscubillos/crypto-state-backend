package com.cryptostate.backend.exchange.adapter;

import com.cryptostate.backend.transaction.model.NormalizedTransaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Adaptador para BingX.
 *
 * Endpoints utilizados (BingX API v3):
 * - GET /openApi/spot/v1/trade/historyOrders   (spot)
 * - GET /openApi/swap/v2/trade/allOrders       (perpetual futures)
 * - GET /openApi/wallets/v1/capital/deposit/hisrec
 *
 * TODO: Implementar firma HMAC-SHA256 requerida por BingX API.
 */
@Slf4j
@Component
public class BingXAdapter implements ExchangeAdapter {

    private static final String BASE_URL = "https://open-api.bingx.com";
    private static final String EXCHANGE_ID = "bingx";

    private final WebClient webClient;

    public BingXAdapter(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl(BASE_URL).build();
    }

    @Override
    public String getExchangeId() {
        return EXCHANGE_ID;
    }

    @Override
    public List<NormalizedTransaction> fetchAndNormalize(
            String apiKey, String apiSecret, Instant from, Instant to, String userId) {
        log.info("Sincronizando BingX para userId={}", userId);
        List<NormalizedTransaction> result = new ArrayList<>();

        // TODO: implementar llamadas firmadas a BingX API v3
        // 1. Spot orders history
        // 2. Perpetual futures history
        // 3. Depósitos y retiros
        // Normalizar cada tipo a NormalizedTransaction

        return result;
    }
}
