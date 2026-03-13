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
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Adaptador BingX — Spot + Futuros Perpetuos.
 *
 * Spot:    GET /openApi/spot/v1/trade/historyOrders   (por símbolo, batches mensuales)
 * Futuros: GET /openApi/swap/v2/trade/closeOrder      (posiciones cerradas con PnL)
 *
 * Autenticación: HMAC-SHA256 sobre el query string, header X-BX-APIKEY.
 */
@Slf4j
@Component
public class BingXAdapter implements ExchangeAdapter {

    private static final String BASE_URL    = "https://open-api.bingx.com";
    private static final String EXCHANGE_ID = "bingx";
    private static final int    PAGE_LIMIT  = 100;

    // Assets que actúan como quote — no se construyen pares para ellos
    private static final Set<String> QUOTE_ASSETS = Set.of(
            "USDT", "BUSD", "USDC", "DAI", "TUSD", "BTC", "ETH", "BNB"
    );

    // Pares spot que siempre se consultan (cubre activos completamente vendidos)
    private static final List<String> ALWAYS_QUERY = List.of(
            "BTC-USDT", "ETH-USDT", "BNB-USDT", "SOL-USDT", "XRP-USDT",
            "ADA-USDT", "DOGE-USDT", "AVAX-USDT", "DOT-USDT", "MATIC-USDT",
            "LINK-USDT", "UNI-USDT", "ATOM-USDT", "LTC-USDT", "TRX-USDT",
            "NEAR-USDT", "FTM-USDT", "ALGO-USDT", "VET-USDT", "ICP-USDT",
            "SHIB-USDT", "APT-USDT", "ARB-USDT", "OP-USDT", "INJ-USDT",
            "SUI-USDT", "SEI-USDT", "TIA-USDT", "PEPE-USDT", "WIF-USDT"
    );

    // Futuros perpetuos y standard USDT-margined
    // Nota: MATIC (→POL), FTM (→S), PEPE no existen en BingX swap — excluidos
    private static final List<String> FUTURES_SYMBOLS_USDT = List.of(
            "BTC-USDT", "ETH-USDT", "BNB-USDT", "SOL-USDT", "XRP-USDT",
            "ADA-USDT", "DOGE-USDT", "AVAX-USDT", "DOT-USDT",
            "LINK-USDT", "UNI-USDT", "ATOM-USDT", "LTC-USDT", "TRX-USDT",
            "NEAR-USDT", "INJ-USDT", "APT-USDT", "ARB-USDT",
            "OP-USDT", "SUI-USDT", "WIF-USDT", "POL-USDT", "S-USDT"
    );

    // Futuros coin-margined (colateral en la propia cripto, ej: BTCUSD)
    private static final List<String> FUTURES_SYMBOLS_COIN = List.of(
            "BTC-USD", "ETH-USD"
    );

    private final WebClient    webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public BingXAdapter(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder
                .baseUrl(BASE_URL)
                .filter((req, next) -> next.exchange(req)) // evita métricas por URI dinámica
                .build();
    }

    @Override
    public String getExchangeId() { return EXCHANGE_ID; }

    @Override
    public List<NormalizedTransaction> fetchAndNormalize(
            String apiKey, String apiSecret, Instant from, Instant to, String userId) {

        log.info("BingX sync: userId={} from={} to={} apiKey_prefix={}",
                userId, from, to,
                apiKey != null && apiKey.length() > 6 ? apiKey.substring(0, 6) + "***" : "null");

        List<NormalizedTransaction> all = new ArrayList<>();
        List<long[]> batches = buildMonthlyBatches(from, to);
        log.info("BingX: {} lotes mensuales (desde {} hasta {})", batches.size(), from, to);

        // ── SPOT: descubrir símbolos desde balance + lista fija
        try {
            Set<String> spotSymbols = discoverSymbolsFromBalance(apiKey, apiSecret);
            spotSymbols.addAll(ALWAYS_QUERY);
            log.info("BingX spot: {} símbolos a consultar", spotSymbols.size());
            for (String symbol : spotSymbols) {
                for (long[] batch : batches) {
                    try {
                        all.addAll(fetchSpotBatch(apiKey, apiSecret, symbol, batch[0], batch[1], userId));
                    } catch (Exception e) {
                        log.warn("BingX spot {} lote {}: {}", symbol, batch[0], e.getMessage());
                    }
                }
            }
            log.info("BingX spot: COMPLETADO — {} transacciones", all.size());
        } catch (Exception e) {
            log.warn("BingX spot: error obteniendo balance: {}", e.getMessage());
        }

        // ── FUTUROS PERPETUOS USDT-M
        int beforeFutures = all.size();
        all.addAll(fetchPositionHistory(apiKey, apiSecret, FUTURES_SYMBOLS_USDT, batches, userId));
        log.info("BingX futuros aportaron: {} transacciones", all.size() - beforeFutures);

        // BingX: positionHistory solo acepta -USDT; coin-M (BTC-USD) no soportado en este endpoint
        log.info("BingX sync completado: {} transacciones totales para userId={}", all.size(), userId);
        return all;
    }

    // ── Lotes mensuales ───────────────────────────────────────────────────────

    private List<long[]> buildMonthlyBatches(Instant from, Instant to) {
        List<long[]> batches = new ArrayList<>();
        ZonedDateTime cursor = from.atZone(ZoneOffset.UTC);
        ZonedDateTime end    = to.atZone(ZoneOffset.UTC);
        while (cursor.isBefore(end)) {
            ZonedDateTime next = cursor.plusMonths(1);
            if (next.isAfter(end)) next = end;
            batches.add(new long[]{ cursor.toInstant().toEpochMilli(), next.toInstant().toEpochMilli() });
            cursor = next;
        }
        return batches;
    }

    // ── Descubrir activos desde el balance spot ───────────────────────────────

    private Set<String> discoverSymbolsFromBalance(String apiKey, String apiSecret) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("timestamp", String.valueOf(System.currentTimeMillis()));
        String qs  = buildQueryString(params);
        String sig = sign(qs, apiSecret);

        String body = webClient.get()
                .uri("/openApi/spot/v1/account/balance?" + qs + "&signature=" + sig)
                .header("X-BX-APIKEY", apiKey)
                .retrieve().bodyToMono(String.class).block();

        JsonNode root = mapper.readTree(body);
        if (root.path("code").asInt(-1) != 0)
            throw new RuntimeException("Balance API error: " + root.path("msg").asText());

        Set<String> symbols = new LinkedHashSet<>();
        for (JsonNode b : root.path("data").path("balances")) {
            String asset = b.path("asset").asText();
            if (!QUOTE_ASSETS.contains(asset)) {
                symbols.add(asset + "-USDT");
                symbols.add(asset + "-BTC");
                symbols.add(asset + "-ETH");
            }
        }
        return symbols;
    }

    // ── SPOT: historyOrders por símbolo y lote ────────────────────────────────

    private List<NormalizedTransaction> fetchSpotBatch(
            String apiKey, String apiSecret, String symbol,
            long startMs, long endMs, String userId) throws Exception {

        List<NormalizedTransaction> result = new ArrayList<>();
        Long lastOrderId = null;

        while (true) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("symbol",    symbol);
            params.put("startTime", String.valueOf(startMs));
            params.put("endTime",   String.valueOf(endMs));
            params.put("limit",     String.valueOf(PAGE_LIMIT));
            if (lastOrderId != null) params.put("orderId", String.valueOf(lastOrderId - 1));
            params.put("timestamp", String.valueOf(System.currentTimeMillis()));

            String qs  = buildQueryString(params);
            String sig = sign(qs, apiSecret);
            String body = webClient.get()
                    .uri("/openApi/spot/v1/trade/historyOrders?" + qs + "&signature=" + sig)
                    .header("X-BX-APIKEY", apiKey)
                    .retrieve().bodyToMono(String.class).block();

            JsonNode root = mapper.readTree(body);
            int code = root.path("code").asInt(-1);
            if (code != 0) {
                if (code != 80014)
                    log.warn("BingX spot API error {}: {} símbolo={}", code, root.path("msg").asText(), symbol);
                break;
            }

            JsonNode orders = root.path("data").path("orders");
            if (!orders.isArray() || orders.isEmpty()) break;

            long minId = Long.MAX_VALUE;
            for (JsonNode order : orders) {
                if (!"FILLED".equals(order.path("status").asText())) continue;
                result.add(normalizeSpot(order, symbol, userId));
                long oid = order.path("orderId").asLong();
                if (oid < minId) minId = oid;
            }

            if (orders.size() < PAGE_LIMIT) break;
            lastOrderId = minId;
        }
        return result;
    }

    // ── FUTUROS PERPETUOS: positionHistory por símbolo y lote ────────────────
    // Endpoint: GET /openApi/swap/v1/trade/positionHistory
    // Params: symbol, startTs, endTs, pageIndex, pageSize (pageSize max=100)

    private List<NormalizedTransaction> fetchPositionHistory(
            String apiKey, String apiSecret, List<String> symbols,
            List<long[]> batches, String userId) {

        List<NormalizedTransaction> all = new ArrayList<>();
        boolean firstCall = true;

        log.info("BingX futuros: consultando {} símbolos x {} lotes = {} requests",
                symbols.size(), batches.size(), symbols.size() * batches.size());

        for (String symbol : symbols) {
            int symbolTotal = 0;
            for (long[] batch : batches) {
                try {
                    List<NormalizedTransaction> txs =
                            fetchPositionHistoryBatch(apiKey, apiSecret, symbol,
                                    batch[0], batch[1], userId, firstCall);
                    firstCall = false;
                    symbolTotal += txs.size();
                    if (!txs.isEmpty())
                        log.info("BingX futuros {}: {} posiciones en lote {}",
                                symbol, txs.size(),
                                Instant.ofEpochMilli(batch[0]).toString().substring(0, 10));
                    all.addAll(txs);
                } catch (Exception e) {
                    log.warn("BingX futuros {} lote {}: {}", symbol, batch[0], e.getMessage());
                    firstCall = false;
                }
            }
            if (symbolTotal > 0)
                log.info("BingX futuros {}: {} posiciones totales en todos los lotes", symbol, symbolTotal);
        }
        log.info("BingX futuros: COMPLETADO — {} posiciones totales", all.size());
        return all;
    }

    private List<NormalizedTransaction> fetchPositionHistoryBatch(
            String apiKey, String apiSecret, String symbol,
            long startMs, long endMs, String userId, boolean logRaw) throws Exception {

        List<NormalizedTransaction> result = new ArrayList<>();
        int pageIndex = 1;

        while (true) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("symbol",    symbol);
            params.put("startTs",   String.valueOf(startMs));
            params.put("endTs",     String.valueOf(endMs));
            params.put("pageIndex", String.valueOf(pageIndex));
            params.put("pageSize",  String.valueOf(PAGE_LIMIT));
            params.put("timestamp", String.valueOf(System.currentTimeMillis()));

            String qs  = buildQueryString(params);
            String sig = sign(qs, apiSecret);
            String body = webClient.get()
                    .uri("/openApi/swap/v1/trade/positionHistory?" + qs + "&signature=" + sig)
                    .header("X-BX-APIKEY", apiKey)
                    .retrieve().bodyToMono(String.class).block();

            if (logRaw) {
                log.info("BingX futuros raw [{}]: {}", symbol,
                        body != null && body.length() > 500 ? body.substring(0, 500) : body);
                logRaw = false;
            }

            JsonNode root = mapper.readTree(body);
            int code = root.path("code").asInt(-1);
            if (code != 0) {
                if (code != 80014)
                    log.warn("BingX futuros positionHistory error {}: '{}' símbolo={}",
                            code, root.path("msg").asText(), symbol);
                break;
            }

            JsonNode positions = root.path("data").path("positionHistory");
            if (!positions.isArray() || positions.isEmpty()) break;

            for (JsonNode pos : positions) {
                NormalizedTransaction tx = normalizePosition(pos, userId);
                if (tx != null) result.add(tx);
            }

            if (positions.size() < PAGE_LIMIT) break;
            pageIndex++;
        }
        return result;
    }

    // ── Normalización SPOT ────────────────────────────────────────────────────

    private NormalizedTransaction normalizeSpot(JsonNode order, String symbol, String userId) {
        String[] parts = symbol.split("-");
        String base    = parts[0];
        String quote   = parts[1];

        String  side  = order.path("side").asText();
        boolean isBuy = "BUY".equalsIgnoreCase(side);

        BigDecimal price    = bd(order, "price");
        BigDecimal qty      = bd(order, "executedQty");
        BigDecimal fee      = bd(order, "fee");
        String feeAsset     = order.path("feeAsset").asText(quote);
        long   ts           = order.path("time").asLong();

        return NormalizedTransaction.builder()
                .id(UUID.randomUUID())
                .userId(UUID.fromString(userId))
                .exchangeId(EXCHANGE_ID)
                .externalId("spot-" + order.path("orderId").asText())
                .type(isBuy ? TransactionType.SPOT_BUY : TransactionType.SPOT_SELL)
                .baseAsset(base)
                .quoteAsset(quote)
                .quantity(qty)
                .price(price)
                .fee(fee.compareTo(BigDecimal.ZERO) < 0 ? fee.negate() : fee)
                .feeAsset(feeAsset)
                .realizedPnl(null)
                .timestamp(Instant.ofEpochMilli(ts))
                .rawData(Map.of(
                        "orderId", order.path("orderId").asText(),
                        "symbol", symbol, "side", side,
                        "status", order.path("status").asText(),
                        "market", "spot"
                ))
                .build();
    }

    // ── Normalización FUTUROS (positionHistory) ───────────────────────────────

    /**
     * Normaliza un registro de positionHistory.
     * Campos relevantes: positionId, symbol, positionSide, updateTime,
     *                    avgClosePrice, closePositionAmt, realisedProfit,
     *                    positionCommission, totalFunding
     */
    private NormalizedTransaction normalizePosition(JsonNode pos, String userId) {
        String positionId   = pos.path("positionId").asText("");
        if (positionId.isBlank()) return null;

        String symbol       = pos.path("symbol").asText("");
        String positionSide = pos.path("positionSide").asText("LONG");

        String[] parts = symbol.split("-");
        String base    = parts[0];
        String quote   = parts.length > 1 ? parts[1] : "USDT";
        String market  = "USD".equals(quote) ? "coin-m" : "perp-usdt";

        BigDecimal qty      = bd(pos, "closePositionAmt");
        BigDecimal price    = bd(pos, "avgClosePrice");
        BigDecimal pnl      = bd(pos, "realisedProfit");
        BigDecimal fee      = bd(pos, "positionCommission");
        long ts             = pos.path("updateTime").asLong();
        if (ts == 0) ts     = pos.path("openTime").asLong();

        TransactionType type = "SHORT".equalsIgnoreCase(positionSide)
                ? TransactionType.FUTURES_SHORT : TransactionType.FUTURES_LONG;

        return NormalizedTransaction.builder()
                .id(UUID.randomUUID())
                .userId(UUID.fromString(userId))
                .exchangeId(EXCHANGE_ID)
                .externalId("fut-" + market + "-" + positionId)
                .type(type)
                .baseAsset(base)
                .quoteAsset(quote)
                .quantity(qty)
                .price(price)
                .fee(fee.compareTo(BigDecimal.ZERO) < 0 ? fee.negate() : fee)
                .feeAsset(base)  // coin-M: comisión en la cripto base
                .realizedPnl(pnl)
                .timestamp(Instant.ofEpochMilli(ts))
                .rawData(Map.of(
                        "positionId",   positionId,
                        "symbol",       symbol,
                        "positionSide", positionSide,
                        "market",       market
                ))
                .build();
    }

    // ── Utilidades ────────────────────────────────────────────────────────────

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
