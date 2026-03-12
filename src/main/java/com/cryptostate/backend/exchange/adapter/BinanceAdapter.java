package com.cryptostate.backend.exchange.adapter;

import com.cryptostate.backend.transaction.model.NormalizedTransaction;
import com.cryptostate.backend.transaction.model.TransactionType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Adaptador para Binance.
 *
 * Endpoints utilizados:
 * - GET /api/v3/myTrades        (spot trades)
 * - GET /sapi/v1/earn/...       (earn history)
 * - GET /sapi/v1/capital/deposit/hisrec (depósitos)
 * - GET /sapi/v1/capital/withdraw/history (retiros)
 *
 * TODO: Implementar firma HMAC-SHA256 requerida por Binance API.
 */
@Slf4j
@Component
public class BinanceAdapter implements ExchangeAdapter {

    private static final String BASE_URL = "https://api.binance.com";
    private static final String EXCHANGE_ID = "binance";

    private final WebClient webClient;

    public BinanceAdapter(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl(BASE_URL).build();
    }

    @Override
    public String getExchangeId() {
        return EXCHANGE_ID;
    }

    @Override
    public List<NormalizedTransaction> fetchAndNormalize(
            String apiKey, String apiSecret, Instant from, Instant to, String userId) {
        log.info("Sincronizando Binance para userId={}", userId);
        List<NormalizedTransaction> result = new ArrayList<>();

        // TODO: implementar llamadas firmadas a Binance API
        // 1. Obtener spot trades por símbolo
        // 2. Obtener earn history
        // 3. Obtener depósitos y retiros
        // Normalizar cada tipo a NormalizedTransaction

        return result;
    }

    /**
     * Construye la firma HMAC-SHA256 requerida por Binance.
     * Firma = HMAC-SHA256(queryString, apiSecret)
     */
    private String buildSignature(String queryString, String apiSecret) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec secretKey =
                new javax.crypto.spec.SecretKeySpec(apiSecret.getBytes(), "HmacSHA256");
            mac.init(secretKey);
            byte[] hash = mac.doFinal(queryString.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Error construyendo firma Binance", e);
        }
    }

    private NormalizedTransaction mapSpotTrade(Object raw, String userId) {
        // TODO: mapear respuesta de /api/v3/myTrades
        return NormalizedTransaction.builder()
                .id(UUID.randomUUID())
                .userId(UUID.fromString(userId))
                .exchangeId(EXCHANGE_ID)
                .type(TransactionType.SPOT_BUY)
                .build();
    }
}
