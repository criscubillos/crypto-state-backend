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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Adaptador Binance — Spot + Convert + Earn + Depósitos/Retiros.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /api/v3/account           — descubrir activos con saldo
 *   <li>GET /api/v3/myTrades          — historial de trades spot (por símbolo)
 *   <li>GET /sapi/v1/convert/tradeFlow — historial de conversiones (Binance Convert)
 *   <li>GET /sapi/v1/asset/assetDividend — intereses Earn, staking, airdrops
 *   <li>GET /sapi/v1/capital/deposit/hisrec  — depósitos
 *   <li>GET /sapi/v1/capital/withdraw/history — retiros
 * </ul>
 *
 * <p>Autenticación: HMAC-SHA256 sobre el query string completo.
 * Header: {@code X-MBX-APIKEY}. Param: {@code signature}.
 */
@Slf4j
@Component
public class BinanceAdapter implements ExchangeAdapter {

    private static final String BASE_URL    = "https://api.binance.com";
    private static final String EXCHANGE_ID = "binance";
    private static final int    LIMIT       = 1000;
    private static final int    CONVERT_LIMIT = 1000;

    /** Activos que actúan de quote — no se construyen pares XXXBTC o XXXETH para ellos */
    private static final Set<String> QUOTE_ASSETS = Set.of(
            "USDT", "USDC", "BUSD", "DAI", "TUSD", "FDUSD", "BTC", "ETH", "BNB"
    );

    /** Pares spot que siempre se consultan (cubre activos completamente vendidos) */
    private static final List<String> ALWAYS_QUERY = List.of(
            "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
            "ADAUSDT", "DOGEUSDT", "AVAXUSDT", "DOTUSDT", "MATICUSDT",
            "LINKUSDT", "UNIUSDT", "ATOMUSDT", "LTCUSDT", "TRXUSDT",
            "NEARUSDT", "FTMUSDT", "ALGOUSDT", "VETUSDT", "SHIBUSDT",
            "APTUSDT", "ARBUSDT", "OPUSDT", "INJUSDT", "SUIUSDT",
            "PEPEUSDT", "WIFUSDT", "ETHBTC", "BNBBTC"
    );

    /** Formato de timestamp en retiros (applyTime / completeTime) */
    private static final DateTimeFormatter WITHDRAW_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final WebClient    webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public BinanceAdapter(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl(BASE_URL).build();
    }

    @Override
    public String getExchangeId() { return EXCHANGE_ID; }

    @Override
    public List<NormalizedTransaction> fetchAndNormalize(
            String apiKey, String apiSecret, Instant from, Instant to, String userId) {

        log.info("Binance sync: userId={} from={} to={} key_prefix={}",
                userId, from, to,
                apiKey != null && apiKey.length() > 6 ? apiKey.substring(0, 6) + "***" : "null");

        List<NormalizedTransaction> all = new ArrayList<>();
        List<long[]> batches = buildMonthlyBatches(from, to);

        // ── 1. Spot trades ────────────────────────────────────────────────────
        try {
            Set<String> symbols = discoverSymbols(apiKey, apiSecret);
            symbols.addAll(ALWAYS_QUERY);
            log.info("Binance spot: {} símbolos a consultar", symbols.size());

            for (String symbol : symbols) {
                for (long[] batch : batches) {
                    try {
                        List<NormalizedTransaction> txs =
                                fetchSpotTrades(apiKey, apiSecret, symbol, batch[0], batch[1], userId);
                        all.addAll(txs);
                    } catch (Exception e) {
                        log.warn("Binance spot {}: lote {} error: {}", symbol, batch[0], e.getMessage());
                    }
                }
            }
            log.info("Binance spot: {} trades normalizados", all.size());
        } catch (Exception e) {
            log.error("Binance: error descubriendo símbolos: {}", e.getMessage());
        }

        // ── 2. Convert (Binance Convert) ──────────────────────────────────────
        int before = all.size();
        for (long[] batch : batches) {
            try {
                all.addAll(fetchConvertHistory(apiKey, apiSecret, batch[0], batch[1], userId));
            } catch (Exception e) {
                log.warn("Binance convert: lote {} error: {}", batch[0], e.getMessage());
            }
        }
        log.info("Binance convert aportó: {} transacciones", all.size() - before);

        // ── 3. Earn / Dividends ───────────────────────────────────────────────
        before = all.size();
        for (long[] batch : batches) {
            try {
                all.addAll(fetchEarnDividends(apiKey, apiSecret, batch[0], batch[1], userId));
            } catch (Exception e) {
                log.warn("Binance earn: lote {} error: {}", batch[0], e.getMessage());
            }
        }
        log.info("Binance earn aportó: {} transacciones", all.size() - before);

        // ── 4. Depósitos ──────────────────────────────────────────────────────
        before = all.size();
        for (long[] batch : batches) {
            try {
                all.addAll(fetchDeposits(apiKey, apiSecret, batch[0], batch[1], userId));
            } catch (Exception e) {
                log.warn("Binance deposits: lote {} error: {}", batch[0], e.getMessage());
            }
        }
        log.info("Binance depósitos: {} transacciones", all.size() - before);

        // ── 5. Retiros ────────────────────────────────────────────────────────
        before = all.size();
        for (long[] batch : batches) {
            try {
                all.addAll(fetchWithdrawals(apiKey, apiSecret, batch[0], batch[1], userId));
            } catch (Exception e) {
                log.warn("Binance withdrawals: lote {} error: {}", batch[0], e.getMessage());
            }
        }
        log.info("Binance retiros: {} transacciones", all.size() - before);

        log.info("Binance sync completado: {} transacciones totales (userId={})", all.size(), userId);
        return all;
    }

    // ── Descubrir símbolos desde el balance ───────────────────────────────────

    private Set<String> discoverSymbols(String apiKey, String apiSecret) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("timestamp", ts());
        String qs  = qs(params);
        String body = get("/api/v3/account", qs + "&signature=" + sign(qs, apiSecret), apiKey);

        JsonNode root = mapper.readTree(body);
        checkBinanceError(root, "/api/v3/account");

        Set<String> symbols = new LinkedHashSet<>();
        for (JsonNode b : root.path("balances")) {
            String asset = b.path("asset").asText();
            BigDecimal free = bd(b, "free");
            if (QUOTE_ASSETS.contains(asset) || free.compareTo(BigDecimal.ZERO) <= 0) continue;
            symbols.add(asset + "USDT");
            symbols.add(asset + "BTC");
        }
        log.debug("Binance: {} símbolos descubiertos desde balance", symbols.size());
        return symbols;
    }

    // ── Spot Trades ───────────────────────────────────────────────────────────

    private List<NormalizedTransaction> fetchSpotTrades(
            String apiKey, String apiSecret, String symbol,
            long startMs, long endMs, String userId) throws Exception {

        List<NormalizedTransaction> result = new ArrayList<>();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol",    symbol);
        params.put("startTime", String.valueOf(startMs));
        params.put("endTime",   String.valueOf(endMs));
        params.put("limit",     String.valueOf(LIMIT));
        params.put("timestamp", ts());

        String qs   = qs(params);
        String body = get("/api/v3/myTrades", qs + "&signature=" + sign(qs, apiSecret), apiKey);
        JsonNode root = mapper.readTree(body);

        // myTrades devuelve array directo (sin wrapper) o un objeto de error
        if (root.isObject() && root.has("code")) {
            int code = root.path("code").asInt();
            if (code == -1121) return result; // símbolo inválido — ignorar silenciosamente
            log.warn("Binance myTrades {}: código {} — {}", symbol, code, root.path("msg").asText());
            return result;
        }

        if (!root.isArray()) return result;
        if (root.size() == LIMIT) {
            log.warn("Binance myTrades {}: lote lleno ({} trades) — puede haber datos truncados", symbol, LIMIT);
        }

        for (JsonNode trade : root) {
            result.add(normalizeSpotTrade(trade, symbol, userId));
        }
        return result;
    }

    private NormalizedTransaction normalizeSpotTrade(JsonNode t, String symbol, String userId) {
        String[] assets = splitSymbol(symbol);
        boolean isBuyer = t.path("isBuyer").asBoolean();

        return NormalizedTransaction.builder()
                .id(UUID.randomUUID())
                .userId(UUID.fromString(userId))
                .exchangeId(EXCHANGE_ID)
                .externalId("spot-" + t.path("id").asText())
                .type(isBuyer ? TransactionType.SPOT_BUY : TransactionType.SPOT_SELL)
                .baseAsset(assets[0])
                .quoteAsset(assets[1])
                .quantity(bd(t, "qty"))
                .price(bd(t, "price"))
                .fee(bd(t, "commission"))
                .feeAsset(t.path("commissionAsset").asText(assets[1]))
                .timestamp(Instant.ofEpochMilli(t.path("time").asLong()))
                .rawData(Map.of(
                        "tradeId",  t.path("id").asText(),
                        "orderId",  t.path("orderId").asText(),
                        "symbol",   symbol,
                        "isBuyer",  String.valueOf(isBuyer),
                        "quoteQty", t.path("quoteQty").asText()
                ))
                .build();
    }

    // ── Convert History ───────────────────────────────────────────────────────

    private List<NormalizedTransaction> fetchConvertHistory(
            String apiKey, String apiSecret, long startMs, long endMs, String userId) throws Exception {

        List<NormalizedTransaction> result = new ArrayList<>();
        Long fromId = null;
        boolean hasMore = true;

        while (hasMore) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("startTime", String.valueOf(startMs));
            params.put("endTime",   String.valueOf(endMs));
            params.put("limit",     String.valueOf(CONVERT_LIMIT));
            if (fromId != null) params.put("fromId", String.valueOf(fromId));
            params.put("timestamp", ts());

            String qs   = qs(params);
            String body = get("/sapi/v1/convert/tradeFlow", qs + "&signature=" + sign(qs, apiSecret), apiKey);
            JsonNode root = mapper.readTree(body);
            checkBinanceError(root, "/sapi/v1/convert/tradeFlow");

            JsonNode list = root.path("list");
            if (!list.isArray() || list.isEmpty()) break;

            long lastOrderId = 0;
            for (JsonNode conv : list) {
                if (!"SUCCESS".equals(conv.path("orderStatus").asText())) continue;
                result.add(normalizeConvert(conv, userId));
                long oid = conv.path("orderId").asLong();
                if (oid > lastOrderId) lastOrderId = oid;
            }

            hasMore = root.path("moreData").asBoolean(false);
            if (hasMore && lastOrderId > 0) {
                fromId = lastOrderId;
            } else {
                break;
            }
        }
        return result;
    }

    private NormalizedTransaction normalizeConvert(JsonNode c, String userId) {
        String fromAsset   = c.path("fromAsset").asText();
        String toAsset     = c.path("toAsset").asText();
        BigDecimal fromAmt = bd(c, "fromAmount");
        BigDecimal toAmt   = bd(c, "toAmount");
        long createTime    = c.path("createTime").asLong();
        boolean sellingCrypto = !QUOTE_ASSETS.contains(fromAsset) && QUOTE_ASSETS.contains(toAsset);

        // Si vende crypto → SPOT_SELL; si compra crypto → SPOT_BUY
        TransactionType type = sellingCrypto ? TransactionType.SPOT_SELL : TransactionType.SPOT_BUY;
        String baseAsset  = sellingCrypto ? fromAsset : toAsset;
        String quoteAsset = sellingCrypto ? toAsset   : fromAsset;
        BigDecimal qty    = sellingCrypto ? fromAmt   : toAmt;

        return NormalizedTransaction.builder()
                .id(UUID.randomUUID())
                .userId(UUID.fromString(userId))
                .exchangeId(EXCHANGE_ID)
                .externalId("convert-" + c.path("orderId").asText())
                .type(type)
                .baseAsset(baseAsset)
                .quoteAsset(quoteAsset)
                .quantity(qty)
                .timestamp(Instant.ofEpochMilli(createTime))
                .rawData(Map.of(
                        "quoteId",   c.path("quoteId").asText(),
                        "orderId",   c.path("orderId").asText(),
                        "fromAsset", fromAsset,
                        "toAsset",   toAsset,
                        "fromAmount", c.path("fromAmount").asText(),
                        "toAmount",   c.path("toAmount").asText()
                ))
                .build();
    }

    // ── Earn / Dividends ──────────────────────────────────────────────────────

    private List<NormalizedTransaction> fetchEarnDividends(
            String apiKey, String apiSecret, long startMs, long endMs, String userId) throws Exception {

        List<NormalizedTransaction> result = new ArrayList<>();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("startTime", String.valueOf(startMs));
        params.put("endTime",   String.valueOf(endMs));
        params.put("limit",     String.valueOf(500));
        params.put("timestamp", ts());

        String qs   = qs(params);
        String body = get("/sapi/v1/asset/assetDividend", qs + "&signature=" + sign(qs, apiSecret), apiKey);
        JsonNode root = mapper.readTree(body);
        checkBinanceError(root, "/sapi/v1/asset/assetDividend");

        for (JsonNode row : root.path("rows")) {
            // direction=1: ingreso (interés, reward). Ignoramos débitos.
            if (row.path("direction").asInt(1) != 1) continue;
            BigDecimal amount = bd(row, "amount");
            if (amount.compareTo(BigDecimal.ZERO) <= 0) continue;

            result.add(NormalizedTransaction.builder()
                    .id(UUID.randomUUID())
                    .userId(UUID.fromString(userId))
                    .exchangeId(EXCHANGE_ID)
                    .externalId("earn-" + row.path("id").asText())
                    .type(TransactionType.EARN)
                    .baseAsset(row.path("asset").asText())
                    .quoteAsset("USDT")
                    .quantity(amount)
                    .timestamp(Instant.ofEpochMilli(row.path("divTime").asLong()))
                    .rawData(Map.of(
                            "id",      row.path("id").asText(),
                            "tranId",  row.path("tranId").asText(),
                            "enInfo",  row.path("enInfo").asText()
                    ))
                    .build());
        }
        return result;
    }

    // ── Depósitos ─────────────────────────────────────────────────────────────

    private List<NormalizedTransaction> fetchDeposits(
            String apiKey, String apiSecret, long startMs, long endMs, String userId) throws Exception {

        List<NormalizedTransaction> result = new ArrayList<>();
        int offset = 0;

        while (true) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("startTime", String.valueOf(startMs));
            params.put("endTime",   String.valueOf(endMs));
            params.put("limit",     String.valueOf(LIMIT));
            params.put("offset",    String.valueOf(offset));
            params.put("status",    "1"); // solo completados
            params.put("timestamp", ts());

            String qs   = qs(params);
            String body = get("/sapi/v1/capital/deposit/hisrec", qs + "&signature=" + sign(qs, apiSecret), apiKey);
            JsonNode root = mapper.readTree(body);
            checkBinanceError(root, "/sapi/v1/capital/deposit/hisrec");

            if (!root.isArray() || root.isEmpty()) break;

            for (JsonNode dep : root) {
                BigDecimal amount = bd(dep, "amount");
                result.add(NormalizedTransaction.builder()
                        .id(UUID.randomUUID())
                        .userId(UUID.fromString(userId))
                        .exchangeId(EXCHANGE_ID)
                        .externalId("dep-" + dep.path("id").asText())
                        .type(TransactionType.TRANSFER_IN)
                        .baseAsset(dep.path("coin").asText())
                        .quoteAsset("USDT")
                        .quantity(amount)
                        .timestamp(Instant.ofEpochMilli(dep.path("insertTime").asLong()))
                        .rawData(Map.of(
                                "network", dep.path("network").asText(),
                                "txId",    dep.path("txId").asText(),
                                "status",  dep.path("status").asText()
                        ))
                        .build());
            }

            if (root.size() < LIMIT) break;
            offset += LIMIT;
        }
        return result;
    }

    // ── Retiros ───────────────────────────────────────────────────────────────

    private List<NormalizedTransaction> fetchWithdrawals(
            String apiKey, String apiSecret, long startMs, long endMs, String userId) throws Exception {

        List<NormalizedTransaction> result = new ArrayList<>();
        int offset = 0;

        while (true) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("startTime", String.valueOf(startMs));
            params.put("endTime",   String.valueOf(endMs));
            params.put("limit",     String.valueOf(LIMIT));
            params.put("offset",    String.valueOf(offset));
            params.put("status",    "6"); // solo completados
            params.put("timestamp", ts());

            String qs   = qs(params);
            String body = get("/sapi/v1/capital/withdraw/history", qs + "&signature=" + sign(qs, apiSecret), apiKey);
            JsonNode root = mapper.readTree(body);
            checkBinanceError(root, "/sapi/v1/capital/withdraw/history");

            if (!root.isArray() || root.isEmpty()) break;

            for (JsonNode w : root) {
                BigDecimal amount = bd(w, "amount");
                BigDecimal fee    = bd(w, "transactionFee");
                Instant ts = parseWithdrawTime(w.path("applyTime").asText(""));

                result.add(NormalizedTransaction.builder()
                        .id(UUID.randomUUID())
                        .userId(UUID.fromString(userId))
                        .exchangeId(EXCHANGE_ID)
                        .externalId("wd-" + w.path("id").asText())
                        .type(TransactionType.TRANSFER_OUT)
                        .baseAsset(w.path("coin").asText())
                        .quoteAsset("USDT")
                        .quantity(amount)
                        .fee(fee.compareTo(BigDecimal.ZERO) > 0 ? fee : null)
                        .feeAsset(fee.compareTo(BigDecimal.ZERO) > 0 ? w.path("coin").asText() : null)
                        .timestamp(ts)
                        .rawData(Map.of(
                                "network", w.path("network").asText(),
                                "txId",    w.path("txId").asText(),
                                "address", w.path("address").asText()
                        ))
                        .build());
            }

            if (root.size() < LIMIT) break;
            offset += LIMIT;
        }
        return result;
    }

    // ── Utilidades ────────────────────────────────────────────────────────────

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

    /**
     * Divide un símbolo Binance (sin guión) en [base, quote].
     * Intenta sufijos de mayor a menor para evitar ambigüedades (e.g. ETHBTC → [ETH, BTC]).
     */
    private String[] splitSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return new String[]{"UNKNOWN", "USDT"};
        for (String q : List.of("USDT", "USDC", "BUSD", "FDUSD", "TUSD", "BTC", "ETH", "BNB", "USD")) {
            if (symbol.endsWith(q) && symbol.length() > q.length()) {
                return new String[]{symbol.substring(0, symbol.length() - q.length()), q};
            }
        }
        return new String[]{symbol.length() >= 3 ? symbol.substring(0, 3) : symbol, "USDT"};
    }

    private Instant parseWithdrawTime(String s) {
        if (s == null || s.isBlank()) return Instant.now();
        try {
            return LocalDateTime.parse(s.trim(), WITHDRAW_TS).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            log.warn("Binance: no se pudo parsear applyTime '{}', usando now", s);
            return Instant.now();
        }
    }

    private void checkBinanceError(JsonNode root, String endpoint) {
        if (root.isObject() && root.has("code") && root.path("code").asInt(0) < 0) {
            throw new RuntimeException(
                    endpoint + " error " + root.path("code").asInt() + ": " + root.path("msg").asText());
        }
    }

    private String get(String path, String queryWithSig, String apiKey) {
        return webClient.get()
                .uri(path + "?" + queryWithSig)
                .header("X-MBX-APIKEY", apiKey)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    private String qs(Map<String, String> params) {
        StringJoiner sj = new StringJoiner("&");
        params.forEach((k, v) -> sj.add(k + "=" + v));
        return sj.toString();
    }

    private String ts() {
        return String.valueOf(System.currentTimeMillis());
    }

    private String sign(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private BigDecimal bd(JsonNode node, String field) {
        String val = node.path(field).asText("0");
        try { return new BigDecimal(val).setScale(10, RoundingMode.HALF_UP); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }
}
