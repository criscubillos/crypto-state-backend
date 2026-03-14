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
 * Adaptador Bybit v5 — Spot + Depósitos/Retiros.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /v5/execution/list         — historial de trades spot (cursor pagination)
 *   <li>GET /v5/asset/deposit/query-record  — depósitos
 *   <li>GET /v5/asset/withdraw/query-record — retiros
 * </ul>
 *
 * <p>Autenticación Bybit v5: HMAC-SHA256 de {@code timestamp + apiKey + recvWindow + queryString}.
 * Headers: {@code X-BAPI-API-KEY}, {@code X-BAPI-SIGN}, {@code X-BAPI-SIGN-TYPE: 2},
 *          {@code X-BAPI-TIMESTAMP}, {@code X-BAPI-RECV-WINDOW}.
 *
 * <p>Todos los endpoints retornan HTTP 200; usar {@code retCode == 0} para verificar éxito.
 * Los timestamps en las respuestas son strings de epoch ms (ej: {@code "1699999999000"}).
 */
@Slf4j
@Component
public class BybitAdapter implements ExchangeAdapter {

    private static final String BASE_URL    = "https://api.bybit.com";
    private static final String EXCHANGE_ID = "bybit";
    private static final String RECV_WINDOW = "5000";
    private static final int    LIMIT       = 100; // máximo permitido por Bybit v5

    private final WebClient    webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public BybitAdapter(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl(BASE_URL).build();
    }

    @Override
    public String getExchangeId() { return EXCHANGE_ID; }

    @Override
    public List<NormalizedTransaction> fetchAndNormalize(
            String apiKey, String apiSecret, Instant from, Instant to, String userId) {

        log.info("Bybit sync: userId={} from={} to={} key_prefix={}",
                userId, from, to,
                apiKey != null && apiKey.length() > 4 ? apiKey.substring(0, 4) + "***" : "null");

        List<NormalizedTransaction> all = new ArrayList<>();
        List<long[]> batches = buildMonthlyBatches(from, to);

        // ── 1. Spot Trades (execution list) ──────────────────────────────────
        int before = all.size();
        for (long[] batch : batches) {
            try {
                all.addAll(fetchSpotExecutions(apiKey, apiSecret, batch[0], batch[1], userId));
            } catch (Exception e) {
                log.warn("Bybit spot: lote {} error: {}", batch[0], e.getMessage());
            }
        }
        log.info("Bybit spot: {} trades normalizados", all.size() - before);

        // ── 2. Depósitos ──────────────────────────────────────────────────────
        before = all.size();
        for (long[] batch : batches) {
            try {
                all.addAll(fetchDeposits(apiKey, apiSecret, batch[0], batch[1], userId));
            } catch (Exception e) {
                log.warn("Bybit deposits: lote {} error: {}", batch[0], e.getMessage());
            }
        }
        log.info("Bybit depósitos: {} transacciones", all.size() - before);

        // ── 3. Retiros ────────────────────────────────────────────────────────
        before = all.size();
        for (long[] batch : batches) {
            try {
                all.addAll(fetchWithdrawals(apiKey, apiSecret, batch[0], batch[1], userId));
            } catch (Exception e) {
                log.warn("Bybit withdrawals: lote {} error: {}", batch[0], e.getMessage());
            }
        }
        log.info("Bybit retiros: {} transacciones", all.size() - before);

        log.info("Bybit sync completado: {} transacciones totales (userId={})", all.size(), userId);
        return all;
    }

    // ── Spot Executions ───────────────────────────────────────────────────────

    private List<NormalizedTransaction> fetchSpotExecutions(
            String apiKey, String apiSecret, long startMs, long endMs, String userId) throws Exception {

        List<NormalizedTransaction> result = new ArrayList<>();
        String cursor = null;

        while (true) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("category",  "spot");
            params.put("startTime", String.valueOf(startMs));
            params.put("endTime",   String.valueOf(endMs));
            params.put("limit",     String.valueOf(LIMIT));
            if (cursor != null && !cursor.isBlank()) {
                params.put("cursor", cursor);
            }

            String body = bybitGet("/v5/execution/list", params, apiKey, apiSecret);
            JsonNode root = mapper.readTree(body);
            checkBybitError(root, "/v5/execution/list");

            JsonNode list = root.path("result").path("list");
            if (!list.isArray() || list.isEmpty()) break;

            for (JsonNode exec : list) {
                NormalizedTransaction tx = normalizeExecution(exec, userId);
                if (tx != null) result.add(tx);
            }

            cursor = root.path("result").path("nextPageCursor").asText("");
            if (cursor.isBlank() || list.size() < LIMIT) break;
        }
        return result;
    }

    private NormalizedTransaction normalizeExecution(JsonNode e, String userId) {
        String execId  = e.path("execId").asText("");
        if (execId.isBlank()) return null;

        String symbol  = e.path("symbol").asText("");
        String side    = e.path("side").asText("Buy");
        boolean isBuy  = "Buy".equalsIgnoreCase(side);

        String[] assets   = splitSymbol(symbol);
        BigDecimal qty     = bd(e, "execQty");
        BigDecimal price   = bd(e, "execPrice");
        BigDecimal fee     = bd(e, "execFee");
        String feeCurrency = e.path("feeCurrency").asText(assets[1]);
        long tsMs          = e.path("execTime").asLong(0);

        return NormalizedTransaction.builder()
                .id(UUID.randomUUID())
                .userId(UUID.fromString(userId))
                .exchangeId(EXCHANGE_ID)
                .externalId("exec-" + execId)
                .type(isBuy ? TransactionType.SPOT_BUY : TransactionType.SPOT_SELL)
                .baseAsset(assets[0])
                .quoteAsset(assets[1])
                .quantity(qty)
                .price(price.compareTo(BigDecimal.ZERO) > 0 ? price : null)
                .fee(fee.compareTo(BigDecimal.ZERO) > 0 ? fee : null)
                .feeAsset(fee.compareTo(BigDecimal.ZERO) > 0 ? feeCurrency : null)
                .timestamp(tsMs > 0 ? Instant.ofEpochMilli(tsMs) : Instant.now())
                .rawData(Map.of(
                        "execId",    execId,
                        "symbol",    symbol,
                        "side",      side,
                        "execValue", e.path("execValue").asText("0"),
                        "orderType", e.path("orderType").asText(""),
                        "isMaker",   String.valueOf(e.path("isMaker").asBoolean())
                ))
                .build();
    }

    // ── Depósitos ─────────────────────────────────────────────────────────────

    private List<NormalizedTransaction> fetchDeposits(
            String apiKey, String apiSecret, long startMs, long endMs, String userId) throws Exception {

        List<NormalizedTransaction> result = new ArrayList<>();
        String cursor = null;

        while (true) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("startTime", String.valueOf(startMs));
            params.put("endTime",   String.valueOf(endMs));
            params.put("limit",     String.valueOf(LIMIT));
            if (cursor != null && !cursor.isBlank()) {
                params.put("cursor", cursor);
            }

            String body = bybitGet("/v5/asset/deposit/query-record", params, apiKey, apiSecret);
            JsonNode root = mapper.readTree(body);
            checkBybitError(root, "/v5/asset/deposit/query-record");

            JsonNode rows = root.path("result").path("rows");
            if (!rows.isArray() || rows.isEmpty()) break;

            for (JsonNode dep : rows) {
                if (dep.path("status").asInt(0) != 3) continue; // solo status=3 (success)
                BigDecimal amount = bd(dep, "amount");
                long tsMs = parseTsMs(dep.path("successAt").asText("0"));

                result.add(NormalizedTransaction.builder()
                        .id(UUID.randomUUID())
                        .userId(UUID.fromString(userId))
                        .exchangeId(EXCHANGE_ID)
                        .externalId("dep-" + dep.path("txID").asText(UUID.randomUUID().toString()))
                        .type(TransactionType.TRANSFER_IN)
                        .baseAsset(dep.path("coin").asText())
                        .quoteAsset("USDT")
                        .quantity(amount)
                        .timestamp(tsMs > 0 ? Instant.ofEpochMilli(tsMs) : Instant.now())
                        .rawData(Map.of(
                                "txID",    dep.path("txID").asText(),
                                "chain",   dep.path("chain").asText(),
                                "status",  dep.path("status").asText()
                        ))
                        .build());
            }

            cursor = root.path("result").path("nextPageCursor").asText("");
            if (cursor.isBlank() || rows.size() < LIMIT) break;
        }
        return result;
    }

    // ── Retiros ───────────────────────────────────────────────────────────────

    private List<NormalizedTransaction> fetchWithdrawals(
            String apiKey, String apiSecret, long startMs, long endMs, String userId) throws Exception {

        List<NormalizedTransaction> result = new ArrayList<>();
        String cursor = null;

        while (true) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("startTime", String.valueOf(startMs));
            params.put("endTime",   String.valueOf(endMs));
            params.put("limit",     String.valueOf(LIMIT));
            if (cursor != null && !cursor.isBlank()) {
                params.put("cursor", cursor);
            }

            String body = bybitGet("/v5/asset/withdraw/query-record", params, apiKey, apiSecret);
            JsonNode root = mapper.readTree(body);
            checkBybitError(root, "/v5/asset/withdraw/query-record");

            JsonNode rows = root.path("result").path("rows");
            if (!rows.isArray() || rows.isEmpty()) break;

            for (JsonNode w : rows) {
                // Solo registros completados
                String status = w.path("status").asText("");
                if (!"success".equalsIgnoreCase(status) && !"BlockchainConfirmed".equalsIgnoreCase(status)) continue;

                BigDecimal amount = bd(w, "amount");
                BigDecimal fee    = bd(w, "withdrawFee");
                long tsMs = parseTsMs(w.path("createTime").asText("0"));

                result.add(NormalizedTransaction.builder()
                        .id(UUID.randomUUID())
                        .userId(UUID.fromString(userId))
                        .exchangeId(EXCHANGE_ID)
                        .externalId("wd-" + w.path("withdrawId").asText(UUID.randomUUID().toString()))
                        .type(TransactionType.TRANSFER_OUT)
                        .baseAsset(w.path("coin").asText())
                        .quoteAsset("USDT")
                        .quantity(amount)
                        .fee(fee.compareTo(BigDecimal.ZERO) > 0 ? fee : null)
                        .feeAsset(fee.compareTo(BigDecimal.ZERO) > 0 ? w.path("coin").asText() : null)
                        .timestamp(tsMs > 0 ? Instant.ofEpochMilli(tsMs) : Instant.now())
                        .rawData(Map.of(
                                "withdrawId", w.path("withdrawId").asText(),
                                "txID",       w.path("txID").asText(),
                                "chain",      w.path("chain").asText(),
                                "toAddress",  w.path("toAddress").asText()
                        ))
                        .build());
            }

            cursor = root.path("result").path("nextPageCursor").asText("");
            if (cursor.isBlank() || rows.size() < LIMIT) break;
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
     * Divide un símbolo Bybit (BTCUSDT) en [base, quote].
     * Prueba sufijos de mayor a menor longitud para evitar ambigüedades.
     */
    private String[] splitSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return new String[]{"UNKNOWN", "USDT"};
        String s = symbol.toUpperCase();
        for (String q : List.of("USDT", "USDC", "BUSD", "FDUSD", "BTC", "ETH", "BNB", "USD")) {
            if (s.endsWith(q) && s.length() > q.length()) {
                return new String[]{s.substring(0, s.length() - q.length()), q};
            }
        }
        return new String[]{s.length() >= 3 ? s.substring(0, 3) : s, "USDT"};
    }

    /** Parsea epoch ms que puede llegar como string o number en el JSON */
    private long parseTsMs(String raw) {
        if (raw == null || raw.isBlank() || "0".equals(raw)) return 0;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            // Podría ser un datetime string en algunos endpoints
            try {
                return Instant.parse(raw.trim()).toEpochMilli();
            } catch (Exception ex) {
                log.warn("Bybit: no se pudo parsear timestamp '{}', usando 0", raw);
                return 0;
            }
        }
    }

    /**
     * Realiza un GET autenticado a Bybit v5.
     * Firma: HMAC-SHA256 de {@code timestamp + apiKey + recvWindow + queryString}
     */
    private String bybitGet(String path, Map<String, String> params,
                             String apiKey, String apiSecret) throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String qs = buildQs(params);
        String signPayload = timestamp + apiKey + RECV_WINDOW + qs;
        String signature   = sign(signPayload, apiSecret);

        return webClient.get()
                .uri(path + "?" + qs)
                .header("X-BAPI-API-KEY",     apiKey)
                .header("X-BAPI-SIGN",         signature)
                .header("X-BAPI-SIGN-TYPE",    "2")
                .header("X-BAPI-TIMESTAMP",    timestamp)
                .header("X-BAPI-RECV-WINDOW",  RECV_WINDOW)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    private void checkBybitError(JsonNode root, String endpoint) {
        int retCode = root.path("retCode").asInt(-1);
        if (retCode != 0) {
            throw new RuntimeException(
                    endpoint + " retCode=" + retCode + ": " + root.path("retMsg").asText());
        }
    }

    private String buildQs(Map<String, String> params) {
        StringJoiner sj = new StringJoiner("&");
        params.forEach((k, v) -> sj.add(k + "=" + v));
        return sj.toString();
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
