package com.cryptostate.backend.exchange.adapter;

import com.cryptostate.backend.transaction.model.NormalizedTransaction;
import com.cryptostate.backend.transaction.model.TransactionType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Adaptador BingX — API v1 Spot.
 *
 * Endpoints:
 * - GET /openApi/spot/v1/trade/historyOrders  (spot FILLED)
 *
 * Autenticación: HMAC-SHA256 sobre el query string, header X-BX-APIKEY.
 */
@Slf4j
@Component
public class BingXAdapter implements ExchangeAdapter {

    private static final String BASE_URL    = "https://open-api.bingx.com";
    private static final String EXCHANGE_ID = "bingx";
    private static final int    PAGE_LIMIT  = 100;

    // Pares a sincronizar — se saltean silenciosamente si el usuario no tiene historial
    private static final List<String> SYMBOLS = List.of(
            "BTC-USDT", "ETH-USDT", "SOL-USDT", "BNB-USDT",
            "XRP-USDT", "ADA-USDT", "DOGE-USDT", "AVAX-USDT",
            "DOT-USDT", "MATIC-USDT", "LINK-USDT", "UNI-USDT"
    );

    private final WebClient   webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public BingXAdapter(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl(BASE_URL).build();
    }

    @Override
    public String getExchangeId() { return EXCHANGE_ID; }

    @Override
    public List<NormalizedTransaction> fetchAndNormalize(
            String apiKey, String apiSecret, Instant from, Instant to, String userId) {

        log.info("BingX sync: userId={} from={} to={}", userId, from, to);
        List<NormalizedTransaction> all = new ArrayList<>();

        for (String symbol : SYMBOLS) {
            try {
                List<NormalizedTransaction> txs = fetchSymbol(apiKey, apiSecret, symbol, from, to, userId);
                all.addAll(txs);
                if (!txs.isEmpty()) {
                    log.info("BingX {}: {} transacciones", symbol, txs.size());
                }
            } catch (Exception e) {
                log.warn("BingX {}: error al obtener historial — {}", symbol, e.getMessage());
            }
        }

        log.info("BingX sync completado: {} transacciones totales para userId={}", all.size(), userId);
        return all;
    }

    // ── Fetch de un símbolo con paginación ───────────────────────────────────

    private List<NormalizedTransaction> fetchSymbol(
            String apiKey, String apiSecret, String symbol,
            Instant from, Instant to, String userId) throws Exception {

        List<NormalizedTransaction> result = new ArrayList<>();
        Long lastOrderId = null;
        long startMs = from.toEpochMilli();
        long endMs   = to.toEpochMilli();

        while (true) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("symbol",    symbol);
            params.put("startTime", String.valueOf(startMs));
            params.put("endTime",   String.valueOf(endMs));
            params.put("limit",     String.valueOf(PAGE_LIMIT));
            if (lastOrderId != null) params.put("orderId", String.valueOf(lastOrderId - 1));
            params.put("timestamp", String.valueOf(System.currentTimeMillis()));

            String queryString = buildQueryString(params);
            String signature   = sign(queryString, apiSecret);
            String url         = "/openApi/spot/v1/trade/historyOrders?" + queryString + "&signature=" + signature;

            String body = webClient.get()
                    .uri(url)
                    .header("X-BX-APIKEY", apiKey)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = mapper.readTree(body);
            int code = root.path("code").asInt(-1);

            if (code != 0) {
                String msg = root.path("msg").asText();
                // código 80014 = sin datos para el símbolo — normal, no es error
                if (code != 80014) log.warn("BingX API error {}: {} para símbolo {}", code, msg, symbol);
                break;
            }

            JsonNode orders = root.path("data").path("orders");
            if (!orders.isArray() || orders.isEmpty()) break;

            long minId = Long.MAX_VALUE;
            for (JsonNode order : orders) {
                if (!"FILLED".equals(order.path("status").asText())) continue;
                NormalizedTransaction tx = normalize(order, symbol, userId);
                result.add(tx);
                long oid = order.path("orderId").asLong();
                if (oid < minId) minId = oid;
            }

            // Si recibimos menos de PAGE_LIMIT ya no hay más páginas
            if (orders.size() < PAGE_LIMIT) break;
            lastOrderId = minId;
        }

        return result;
    }

    // ── Normalización ────────────────────────────────────────────────────────

    private NormalizedTransaction normalize(JsonNode order, String symbol, String userId) {
        String[] parts   = symbol.split("-");
        String base      = parts[0];
        String quote     = parts[1];

        String  side     = order.path("side").asText();
        boolean isBuy    = "BUY".equalsIgnoreCase(side);
        TransactionType type = isBuy ? TransactionType.SPOT_BUY : TransactionType.SPOT_SELL;

        BigDecimal price    = bd(order, "price");
        BigDecimal qty      = bd(order, "executedQty");
        BigDecimal fee      = bd(order, "fee");
        String feeAsset     = order.path("feeAsset").asText(quote);
        long   ts           = order.path("time").asLong();

        // PnL: solo ventas — BingX no provee cost-basis en este endpoint, se deja null
        // El módulo fiscal (FIFO) lo calculará a partir del historial completo
        BigDecimal pnl = null;

        return NormalizedTransaction.builder()
                .id(UUID.randomUUID())
                .userId(UUID.fromString(userId))
                .exchangeId(EXCHANGE_ID)
                .externalId(order.path("orderId").asText())
                .type(type)
                .baseAsset(base)
                .quoteAsset(quote)
                .quantity(qty)
                .price(price)
                .fee(fee.compareTo(BigDecimal.ZERO) < 0 ? fee.negate() : fee)
                .feeAsset(feeAsset)
                .realizedPnl(pnl)
                .timestamp(Instant.ofEpochMilli(ts))
                .rawData(Map.of(
                        "orderId",  order.path("orderId").asText(),
                        "symbol",   symbol,
                        "type",     order.path("type").asText(),
                        "side",     side,
                        "status",   order.path("status").asText()
                ))
                .build();
    }

    // ── Utilidades ───────────────────────────────────────────────────────────

    private String buildQueryString(Map<String, String> params) {
        StringJoiner sj = new StringJoiner("&");
        params.forEach((k, v) -> sj.add(k + "=" + v));
        return sj.toString();
    }

    private String sign(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    private BigDecimal bd(JsonNode node, String field) {
        String val = node.path(field).asText("0");
        try { return new BigDecimal(val).setScale(10, RoundingMode.HALF_UP); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }
}
