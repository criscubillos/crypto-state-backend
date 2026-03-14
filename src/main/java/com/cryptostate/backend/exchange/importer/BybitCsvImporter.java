package com.cryptostate.backend.exchange.importer;

import com.cryptostate.backend.common.util.CoinGeckoPriceService;
import com.cryptostate.backend.transaction.model.NormalizedTransaction;
import com.cryptostate.backend.transaction.model.TransactionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Importador del historial de trades spot de Bybit en formato CSV.
 *
 * <p>Bybit exporta un CSV con las siguientes columnas:
 * <pre>
 * Spot Pairs | Order Type | Direction | feeCoin | ExecFeeV2 | Filled Value |
 * Filled Price | Filled Quantity | Fees | Transaction ID | Order No. | Timestamp (UTC)
 * </pre>
 *
 * <p>Notas del formato:
 * <ul>
 *   <li>Timestamp en formato "HH:mm yyyy-MM-dd" (UTC).</li>
 *   <li>Cuando {@code feeCoin != "--"}: la fee está en {@code ExecFeeV2} en la moneda {@code feeCoin}.</li>
 *   <li>Cuando {@code feeCoin == "--"}: la fee está en la columna {@code Fees}; la moneda se infiere
 *       por dirección (BUY → base asset, SELL → quote asset).</li>
 *   <li>El {@code Transaction ID} se usa como {@code externalId} para deduplicación.</li>
 *   <li>Los archivos de subcuentas se importan con el mismo importador; la diferencia la controla
 *       el {@code connectionId} de la conexión seleccionada.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BybitCsvImporter implements ExchangeImporter {

    private final CoinGeckoPriceService priceService;

    /** Bybit exporta timestamps como "HH:mm yyyy-MM-dd" (sin segundos) en UTC */
    private static final DateTimeFormatter TS_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm yyyy-MM-dd");

    private static final String EMPTY_CELL = "--";

    @Override
    public String exchangeId() {
        return "bybit";
    }

    @Override
    public List<NormalizedTransaction> parse(InputStream input, UUID userId, UUID connectionId) throws Exception {
        List<NormalizedTransaction> result = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                log.warn("Bybit CSV vacío (userId={})", userId);
                return result;
            }

            // Eliminar BOM si está presente
            if (headerLine.startsWith("\uFEFF")) {
                headerLine = headerLine.substring(1);
            }

            List<String> headers = parseCsvLine(headerLine);
            log.debug("Bybit CSV cabeceras: {}", headers);

            String line;
            int lineNum = 1;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                if (line.isBlank()) continue;

                List<String> cells = parseCsvLine(line);
                if (cells.size() < headers.size()) {
                    log.warn("Bybit CSV línea {} con {} celdas (esperadas {}), omitiendo",
                            lineNum, cells.size(), headers.size());
                    continue;
                }

                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    row.put(headers.get(i).trim(), cells.get(i).trim());
                }

                NormalizedTransaction tx = processRow(row, userId, connectionId);
                if (tx != null) result.add(tx);
            }
        }

        result.forEach(this::computeUsdValues);

        log.info("Bybit CSV parse: {} transacciones normalizadas (userId={})", result.size(), userId);
        return result;
    }

    // ── Procesamiento de filas ────────────────────────────────────────────────

    private NormalizedTransaction processRow(Map<String, String> row, UUID userId, UUID connectionId) {
        String spotPairs   = getCell(row, "Spot Pairs");
        String direction   = getCell(row, "Direction");
        String feeCoin     = getCell(row, "feeCoin");
        String execFeeV2   = getCell(row, "ExecFeeV2");
        String filledValue = getCell(row, "Filled Value");
        String filledPrice = getCell(row, "Filled Price");
        String filledQty   = getCell(row, "Filled Quantity");
        String feesCol     = getCell(row, "Fees");
        String txId        = getCell(row, "Transaction ID");
        String timestampS  = getCell(row, "Timestamp (UTC)");

        if (spotPairs.isBlank() || direction.isBlank() || timestampS.isBlank()) return null;

        Instant timestamp;
        try {
            timestamp = parseTimestamp(timestampS);
        } catch (Exception e) {
            log.warn("Bybit: no se pudo parsear timestamp '{}' (txId={}), omitiendo", timestampS, txId);
            return null;
        }

        String[] assets    = splitSymbol(spotPairs);
        String baseAsset   = assets[0];
        String quoteAsset  = assets[1];

        TransactionType type = direction.equalsIgnoreCase("BUY")
                ? TransactionType.SPOT_BUY
                : TransactionType.SPOT_SELL;

        BigDecimal quantity = parseBigDecimal(filledQty);
        BigDecimal price    = parseBigDecimal(filledPrice);
        BigDecimal value    = parseBigDecimal(filledValue);

        // Resolución de fee y feeAsset
        BigDecimal fee;
        String feeAsset;
        if (!isEmpty(feeCoin)) {
            fee      = parseBigDecimal(execFeeV2);
            feeAsset = feeCoin;
        } else if (!isEmpty(feesCol)) {
            fee      = parseBigDecimal(feesCol);
            // Si BUY la fee se cobra en el asset comprado; si SELL en el asset de pago (quote)
            feeAsset = type == TransactionType.SPOT_BUY ? baseAsset : quoteAsset;
        } else {
            fee      = BigDecimal.ZERO;
            feeAsset = quoteAsset;
        }

        // externalId basado en Transaction ID (único por trade en Bybit)
        String externalId = "bybit-csv-" + (txId.isBlank() ? buildFallbackId(timestampS, spotPairs, filledQty) : txId);
        if (externalId.length() > 255) externalId = externalId.substring(0, 255);

        return NormalizedTransaction.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .connectionId(connectionId)
                .exchangeId("bybit")
                .externalId(externalId)
                .type(type)
                .baseAsset(baseAsset)
                .quoteAsset(quoteAsset)
                .quantity(quantity)
                .price(price.compareTo(BigDecimal.ZERO) > 0 ? price : null)
                .fee(fee.compareTo(BigDecimal.ZERO) > 0 ? fee : null)
                .feeAsset(fee.compareTo(BigDecimal.ZERO) > 0 ? feeAsset : null)
                .timestamp(timestamp)
                .rawData(toRawData(row))
                .build();
    }

    // ── USD values ───────────────────────────────────────────────────────────

    private void computeUsdValues(NormalizedTransaction tx) {
        if (tx.getFee() != null && tx.getFeeAsset() != null) {
            tx.setFeeUsd(priceService.toUsd(tx.getFee(), tx.getFeeAsset(), tx.getTimestamp()));
        }
    }

    // ── Parseo del timestamp ──────────────────────────────────────────────────

    /**
     * Parsea el timestamp de Bybit en formato "HH:mm yyyy-MM-dd" (UTC).
     * Ejemplo: "08:42 2025-11-04"
     */
    private Instant parseTimestamp(String raw) {
        return LocalDateTime.parse(raw.trim(), TS_FORMATTER).toInstant(ZoneOffset.UTC);
    }

    // ── Split del par de trading ──────────────────────────────────────────────

    /**
     * Divide un símbolo compuesto (ej: "BTCUSDT") en [base, quote].
     * Intenta primero los sufijos más largos para evitar ambigüedades.
     */
    private String[] splitSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return new String[]{"UNKNOWN", "USDT"};
        String s = symbol.toUpperCase();

        // Sufijos conocidos ordenados de mayor a menor longitud para evitar falsos matches
        for (String quote : List.of("USDT", "USDC", "BUSD", "FDUSD", "TUSD", "BTC", "ETH", "BNB")) {
            if (s.endsWith(quote) && s.length() > quote.length()) {
                return new String[]{s.substring(0, s.length() - quote.length()), quote};
            }
        }

        // Fallback: primeros 3 chars como base
        if (s.length() >= 4) {
            return new String[]{s.substring(0, 3), s.substring(3)};
        }
        return new String[]{s, "USDT"};
    }

    // ── Parseo CSV ────────────────────────────────────────────────────────────

    /**
     * Parser CSV simple que maneja campos entrecomillados con comas internas.
     */
    private List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BigDecimal parseBigDecimal(String value) {
        if (isEmpty(value)) return BigDecimal.ZERO;
        try {
            return new BigDecimal(value.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            log.warn("Bybit: no se pudo parsear número: '{}'", value);
            return BigDecimal.ZERO;
        }
    }

    private boolean isEmpty(String value) {
        return value == null || value.isBlank() || EMPTY_CELL.equals(value.trim());
    }

    private String getCell(Map<String, String> row, String key) {
        String val = row.get(key);
        return val != null ? val : "";
    }

    private String buildFallbackId(String timestamp, String pair, String qty) {
        return timestamp.replaceAll("[^0-9]", "") + "-" + pair + "-" + qty.replace(".", "");
    }

    private Map<String, Object> toRawData(Map<String, String> row) {
        Map<String, Object> raw = new HashMap<>();
        raw.putAll(row);
        return raw;
    }
}
