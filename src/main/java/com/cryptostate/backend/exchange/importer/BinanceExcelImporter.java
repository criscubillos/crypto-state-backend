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

/**
 * Importador del historial de transacciones de Binance en formato XLSX.
 *
 * <p>El archivo exportado desde Binance tiene una sola hoja ("Sheet0") con metadatos
 * en las primeras filas y la cabecera real en la fila que contiene "ID de usuario" y "Hora".
 * Las columnas relevantes son:
 * <ul>
 *   <li>Hora — timestamp en formato "yy-MM-dd HH:mm:ss" (UTC-3)</li>
 *   <li>Cuenta — Spot, Coin-M Futures, USD-M Futures, etc.</li>
 *   <li>Operación — tipo de movimiento</li>
 *   <li>Moneda — símbolo del activo (BTC, USDT, ETH…)</li>
 *   <li>Cambiar — importe (positivo = ingreso, negativo = egreso)</li>
 *   <li>Comentario — etiqueta adicional (p.ej. "Binance Earn")</li>
 * </ul>
 *
 * <p>Estrategia de normalización:
 * <ul>
 *   <li>Spot trades (Convert, Buy/Sell): solo se registra el lado crypto; el lado stablecoin se descarta.</li>
 *   <li>Earn/Airdrops: se registran como {@code EARN}.</li>
 *   <li>Fees y Funding Fees: se registran como {@code FEE}.</li>
 *   <li>Realized PnL de futuros: se registra como {@code FUTURES_LONG} o {@code FUTURES_SHORT}.</li>
 *   <li>Depósitos/Retiros: {@code TRANSFER_IN} / {@code TRANSFER_OUT}.</li>
 *   <li>Suscripciones/Redenciones de Earn y transfers internas: se omiten (no afectan P&L).</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BinanceExcelImporter implements ExchangeImporter {

    private final CoinGeckoPriceService priceService;

    /** Binance exporta el timestamp como texto "yy-MM-dd HH:mm:ss" */
    private static final DateTimeFormatter TS_FORMATTER =
            DateTimeFormatter.ofPattern("yy-MM-dd HH:mm:ss");

    /** Al leer celdas de fecha formateadas por POI, usamos 4 dígitos de año */
    private static final DateTimeFormatter CELL_TS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Offset de la exportación de Binance Chile */
    private static final ZoneOffset EXPORT_OFFSET = ZoneOffset.ofHours(-3);

    private static final Set<String> STABLECOINS = Set.of("USDT", "USDC", "BUSD", "DAI", "FDUSD", "TUSD");

    @Override
    public String exchangeId() {
        return "binance";
    }

    @Override
    public List<NormalizedTransaction> parse(InputStream input, UUID userId, UUID connectionId) throws Exception {
        List<NormalizedTransaction> result = new ArrayList<>();

        try (Workbook workbook = new XSSFWorkbook(input)) {
            Sheet sheet = workbook.getSheet("Sheet0");
            if (sheet == null) sheet = workbook.getSheetAt(0);

            List<Map<String, String>> rows = readDataRows(sheet);
            log.info("Binance Excel: {} filas de datos leídas (userId={})", rows.size(), userId);

            for (Map<String, String> row : rows) {
                NormalizedTransaction tx = processRow(row, userId, connectionId);
                if (tx != null) result.add(tx);
            }
        }

        // Calcular valores USD para fees y PnL de futuros
        result.forEach(this::computeUsdValues);

        log.info("Binance Excel parse: {} transacciones normalizadas (userId={})", result.size(), userId);
        return result;
    }

    // ── Procesamiento de filas ────────────────────────────────────────────────

    private NormalizedTransaction processRow(Map<String, String> row, UUID userId, UUID connectionId) {
        String op       = getCell(row, "Operación");
        String hora     = getCell(row, "Hora");
        String moneda   = getCell(row, "Moneda");
        String cambiarS = getCell(row, "Cambiar");
        String cuenta   = getCell(row, "Cuenta");

        if (op.isBlank() || hora.isBlank() || moneda.isBlank()) return null;

        BigDecimal amount = parseBigDecimal(cambiarS);
        boolean isPositive = amount.compareTo(BigDecimal.ZERO) > 0;
        boolean isNegative = amount.compareTo(BigDecimal.ZERO) < 0;

        Instant timestamp;
        try {
            timestamp = parseTimestamp(hora);
        } catch (Exception e) {
            log.warn("Binance: no se pudo parsear timestamp '{}' (op={}), omitiendo", hora, op);
            return null;
        }

        return switch (op) {
            // ── Spot trades — solo se captura el lado crypto ──────────────────
            case "Transaction Buy", "Auto-Invest Transaction" -> {
                // Positive amount = crypto recibida (compra)
                if (isPositive && !isStablecoin(moneda)) {
                    yield buildSpotTx(userId, connectionId, TransactionType.SPOT_BUY,
                            moneda, amount, timestamp, hora, row);
                }
                yield null;
            }
            case "Transaction Spend" -> null; // lado stablecoin de una compra, se descarta

            case "Transaction Sold" -> {
                // Negative amount = crypto vendida (venta)
                if (isNegative && !isStablecoin(moneda)) {
                    yield buildSpotTx(userId, connectionId, TransactionType.SPOT_SELL,
                            moneda, amount.abs(), timestamp, hora, row);
                }
                yield null;
            }
            case "Transaction Revenue" -> null; // lado stablecoin de una venta, se descarta

            case "Binance Convert" -> {
                // Positivo en crypto = compra; negativo en crypto = venta; stablecoins = descartar
                if (isStablecoin(moneda)) {
                    yield null;
                } else if (isPositive) {
                    yield buildSpotTx(userId, connectionId, TransactionType.SPOT_BUY,
                            moneda, amount, timestamp, hora, row);
                } else if (isNegative) {
                    yield buildSpotTx(userId, connectionId, TransactionType.SPOT_SELL,
                            moneda, amount.abs(), timestamp, hora, row);
                } else {
                    yield null;
                }
            }

            // ── Earn / Airdrops ───────────────────────────────────────────────
            case "Simple Earn Flexible Interest",
                 "HODLer Airdrops Distribution",
                 "Launchpool Airdrop - System Distribution" -> {
                if (isPositive) {
                    yield buildEarnTx(userId, connectionId, moneda, amount, timestamp, hora, row);
                }
                yield null;
            }
            // Suscripción y Redención son movimientos internos entre Spot y Earn: sin impacto fiscal
            case "Simple Earn Flexible Subscription",
                 "Simple Earn Flexible Redemption" -> null;

            // ── Fees ──────────────────────────────────────────────────────────
            case "Funding Fee" -> {
                // Funding fee de futuros: puede ser positiva (cobrada) o negativa (pagada)
                yield buildFeeTx(userId, connectionId, moneda, amount.abs(), timestamp, hora, row);
            }
            case "Fee", "Transaction Fee" -> {
                if (isNegative) {
                    yield buildFeeTx(userId, connectionId, moneda, amount.abs(), timestamp, hora, row);
                }
                yield null;
            }

            // ── Futuros ───────────────────────────────────────────────────────
            case "Realized Profit and Loss" ->
                    buildFuturesPnlTx(userId, connectionId, cuenta, moneda, amount, timestamp, hora, row);

            // ── Depósitos / Retiros ───────────────────────────────────────────
            case "Deposit" ->
                    buildTransferTx(userId, connectionId, TransactionType.TRANSFER_IN,
                            moneda, amount.abs(), timestamp, hora, row);

            case "Withdraw" ->
                    buildTransferTx(userId, connectionId, TransactionType.TRANSFER_OUT,
                            moneda, amount.abs(), timestamp, hora, row);

            // ── Transfers entre cuentas internas ─────────────────────────────
            default -> {
                if (op.startsWith("Transfer") || op.startsWith("Funds Transfer")) {
                    TransactionType type = isPositive ? TransactionType.TRANSFER_IN : TransactionType.TRANSFER_OUT;
                    yield buildTransferTx(userId, connectionId, type, moneda, amount.abs(), timestamp, hora, row);
                }
                log.debug("Binance: operación no mapeada ignorada: '{}'", op);
                yield null;
            }
        };
    }

    // ── Builders ─────────────────────────────────────────────────────────────

    private NormalizedTransaction buildSpotTx(UUID userId, UUID connectionId, TransactionType type,
                                               String asset, BigDecimal quantity,
                                               Instant timestamp, String hora, Map<String, String> raw) {
        return NormalizedTransaction.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .connectionId(connectionId)
                .exchangeId("binance")
                .externalId(buildExtId("bn-spot-", hora, asset, quantity.toPlainString()))
                .type(type)
                .baseAsset(asset)
                .quoteAsset("USDT")
                .quantity(quantity)
                .timestamp(timestamp)
                .rawData(toRawData(raw))
                .build();
    }

    private NormalizedTransaction buildEarnTx(UUID userId, UUID connectionId,
                                               String asset, BigDecimal amount,
                                               Instant timestamp, String hora, Map<String, String> raw) {
        return NormalizedTransaction.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .connectionId(connectionId)
                .exchangeId("binance")
                .externalId(buildExtId("bn-earn-", hora, asset, amount.toPlainString()))
                .type(TransactionType.EARN)
                .baseAsset(asset)
                .quoteAsset("USDT")
                .quantity(amount)
                .timestamp(timestamp)
                .rawData(toRawData(raw))
                .build();
    }

    private NormalizedTransaction buildFeeTx(UUID userId, UUID connectionId,
                                              String asset, BigDecimal fee,
                                              Instant timestamp, String hora, Map<String, String> raw) {
        return NormalizedTransaction.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .connectionId(connectionId)
                .exchangeId("binance")
                .externalId(buildExtId("bn-fee-", hora, asset, fee.toPlainString()))
                .type(TransactionType.FEE)
                .baseAsset(asset)
                .quoteAsset("USDT")
                .fee(fee)
                .feeAsset(asset)
                .timestamp(timestamp)
                .rawData(toRawData(raw))
                .build();
    }

    private NormalizedTransaction buildFuturesPnlTx(UUID userId, UUID connectionId,
                                                     String cuenta, String asset, BigDecimal pnl,
                                                     Instant timestamp, String hora, Map<String, String> raw) {
        // Coin-M: FUTURES_SHORT (contratos inversos); USD-M y otros: FUTURES_LONG
        boolean isCoinM = cuenta.equalsIgnoreCase("Coin-M Futures");
        TransactionType type = isCoinM ? TransactionType.FUTURES_SHORT : TransactionType.FUTURES_LONG;

        return NormalizedTransaction.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .connectionId(connectionId)
                .exchangeId("binance")
                .externalId(buildExtId("bn-futures-pnl-", hora, asset, pnl.toPlainString()))
                .type(type)
                .baseAsset(asset)
                .quoteAsset(isCoinM ? "USD" : "USDT")
                .realizedPnl(pnl)
                .timestamp(timestamp)
                .rawData(toRawData(raw))
                .build();
    }

    private NormalizedTransaction buildTransferTx(UUID userId, UUID connectionId, TransactionType type,
                                                   String asset, BigDecimal amount,
                                                   Instant timestamp, String hora, Map<String, String> raw) {
        return NormalizedTransaction.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .connectionId(connectionId)
                .exchangeId("binance")
                .externalId(buildExtId("bn-transfer-", hora, asset, amount.toPlainString()))
                .type(type)
                .baseAsset(asset)
                .quoteAsset("USDT")
                .quantity(amount)
                .timestamp(timestamp)
                .rawData(toRawData(raw))
                .build();
    }

    // ── USD values ───────────────────────────────────────────────────────────

    private void computeUsdValues(NormalizedTransaction tx) {
        String settlementAsset = tx.getFeeAsset() != null ? tx.getFeeAsset()
                : tx.getQuoteAsset() != null ? tx.getQuoteAsset() : tx.getBaseAsset();

        if (tx.getRealizedPnl() != null) {
            tx.setRealizedPnlUsd(priceService.toUsd(tx.getRealizedPnl(), settlementAsset, tx.getTimestamp()));
        }
        if (tx.getFee() != null) {
            tx.setFeeUsd(priceService.toUsd(tx.getFee(), settlementAsset, tx.getTimestamp()));
        }
    }

    // ── Lectura del Excel ─────────────────────────────────────────────────────

    /**
     * Detecta automáticamente la fila de cabecera buscando las columnas
     * "ID de usuario" y "Hora", luego lee todas las filas de datos restantes.
     */
    private List<Map<String, String>> readDataRows(Sheet sheet) {
        List<Map<String, String>> result = new ArrayList<>();
        List<String> headers = null;

        for (Row row : sheet) {
            List<String> cells = readRow(row);
            if (cells.stream().allMatch(String::isBlank)) continue;

            if (headers == null) {
                boolean isHeader = cells.stream().anyMatch(c -> c.equalsIgnoreCase("ID de usuario"))
                        && cells.stream().anyMatch(c -> c.equalsIgnoreCase("Hora"));
                if (isHeader) {
                    headers = cells.stream().map(String::trim).toList();
                }
                continue;
            }

            Map<String, String> map = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                String h = headers.get(i);
                String v = i < cells.size() ? cells.get(i).trim() : "";
                if (!h.isBlank()) map.put(h, v);
            }
            result.add(map);
        }

        if (headers == null) {
            log.warn("Binance Excel: no se encontró fila de cabecera");
        }
        return result;
    }

    private List<String> readRow(Row row) {
        List<String> cells = new ArrayList<>();
        if (row == null) return cells;
        for (int i = 0; i < row.getLastCellNum(); i++) {
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
                    LocalDateTime ldt = cell.getLocalDateTimeCellValue();
                    yield ldt != null ? ldt.format(CELL_TS_FORMATTER) : "";
                }
                BigDecimal bd = new BigDecimal(cell.getNumericCellValue()).stripTrailingZeros();
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Parsea el timestamp de Binance.
     * Si viene en formato "yy-MM-dd HH:mm:ss" (texto, 2 dígitos de año) → UTC-3.
     * Si el archivo exportó la celda como fecha y POI la leyó con 4 dígitos → también UTC-3.
     */
    private Instant parseTimestamp(String timeStr) {
        String s = timeStr.trim();
        LocalDateTime ldt;
        if (s.matches("\\d{2}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")) {
            ldt = LocalDateTime.parse(s, TS_FORMATTER);
        } else {
            // Celda leída por POI como fecha con año de 4 dígitos
            ldt = LocalDateTime.parse(s, CELL_TS_FORMATTER);
        }
        return ldt.toInstant(EXPORT_OFFSET);
    }

    private boolean isStablecoin(String asset) {
        return asset != null && STABLECOINS.contains(asset.toUpperCase());
    }

    private String buildExtId(String prefix, String hora, String asset, String amount) {
        String clean = hora.replaceAll("[^0-9]", "") + "-" + asset
                + "-" + amount.replace(".", "").replace("-", "");
        String id = prefix + clean;
        return id.length() > 255 ? id.substring(0, 255) : id;
    }

    private BigDecimal parseBigDecimal(String value) {
        if (value == null || value.isBlank()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(value.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            log.warn("Binance: no se pudo parsear número: '{}'", value);
            return BigDecimal.ZERO;
        }
    }

    private String getCell(Map<String, String> row, String key) {
        String val = row.get(key);
        return val != null ? val : "";
    }

    private Map<String, Object> toRawData(Map<String, String> row) {
        Map<String, Object> raw = new HashMap<>();
        raw.putAll(row);
        return raw;
    }
}
