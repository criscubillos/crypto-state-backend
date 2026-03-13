package com.cryptostate.backend.exchange.importer;

import com.cryptostate.backend.common.util.CoinGeckoPriceService;
import com.cryptostate.backend.transaction.model.NormalizedTransaction;
import com.cryptostate.backend.transaction.model.TransactionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class BingXExcelImporter implements ExchangeImporter {

    private final CoinGeckoPriceService priceService;

    private static final DateTimeFormatter TS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String SHEET_USDTM   = "USDⓢ_M_Perpetual_Futures";
    private static final String SHEET_STD     = "Standard_Futures";
    private static final String SHEET_COINM   = "Coin_M_Perpetual_Futures";
    private static final String SHEET_SPOT    = "Spot_Account";
    private static final String SHEET_FUND    = "Fund_Account";

    @Override
    public String exchangeId() {
        return "bingx";
    }

    @Override
    public List<NormalizedTransaction> parse(InputStream input, UUID userId, UUID connectionId) throws Exception {
        List<NormalizedTransaction> result = new ArrayList<>();

        try (Workbook workbook = new XSSFWorkbook(input)) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                String name = sheet.getSheetName();
                log.debug("Procesando hoja: '{}'", name);

                if (SHEET_USDTM.equals(name)) {
                    result.addAll(parseFuturesSheet(sheet, userId, connectionId, "usdtm", "bingx-xl-usdtm-"));
                } else if (SHEET_COINM.equals(name)) {
                    result.addAll(parseFuturesSheet(sheet, userId, connectionId, "coinm", "bingx-xl-coinm-"));
                } else if (SHEET_STD.equals(name)) {
                    result.addAll(parseStandardFuturesSheet(sheet, userId, connectionId));
                } else if (SHEET_SPOT.equals(name)) {
                    result.addAll(parseSpotSheet(sheet, userId, connectionId));
                } else if (SHEET_FUND.equals(name)) {
                    result.addAll(parseFundSheet(sheet, userId, connectionId));
                } else {
                    log.debug("Hoja desconocida ignorada: '{}'", name);
                }
            }
        }

        // Calcular valores en USD para todas las transacciones
        log.info("Calculando valores USD para {} transacciones (puede tardar por CoinGecko rate limit)...", result.size());
        result.forEach(this::computeUsdValues);

        log.info("BingX Excel parse: {} transacciones extraídas (userId={})", result.size(), userId);
        return result;
    }

    // ── USDT-M and Coin-M Perpetual Futures ──────────────────────────────────

    private List<NormalizedTransaction> parseFuturesSheet(
            Sheet sheet, UUID userId, UUID connectionId,
            String sheetType, String externalIdPrefix) {

        List<NormalizedTransaction> txs = new ArrayList<>();
        List<Map<String, String>> rows = readRowsAsMap(sheet);

        // Group by (Time, Futures/symbol)
        Map<String, List<Map<String, String>>> grouped = rows.stream()
                .collect(Collectors.groupingBy(row -> {
                    String time = getCell(row, "Time(UTC+8)");
                    String symbol = getCell(row, "Futures");
                    if (symbol.isBlank()) symbol = getCell(row, "Assets");
                    return time + "|" + symbol;
                }));

        for (Map.Entry<String, List<Map<String, String>>> entry : grouped.entrySet()) {
            List<Map<String, String>> group = entry.getValue();

            List<Map<String, String>> pnlRows = group.stream()
                    .filter(r -> {
                        String t = getCell(r, "type");
                        return "Realized PnL".equalsIgnoreCase(t)
                                || "Forced liquidation".equalsIgnoreCase(t);
                    })
                    .toList();
            List<Map<String, String>> feeRows = group.stream()
                    .filter(r -> "Trading fee".equalsIgnoreCase(getCell(r, "type")))
                    .toList();
            List<Map<String, String>> fundingRows = group.stream()
                    .filter(r -> "Funding Fee".equalsIgnoreCase(getCell(r, "type")))
                    .toList();

            // Process Realized PnL / Forced liquidation rows
            for (Map<String, String> pnlRow : pnlRows) {
                String timeStr  = getCell(pnlRow, "Time(UTC+8)");
                String symbol   = getCell(pnlRow, "Futures");
                if (symbol.isBlank()) symbol = getCell(pnlRow, "Assets");
                String details  = getCell(pnlRow, "Details");
                String amountStr = getCell(pnlRow, "Amount");
                String assets   = getCell(pnlRow, "Assets");

                Instant timestamp;
                try {
                    timestamp = parseTimestamp(timeStr);
                } catch (Exception e) {
                    log.warn("No se pudo parsear timestamp '{}', omitiendo fila", timeStr);
                    continue;
                }

                BigDecimal realizedPnl = parseBigDecimal(amountStr);

                // Find matching closing fee
                BigDecimal fee = BigDecimal.ZERO;
                String feeAsset = assets;
                for (Map<String, String> feeRow : feeRows) {
                    String feeDetails = getCell(feeRow, "Details");
                    if (feeDetails.toLowerCase().contains("closing")) {
                        fee = parseBigDecimal(getCell(feeRow, "Amount")).abs();
                        feeAsset = getCell(feeRow, "Assets");
                        if (feeAsset.isBlank()) feeAsset = assets;
                        break;
                    }
                }

                TransactionType type = determineFuturesType(details);
                String[] assets12 = splitSymbol(symbol);
                String baseAsset  = assets12[0];
                String quoteAsset = assets12[1];

                // En Coin-M, el PnL y las fees se liquidan en el baseAsset (ej: ETH para ETH-USD).
                // La columna "Assets" del Excel de BingX puede mostrar "USD" (el colateral),
                // por lo que debemos forzar el settlement al baseAsset para la conversión USD.
                if ("coinm".equals(sheetType)) {
                    feeAsset = baseAsset;
                }

                long tsMs = timestamp.toEpochMilli();
                String amtNoDot = amountStr.replace(".", "").replace("-", "");
                String extId = (externalIdPrefix + tsMs + "-" + symbol + "-" + amtNoDot);
                if (extId.length() > 255) extId = extId.substring(0, 255);

                NormalizedTransaction tx = NormalizedTransaction.builder()
                        .id(UUID.randomUUID())
                        .userId(userId)
                        .connectionId(connectionId)
                        .exchangeId("bingx")
                        .externalId(extId)
                        .type(type)
                        .baseAsset(baseAsset)
                        .quoteAsset(quoteAsset)
                        .realizedPnl(realizedPnl)
                        .fee(fee)
                        .feeAsset(feeAsset)
                        .timestamp(timestamp)
                        .rawData(new HashMap<>(pnlRow))
                        .build();
                txs.add(tx);
            }

            // Process Funding Fee rows
            for (Map<String, String> fundingRow : fundingRows) {
                String timeStr   = getCell(fundingRow, "Time(UTC+8)");
                String symbol    = getCell(fundingRow, "Futures");
                if (symbol.isBlank()) symbol = getCell(fundingRow, "Assets");
                String amountStr = getCell(fundingRow, "Amount");
                String assets    = getCell(fundingRow, "Assets");

                Instant timestamp;
                try {
                    timestamp = parseTimestamp(timeStr);
                } catch (Exception e) {
                    log.warn("No se pudo parsear timestamp funding '{}', omitiendo fila", timeStr);
                    continue;
                }

                long tsMs = timestamp.toEpochMilli();
                String extId = ("bingx-xl-funding-" + tsMs + "-" + symbol);
                if (extId.length() > 255) extId = extId.substring(0, 255);

                String[] assets12 = splitSymbol(symbol);

                NormalizedTransaction tx = NormalizedTransaction.builder()
                        .id(UUID.randomUUID())
                        .userId(userId)
                        .connectionId(connectionId)
                        .exchangeId("bingx")
                        .externalId(extId)
                        .type(TransactionType.FEE)
                        .baseAsset(assets12[0])
                        .quoteAsset(assets12[1])
                        .fee(parseBigDecimal(amountStr).abs())
                        .feeAsset(assets)
                        .timestamp(timestamp)
                        .rawData(new HashMap<>(fundingRow))
                        .build();
                txs.add(tx);
            }
        }

        log.debug("Hoja {}: {} transacciones", sheetType, txs.size());
        return txs;
    }

    // ── Standard Futures ─────────────────────────────────────────────────────

    private List<NormalizedTransaction> parseStandardFuturesSheet(
            Sheet sheet, UUID userId, UUID connectionId) {

        List<NormalizedTransaction> txs = new ArrayList<>();
        List<Map<String, String>> rows = readRowsAsMap(sheet);

        Map<String, List<Map<String, String>>> grouped = rows.stream()
                .collect(Collectors.groupingBy(row -> {
                    String time = getCell(row, "Time(UTC+8)");
                    String symbol = getCell(row, "Futures");
                    if (symbol.isBlank()) symbol = getCell(row, "Assets");
                    return time + "|" + symbol;
                }));

        for (Map.Entry<String, List<Map<String, String>>> entry : grouped.entrySet()) {
            List<Map<String, String>> group = entry.getValue();

            List<Map<String, String>> pnlRows = group.stream()
                    .filter(r -> {
                        String t = getCell(r, "type");
                        return "Realized PnL".equalsIgnoreCase(t)
                                || "Forced liquidation".equalsIgnoreCase(t);
                    })
                    .toList();
            List<Map<String, String>> feeRows = group.stream()
                    .filter(r -> "Trading fee".equalsIgnoreCase(getCell(r, "type")))
                    .toList();
            List<Map<String, String>> fundingRows = group.stream()
                    .filter(r -> "Funding Fee".equalsIgnoreCase(getCell(r, "type")))
                    .toList();

            for (Map<String, String> pnlRow : pnlRows) {
                String timeStr   = getCell(pnlRow, "Time(UTC+8)");
                String symbol    = getCell(pnlRow, "Futures");
                if (symbol.isBlank()) symbol = getCell(pnlRow, "Assets");
                String amountStr = getCell(pnlRow, "Amount");
                String assets    = getCell(pnlRow, "Assets");

                Instant timestamp;
                try {
                    timestamp = parseTimestamp(timeStr);
                } catch (Exception e) {
                    log.warn("No se pudo parsear timestamp std '{}', omitiendo fila", timeStr);
                    continue;
                }

                BigDecimal realizedPnl = parseBigDecimal(amountStr);

                BigDecimal fee = BigDecimal.ZERO;
                String feeAsset = assets;
                for (Map<String, String> feeRow : feeRows) {
                    String feeDetails = getCell(feeRow, "Details");
                    if (feeDetails.toLowerCase().contains("closing")) {
                        fee = parseBigDecimal(getCell(feeRow, "Amount")).abs();
                        feeAsset = getCell(feeRow, "Assets");
                        if (feeAsset.isBlank()) feeAsset = assets;
                        break;
                    }
                }

                // Standard futures: cannot determine direction, default FUTURES_LONG
                TransactionType type = TransactionType.FUTURES_LONG;
                String[] assets12 = splitStandardSymbol(symbol);

                long tsMs = timestamp.toEpochMilli();
                String amtNoDot = amountStr.replace(".", "").replace("-", "");
                String extId = ("bingx-xl-std-" + tsMs + "-" + symbol + "-" + amtNoDot);
                if (extId.length() > 255) extId = extId.substring(0, 255);

                NormalizedTransaction tx = NormalizedTransaction.builder()
                        .id(UUID.randomUUID())
                        .userId(userId)
                        .connectionId(connectionId)
                        .exchangeId("bingx")
                        .externalId(extId)
                        .type(type)
                        .baseAsset(assets12[0])
                        .quoteAsset(assets12[1])
                        .realizedPnl(realizedPnl)
                        .fee(fee)
                        .feeAsset(feeAsset)
                        .timestamp(timestamp)
                        .rawData(new HashMap<>(pnlRow))
                        .build();
                txs.add(tx);
            }

            for (Map<String, String> fundingRow : fundingRows) {
                String timeStr   = getCell(fundingRow, "Time(UTC+8)");
                String symbol    = getCell(fundingRow, "Futures");
                if (symbol.isBlank()) symbol = getCell(fundingRow, "Assets");
                String amountStr = getCell(fundingRow, "Amount");
                String assets    = getCell(fundingRow, "Assets");

                Instant timestamp;
                try {
                    timestamp = parseTimestamp(timeStr);
                } catch (Exception e) {
                    log.warn("No se pudo parsear timestamp funding std '{}', omitiendo fila", timeStr);
                    continue;
                }

                long tsMs = timestamp.toEpochMilli();
                String extId = ("bingx-xl-funding-std-" + tsMs + "-" + symbol);
                if (extId.length() > 255) extId = extId.substring(0, 255);

                String[] assets12 = splitStandardSymbol(symbol);

                NormalizedTransaction tx = NormalizedTransaction.builder()
                        .id(UUID.randomUUID())
                        .userId(userId)
                        .connectionId(connectionId)
                        .exchangeId("bingx")
                        .externalId(extId)
                        .type(TransactionType.FEE)
                        .baseAsset(assets12[0])
                        .quoteAsset(assets12[1])
                        .fee(parseBigDecimal(amountStr).abs())
                        .feeAsset(assets)
                        .timestamp(timestamp)
                        .rawData(new HashMap<>(fundingRow))
                        .build();
                txs.add(tx);
            }
        }

        log.debug("Hoja Standard_Futures: {} transacciones", txs.size());
        return txs;
    }

    // ── Spot Account ─────────────────────────────────────────────────────────

    private List<NormalizedTransaction> parseSpotSheet(
            Sheet sheet, UUID userId, UUID connectionId) {

        List<NormalizedTransaction> txs = new ArrayList<>();
        List<Map<String, String>> rows = readRowsAsMap(sheet);

        for (Map<String, String> row : rows) {
            String typeStr   = getCell(row, "type");
            String timeStr   = getCell(row, "Time(UTC+8)");
            String amountStr = getCell(row, "Amount");
            String assets    = getCell(row, "Assets");

            if (typeStr.isBlank()) continue;

            TransactionType type;
            if (typeStr.equalsIgnoreCase("Buy")) {
                type = TransactionType.SPOT_BUY;
            } else if (typeStr.equalsIgnoreCase("Sell")) {
                type = TransactionType.SPOT_SELL;
            } else {
                type = TransactionType.OTHER;
            }

            Instant timestamp;
            try {
                timestamp = parseTimestamp(timeStr);
            } catch (Exception e) {
                log.warn("No se pudo parsear timestamp spot '{}', omitiendo fila", timeStr);
                continue;
            }

            long tsMs = timestamp.toEpochMilli();
            String amtNoDot = amountStr.replace(".", "").replace("-", "");
            String extId = ("bingx-xl-spot-" + tsMs + "-" + assets + "-" + amtNoDot);
            if (extId.length() > 255) extId = extId.substring(0, 255);

            NormalizedTransaction tx = NormalizedTransaction.builder()
                    .id(UUID.randomUUID())
                    .userId(userId)
                    .connectionId(connectionId)
                    .exchangeId("bingx")
                    .externalId(extId)
                    .type(type)
                    .baseAsset(assets)
                    .quoteAsset("USDT")
                    .quantity(parseBigDecimal(amountStr).abs())
                    .timestamp(timestamp)
                    .rawData(new HashMap<>(row))
                    .build();
            txs.add(tx);
        }

        log.debug("Hoja Spot_Account: {} transacciones", txs.size());
        return txs;
    }

    // ── Fund Account ─────────────────────────────────────────────────────────

    private List<NormalizedTransaction> parseFundSheet(
            Sheet sheet, UUID userId, UUID connectionId) {

        List<NormalizedTransaction> txs = new ArrayList<>();
        List<Map<String, String>> rows = readRowsAsMap(sheet);

        for (Map<String, String> row : rows) {
            String typeStr   = getCell(row, "type");
            // Fund_Account has Time(UTC+8) as last column
            String timeStr   = getCell(row, "Time(UTC+8)");
            String amountStr = getCell(row, "amount");
            String assetName = getCell(row, "asset_name");

            if (typeStr.isBlank()) continue;

            TransactionType type = mapFundType(typeStr);

            Instant timestamp;
            try {
                timestamp = parseTimestamp(timeStr);
            } catch (Exception e) {
                log.warn("No se pudo parsear timestamp fund '{}', omitiendo fila", timeStr);
                continue;
            }

            long tsMs = timestamp.toEpochMilli();
            String amtNoDot = amountStr.replace(".", "").replace("-", "");
            String extId = ("bingx-xl-fund-" + tsMs + "-" + assetName + "-" + amtNoDot);
            if (extId.length() > 255) extId = extId.substring(0, 255);

            NormalizedTransaction tx = NormalizedTransaction.builder()
                    .id(UUID.randomUUID())
                    .userId(userId)
                    .connectionId(connectionId)
                    .exchangeId("bingx")
                    .externalId(extId)
                    .type(type)
                    .baseAsset(assetName)
                    .quantity(parseBigDecimal(amountStr).abs())
                    .timestamp(timestamp)
                    .rawData(new HashMap<>(row))
                    .build();
            txs.add(tx);
        }

        log.debug("Hoja Fund_Account: {} transacciones", txs.size());
        return txs;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Calcula realizedPnlUsd y feeUsd para una transacción.
     * Si el asset de liquidación es stablecoin, es 1:1.
     * Para Coin-M (BTC, DOGE, etc.) consulta CoinGecko con precio histórico.
     */
    private void computeUsdValues(NormalizedTransaction tx) {
        // El asset de liquidación es el feeAsset (en qué moneda se cobra la fee y se realiza el PnL)
        String settlementAsset = tx.getFeeAsset() != null ? tx.getFeeAsset() : tx.getQuoteAsset();

        if (tx.getRealizedPnl() != null) {
            BigDecimal pnlUsd = priceService.toUsd(tx.getRealizedPnl(), settlementAsset, tx.getTimestamp());
            tx.setRealizedPnlUsd(pnlUsd);
        }
        if (tx.getFee() != null) {
            BigDecimal feeUsd = priceService.toUsd(tx.getFee(), settlementAsset, tx.getTimestamp());
            tx.setFeeUsd(feeUsd);
        }
    }

    private TransactionType mapFundType(String typeStr) {
        if (typeStr == null) return TransactionType.OTHER;
        String t = typeStr.toLowerCase();
        if (t.contains("deposit") || t.contains("redemption") || t.contains("transfer in") || t.contains("receive")) {
            return TransactionType.TRANSFER_IN;
        }
        if (t.contains("withdraw") || t.contains("transfer out") || t.contains("send")) {
            return TransactionType.TRANSFER_OUT;
        }
        return TransactionType.OTHER;
    }

    private Instant parseTimestamp(String timeStr) {
        LocalDateTime ldt = LocalDateTime.parse(timeStr.trim(), TS_FORMATTER);
        return ldt.toInstant(ZoneOffset.ofHours(8));
    }

    private TransactionType determineFuturesType(String details) {
        if (details == null) return TransactionType.FUTURES_LONG;
        String d = details.toLowerCase();
        if (d.contains("sell")) return TransactionType.FUTURES_LONG;
        if (d.contains("buy"))  return TransactionType.FUTURES_SHORT;
        return TransactionType.FUTURES_LONG;
    }

    /** Split "BTC-USDT" -> ["BTC","USDT"]; "BTC-USD" -> ["BTC","USD"]; no dash -> best effort */
    private String[] splitSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return new String[]{"UNKNOWN", "USDT"};
        int dash = symbol.indexOf('-');
        if (dash > 0) {
            return new String[]{symbol.substring(0, dash), symbol.substring(dash + 1)};
        }
        // No dash: first 3 chars as base, rest as quote
        if (symbol.length() > 4) {
            return new String[]{symbol.substring(0, 3), symbol.substring(symbol.length() - 4)};
        }
        return new String[]{symbol, "USDT"};
    }

    /** For Standard_Futures: BTCUSDT -> [BTC, USDT]; Gold/Crude Oil -> [symbol, USD] */
    private String[] splitStandardSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return new String[]{"UNKNOWN", "USD"};
        // If it's all uppercase letters (no spaces) and ends with USDT or USDC
        if (symbol.matches("[A-Z0-9]+")) {
            if (symbol.endsWith("USDT") && symbol.length() > 4) {
                return new String[]{symbol.substring(0, symbol.length() - 4), "USDT"};
            }
            if (symbol.endsWith("USDC") && symbol.length() > 4) {
                return new String[]{symbol.substring(0, symbol.length() - 4), "USDC"};
            }
            if (symbol.endsWith("USD") && symbol.length() > 3) {
                return new String[]{symbol.substring(0, symbol.length() - 3), "USD"};
            }
            // Fallback: first 3 chars as base
            if (symbol.length() >= 3) {
                return new String[]{symbol.substring(0, 3), "USDT"};
            }
        }
        // Complex names like "Gold", "Crude Oil" -> treat whole as base
        return new String[]{symbol, "USD"};
    }

    private BigDecimal parseBigDecimal(String value) {
        if (value == null || value.isBlank()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(value.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            log.warn("No se pudo parsear número: '{}'", value);
            return BigDecimal.ZERO;
        }
    }

    private String getCell(Map<String, String> row, String key) {
        String val = row.get(key);
        return val != null ? val : "";
    }

    /**
     * Read all data rows from a sheet as List of Map<headerName, cellValue>.
     * First non-empty row is treated as the header row.
     */
    private List<Map<String, String>> readRowsAsMap(Sheet sheet) {
        List<Map<String, String>> result = new ArrayList<>();
        List<String> headers = null;

        for (Row row : sheet) {
            List<String> cells = readRow(row);
            if (cells.stream().allMatch(String::isBlank)) continue; // skip empty rows

            if (headers == null) {
                headers = cells;
                continue;
            }

            Map<String, String> map = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                String header = headers.get(i);
                String value  = i < cells.size() ? cells.get(i) : "";
                map.put(header, value);
            }
            result.add(map);
        }

        return result;
    }

    private List<String> readRow(Row row) {
        List<String> cells = new ArrayList<>();
        if (row == null) return cells;
        int lastCell = row.getLastCellNum();
        for (int i = 0; i < lastCell; i++) {
            Cell cell = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            cells.add(cellToString(cell));
        }
        return cells;
    }

    private String cellToString(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    // For date cells, format as string
                    LocalDateTime ldt = cell.getLocalDateTimeCellValue();
                    yield ldt != null ? ldt.format(TS_FORMATTER) : "";
                }
                // Use BigDecimal to avoid scientific notation
                double d = cell.getNumericCellValue();
                BigDecimal bd = new BigDecimal(d).stripTrailingZeros();
                yield bd.toPlainString();
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield String.valueOf(cell.getNumericCellValue());
                } catch (Exception e) {
                    yield cell.getStringCellValue();
                }
            }
            default -> "";
        };
    }
}
