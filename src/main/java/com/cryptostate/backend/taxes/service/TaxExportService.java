package com.cryptostate.backend.taxes.service;

import com.cryptostate.backend.taxes.calculator.ChileTaxReport;
import com.cryptostate.backend.transaction.model.NormalizedTransaction;
import com.cryptostate.backend.transaction.model.TransactionType;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * Genera el Excel de detalle tributario para el módulo de impuestos.
 *
 * Hojas:
 *  1. Resumen     → valores clave del ChileTaxReport + F22
 *  2. Detalle     → una fila por transacción con fecha, tipo, par, PnL USD, PnL CLP, ganancia/pérdida
 *  3. Por Mes     → breakdownMensual
 *  4. Por Activo  → breakdownActivo
 */
@Slf4j
@Service
public class TaxExportService {

    private static final ZoneId TZ_CL = ZoneId.of("America/Santiago");
    private static final DateTimeFormatter FMT_FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final Set<TransactionType> TIPOS_EXCLUIDOS = Set.of(
        TransactionType.FEE, TransactionType.TRANSFER_IN, TransactionType.TRANSFER_OUT
    );

    // ── Colores corporativos ───────────────────────────────────────────────────
    private static final byte[] COLOR_HEADER   = hex("1E293B"); // slate-800
    private static final byte[] COLOR_SUBHEAD  = hex("334155"); // slate-700
    private static final byte[] COLOR_GANANCIA = hex("065F46"); // emerald-800
    private static final byte[] COLOR_PERDIDA  = hex("7F1D1D"); // red-900
    private static final byte[] COLOR_NEUTRAL  = hex("1E3A5F"); // blue-900
    private static final byte[] COLOR_TEXT_LIGHT = hex("F8FAFC");
    private static final byte[] COLOR_GANANCIA_FG = hex("D1FAE5");
    private static final byte[] COLOR_PERDIDA_FG  = hex("FEE2E2");
    private static final byte[] COLOR_ALT_ROW     = hex("F1F5F9");
    private static final byte[] COLOR_HIGHLIGHT   = hex("FEF3C7");

    // ── API pública ────────────────────────────────────────────────────────────

    public byte[] generarExcel(List<NormalizedTransaction> transactions,
                               ChileTaxReport report) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles s = new Styles(wb);

            crearHojaResumen(wb, s, report);
            crearHojaDetalle(wb, s, transactions, report.tipoCambioUsdClp(), report.anioComercial());
            crearHojaMensual(wb, s, report);
            crearHojaActivo(wb, s, report);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Error generando Excel tributario", e);
            throw new RuntimeException("No se pudo generar el archivo Excel", e);
        }
    }

    // ── Hoja 1: Resumen ────────────────────────────────────────────────────────

    private void crearHojaResumen(XSSFWorkbook wb, Styles s, ChileTaxReport r) {
        XSSFSheet sheet = wb.createSheet("Resumen");
        sheet.setColumnWidth(0, 45 * 256);
        sheet.setColumnWidth(1, 22 * 256);
        sheet.setColumnWidth(2, 22 * 256);

        int row = 0;

        // Título principal
        row = titulo(sheet, s, row, "RESUMEN TRIBUTARIO — AÑO COMERCIAL " + r.anioComercial()
            + " / AÑO TRIBUTARIO " + r.anioTributario(), 3);
        row = subtitulo(sheet, s, row, "Chile · Formulario 22 SII · " + r.articuloLeyAplicable(), 3);
        row++;

        // Tipo de cambio
        row = seccion(sheet, s, row, "TIPO DE CAMBIO UTILIZADO", 3);
        row = fila2col(sheet, s, row, "USD/CLP promedio anual", "$" + r.tipoCambioUsdClp().setScale(0, RoundingMode.HALF_UP), false, s.normal, s.moneda);
        row = fila2col(sheet, s, row, "Fuente", r.fuenteTipoCambio(), false, s.normal, s.normal);
        row++;

        // Totales USD y CLP
        row = seccion(sheet, s, row, "TOTALES", 3);

        XSSFRow h = sheet.createRow(row++);
        celda(h, 0, "Concepto", s.subheader);
        celda(h, 1, "USD", s.subheader);
        celda(h, 2, "CLP", s.subheader);

        row = fila3col(sheet, s, row, "Ganancias brutas",
            r.gananciasBrutasUsd(), r.gananciasBrutasClp(), true, s.ganancia, s.ganancia, s.gananciaClp);
        row = fila3col(sheet, s, row, "Pérdidas brutas",
            r.perdidasBrutasUsd().negate(), r.perdidasBrutasClp().negate(), false, s.perdida, s.perdida, s.perdidaClp);
        row = fila3col(sheet, s, row, "Mayor valor neto (ganancia neta)",
            r.gananciaNetaUsd(), r.gananciaNetaClp(), null, s.normal, s.usd, s.clp);
        row = fila3col(sheet, s, row, "Comisiones (referencial, no deducible)",
            r.totalComisionesUsd().negate(), r.totalComisionesClp().negate(), false, s.normal, s.usd, s.clp);
        row++;

        // Por tipo de operación
        row = seccion(sheet, s, row, "PNL NETO POR TIPO DE OPERACIÓN (USD)", 3);
        row = fila2col(sheet, s, row, "Futuros (FUTURES_LONG/SHORT)", r.pnlFuturosUsd(), true, s.normal, s.usd);
        row = fila2col(sheet, s, row, "Spot (SPOT_BUY/SELL)", r.pnlSpotUsd(), true, s.normal, s.usd);
        row = fila2col(sheet, s, row, "Earn / Staking", r.pnlEarnUsd(), true, s.normal, s.usd);
        row = fila2col(sheet, s, row, "Otros", r.pnlOtrosUsd(), true, s.normal, s.usd);
        row++;

        // F22
        row = seccion(sheet, s, row, "FORMULARIO 22 — VALORES A DECLARAR (CLP)", 3);
        row = fila2col(sheet, s, row, "Mayor valor bruto (suma de ganancias)", r.mayorValorBrutoClp(), null, s.normal, s.clp);
        row = fila2col(sheet, s, row, "Pérdidas del mismo año (a restar)", r.perdidasBrutasClp().negate(), null, s.normal, s.clp);

        XSSFRow f22row = sheet.createRow(row++);
        celda(f22row, 0, "CÓDIGO 1032 — Base imponible IGC / IA", s.highlight);
        celdaNumero(f22row, 1, r.codigo1032().doubleValue(), s.highlightNum);
        celdaNumero(f22row, 2, r.codigo1032().doubleValue(), s.highlightNum);
        row++;

        // IGC estimado
        row = seccion(sheet, s, row, "IMPUESTO GLOBAL COMPLEMENTARIO ESTIMADO", 3);
        row = fila2col(sheet, s, row, "Base imponible IGC (CLP)", r.baseImponibleIgcClp(), null, s.normal, s.clp);
        row = fila2col(sheet, s, row, "IGC estimado (CLP)", r.impuestoIgcEstimadoClp(), null, s.normal, s.clp);
        row = fila2col(sheet, s, row, "Tasa efectiva", r.tasaEfectivaIgcPct().toString() + "%", false, s.normal, s.normal);
        row = fila2col(sheet, s, row, "Tramo IGC", r.tramoNombre(), false, s.normal, s.normal);
        row = fila2col(sheet, s, row, "UTA año " + r.anioComercial(), r.utaAnio(), null, s.normal, s.clp);
        row++;

        // Disclaimer
        row = seccion(sheet, s, row, "ADVERTENCIA", 3);
        XSSFRow disc = sheet.createRow(row++);
        XSSFCell discCell = disc.createCell(0);
        discCell.setCellValue(r.disclaimer());
        discCell.setCellStyle(s.disclaimer);
        sheet.addMergedRegion(new CellRangeAddress(row - 1, row - 1, 0, 2));
        disc.setHeight((short) 2400);
    }

    // ── Hoja 2: Detalle de transacciones ──────────────────────────────────────

    private void crearHojaDetalle(XSSFWorkbook wb, Styles s,
                                   List<NormalizedTransaction> txs,
                                   BigDecimal tc, int anioComercial) {
        XSSFSheet sheet = wb.createSheet("Detalle");

        // Ancho de columnas
        int[] widths = {18, 18, 15, 10, 10, 15, 17, 12, 14};
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);

        // Título
        int row = 0;
        row = titulo(sheet, s, row, "DETALLE DE OPERACIONES — AÑO COMERCIAL " + anioComercial, 9);
        row++;

        // Cabecera
        String[] headers = {
            "Fecha (Hora CL)", "Fecha UTC", "Tipo", "Activo", "Quote",
            "PnL USD", "PnL CLP", "Resultado", "Fuente PnL"
        };
        XSSFRow hrow = sheet.createRow(row++);
        for (int i = 0; i < headers.length; i++) celda(hrow, i, headers[i], s.subheader);

        // Filas de datos
        boolean alt = false;
        for (NormalizedTransaction tx : txs) {
            if (TIPOS_EXCLUIDOS.contains(tx.getType())) continue;

            BigDecimal pnl = resolverPnlUsd(tx);
            if (pnl == null || pnl.compareTo(BigDecimal.ZERO) == 0) continue;

            boolean esGanancia = pnl.compareTo(BigDecimal.ZERO) > 0;
            BigDecimal pnlClp = pnl.multiply(tc).setScale(0, RoundingMode.HALF_UP);

            XSSFCellStyle rowStyle    = alt ? s.altRow : s.normalRow;
            XSSFCellStyle numStyle    = esGanancia ? s.gananciaNum : s.perdidaNum;
            XSSFCellStyle resultStyle = esGanancia ? s.gananciaTag : s.perdidaTag;

            XSSFRow drow = sheet.createRow(row++);

            // Fecha CL
            String fechaCl = tx.getTimestamp().atZone(TZ_CL).format(FMT_FECHA);
            String fechaUtc = tx.getTimestamp().atZone(ZoneId.of("UTC")).format(FMT_FECHA);
            celda(drow, 0, fechaCl, rowStyle);
            celda(drow, 1, fechaUtc, rowStyle);
            celda(drow, 2, tipoLabel(tx.getType()), rowStyle);
            celda(drow, 3, tx.getBaseAsset() != null ? tx.getBaseAsset() : "-", rowStyle);
            celda(drow, 4, tx.getQuoteAsset() != null ? tx.getQuoteAsset() : "-", rowStyle);
            celdaNumero(drow, 5, pnl.doubleValue(), numStyle);
            celdaNumero(drow, 6, pnlClp.doubleValue(), numStyle);
            celda(drow, 7, esGanancia ? "GANANCIA" : "PÉRDIDA", resultStyle);
            celda(drow, 8, fuentePnl(tx), rowStyle);

            alt = !alt;
        }

        // Auto-filter
        sheet.setAutoFilter(new CellRangeAddress(1, 1, 0, headers.length - 1));
        sheet.createFreezePane(0, 2);
    }

    // ── Hoja 3: Desglose Mensual ───────────────────────────────────────────────

    private void crearHojaMensual(XSSFWorkbook wb, Styles s, ChileTaxReport r) {
        XSSFSheet sheet = wb.createSheet("Por Mes");
        int[] widths = {16, 18, 18, 16, 18, 10};
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);

        int row = 0;
        row = titulo(sheet, s, row, "DESGLOSE MENSUAL — AÑO COMERCIAL " + r.anioComercial(), 6);
        row++;

        String[] headers = {"Mes", "Ganancias USD", "Pérdidas USD", "Neto USD", "Neto CLP", "Operaciones"};
        XSSFRow hrow = sheet.createRow(row++);
        for (int i = 0; i < headers.length; i++) celda(hrow, i, headers[i], s.subheader);

        boolean alt = false;
        for (ChileTaxReport.BreakdownMensual m : r.breakdownMensual()) {
            XSSFRow mrow = sheet.createRow(row++);
            XSSFCellStyle base = alt ? s.altRow : s.normalRow;
            celda(mrow, 0, m.mesNombre(), base);
            celdaNumero(mrow, 1, m.gananciaUsd().doubleValue(), s.gananciaNum);
            celdaNumero(mrow, 2, m.perdidaUsd().negate().doubleValue(), s.perdidaNum);
            celdaNumero(mrow, 3, m.netaUsd().doubleValue(),
                m.netaUsd().compareTo(BigDecimal.ZERO) >= 0 ? s.gananciaNum : s.perdidaNum);
            celdaNumero(mrow, 4, m.netaClp().doubleValue(),
                m.netaClp().compareTo(BigDecimal.ZERO) >= 0 ? s.gananciaNum : s.perdidaNum);
            celdaNumero(mrow, 5, m.cantidadOperaciones(), base);
            alt = !alt;
        }

        // Fila totales
        XSSFRow tot = sheet.createRow(row);
        celda(tot, 0, "TOTAL", s.subheader);
        double sumGan = r.breakdownMensual().stream().mapToDouble(m -> m.gananciaUsd().doubleValue()).sum();
        double sumPer = r.breakdownMensual().stream().mapToDouble(m -> m.perdidaUsd().doubleValue()).sum();
        double sumNet = r.breakdownMensual().stream().mapToDouble(m -> m.netaUsd().doubleValue()).sum();
        double sumClp = r.breakdownMensual().stream().mapToDouble(m -> m.netaClp().doubleValue()).sum();
        int sumOps    = r.breakdownMensual().stream().mapToInt(ChileTaxReport.BreakdownMensual::cantidadOperaciones).sum();
        celdaNumero(tot, 1, sumGan, s.gananciaNum);
        celdaNumero(tot, 2, -sumPer, s.perdidaNum);
        celdaNumero(tot, 3, sumNet, sumNet >= 0 ? s.gananciaNum : s.perdidaNum);
        celdaNumero(tot, 4, sumClp, sumClp >= 0 ? s.gananciaNum : s.perdidaNum);
        celdaNumero(tot, 5, sumOps, s.subheader);

        sheet.createFreezePane(0, 2);
    }

    // ── Hoja 4: Desglose por Activo ───────────────────────────────────────────

    private void crearHojaActivo(XSSFWorkbook wb, Styles s, ChileTaxReport r) {
        XSSFSheet sheet = wb.createSheet("Por Activo");
        int[] widths = {14, 18, 18, 16, 12};
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);

        int row = 0;
        row = titulo(sheet, s, row, "DESGLOSE POR ACTIVO — AÑO COMERCIAL " + r.anioComercial(), 5);
        row++;

        String[] headers = {"Activo", "Ganancias USD", "Pérdidas USD", "Neto USD", "Operaciones"};
        XSSFRow hrow = sheet.createRow(row++);
        for (int i = 0; i < headers.length; i++) celda(hrow, i, headers[i], s.subheader);

        boolean alt = false;
        for (ChileTaxReport.BreakdownActivo a : r.breakdownActivo()) {
            XSSFRow arow = sheet.createRow(row++);
            XSSFCellStyle base = alt ? s.altRow : s.normalRow;
            celda(arow, 0, a.activo(), base);
            celdaNumero(arow, 1, a.gananciaUsd().doubleValue(), s.gananciaNum);
            celdaNumero(arow, 2, a.perdidaUsd().negate().doubleValue(), s.perdidaNum);
            celdaNumero(arow, 3, a.netaUsd().doubleValue(),
                a.netaUsd().compareTo(BigDecimal.ZERO) >= 0 ? s.gananciaNum : s.perdidaNum);
            celdaNumero(arow, 4, a.operaciones(), base);
            alt = !alt;
        }

        // Totales
        XSSFRow tot = sheet.createRow(row);
        celda(tot, 0, "TOTAL", s.subheader);
        double sumG = r.breakdownActivo().stream().mapToDouble(a -> a.gananciaUsd().doubleValue()).sum();
        double sumP = r.breakdownActivo().stream().mapToDouble(a -> a.perdidaUsd().doubleValue()).sum();
        double sumN = r.breakdownActivo().stream().mapToDouble(a -> a.netaUsd().doubleValue()).sum();
        int sumO    = r.breakdownActivo().stream().mapToInt(ChileTaxReport.BreakdownActivo::operaciones).sum();
        celdaNumero(tot, 1, sumG, s.gananciaNum);
        celdaNumero(tot, 2, -sumP, s.perdidaNum);
        celdaNumero(tot, 3, sumN, sumN >= 0 ? s.gananciaNum : s.perdidaNum);
        celdaNumero(tot, 4, sumO, s.subheader);

        sheet.createFreezePane(0, 2);
    }

    // ── Helpers de construcción ────────────────────────────────────────────────

    private int titulo(XSSFSheet sheet, Styles s, int row, String text, int cols) {
        XSSFRow r = sheet.createRow(row);
        r.setHeight((short) 700);
        XSSFCell c = r.createCell(0);
        c.setCellValue(text);
        c.setCellStyle(s.titulo);
        if (cols > 1) sheet.addMergedRegion(new CellRangeAddress(row, row, 0, cols - 1));
        return row + 1;
    }

    private int subtitulo(XSSFSheet sheet, Styles s, int row, String text, int cols) {
        XSSFRow r = sheet.createRow(row);
        r.setHeight((short) 500);
        XSSFCell c = r.createCell(0);
        c.setCellValue(text);
        c.setCellStyle(s.subtitulo);
        if (cols > 1) sheet.addMergedRegion(new CellRangeAddress(row, row, 0, cols - 1));
        return row + 1;
    }

    private int seccion(XSSFSheet sheet, Styles s, int row, String text, int cols) {
        XSSFRow r = sheet.createRow(row);
        r.setHeight((short) 450);
        XSSFCell c = r.createCell(0);
        c.setCellValue(text);
        c.setCellStyle(s.seccion);
        if (cols > 1) sheet.addMergedRegion(new CellRangeAddress(row, row, 0, cols - 1));
        return row + 1;
    }

    private int fila2col(XSSFSheet sheet, Styles s, int row, String label, Object valor,
                          Boolean positivo, XSSFCellStyle labelStyle, XSSFCellStyle valStyle) {
        XSSFRow r = sheet.createRow(row);
        celda(r, 0, label, labelStyle);
        if (valor instanceof BigDecimal bd) celdaNumero(r, 1, bd.doubleValue(), valStyle);
        else if (valor instanceof String str) celda(r, 1, str, valStyle);
        else if (valor instanceof Number n) celdaNumero(r, 1, n.doubleValue(), valStyle);
        return row + 1;
    }

    private int fila3col(XSSFSheet sheet, Styles s, int row, String label,
                          BigDecimal usd, BigDecimal clp, Boolean positivo,
                          XSSFCellStyle labelStyle, XSSFCellStyle usdStyle, XSSFCellStyle clpStyle) {
        XSSFRow r = sheet.createRow(row);
        celda(r, 0, label, labelStyle);
        celdaNumero(r, 1, usd != null ? usd.doubleValue() : 0.0, usdStyle);
        celdaNumero(r, 2, clp != null ? clp.doubleValue() : 0.0, clpStyle);
        return row + 1;
    }

    private void celda(XSSFRow row, int col, String value, XSSFCellStyle style) {
        XSSFCell c = row.createCell(col);
        c.setCellValue(value != null ? value : "");
        if (style != null) c.setCellStyle(style);
    }

    private void celdaNumero(XSSFRow row, int col, double value, XSSFCellStyle style) {
        XSSFCell c = row.createCell(col);
        c.setCellValue(value);
        if (style != null) c.setCellStyle(style);
    }

    // ── Helpers de dominio ────────────────────────────────────────────────────

    private BigDecimal resolverPnlUsd(NormalizedTransaction tx) {
        if (tx.getRealizedPnlUsd() != null) return tx.getRealizedPnlUsd();
        if (tx.getRealizedPnl() == null) return null;
        String q = tx.getQuoteAsset();
        if ("USDT".equals(q) || "USDC".equals(q) || "USD".equals(q) || "BUSD".equals(q))
            return tx.getRealizedPnl();
        return null;
    }

    private String tipoLabel(TransactionType t) {
        return switch (t) {
            case FUTURES_LONG  -> "Futuros Long";
            case FUTURES_SHORT -> "Futuros Short";
            case SPOT_BUY      -> "Spot Compra";
            case SPOT_SELL     -> "Spot Venta";
            case EARN          -> "Earn / Staking";
            case OPTIONS       -> "Opciones";
            case LIQUIDITY_POOL_ADD    -> "Liquidez (add)";
            case LIQUIDITY_POOL_REMOVE -> "Liquidez (remove)";
            default -> t.name();
        };
    }

    private String fuentePnl(NormalizedTransaction tx) {
        if (tx.getRealizedPnlUsd() != null) return "realized_pnl_usd";
        return "realized_pnl (USDT)";
    }

    // ── Estilos ────────────────────────────────────────────────────────────────

    private static byte[] hex(String hex) {
        int r = Integer.parseInt(hex.substring(0, 2), 16);
        int g = Integer.parseInt(hex.substring(2, 4), 16);
        int b = Integer.parseInt(hex.substring(4, 6), 16);
        return new byte[]{(byte) r, (byte) g, (byte) b};
    }

    private static XSSFColor xc(XSSFWorkbook wb, byte[] rgb) {
        return new XSSFColor(rgb, wb.getStylesSource().getIndexedColors());
    }

    private static class Styles {
        final XSSFCellStyle titulo, subtitulo, seccion, subheader;
        final XSSFCellStyle normal, moneda, usd, clp;
        final XSSFCellStyle ganancia, gananciaNum, gananciaClp, gananciaTag;
        final XSSFCellStyle perdida, perdidaNum, perdidaClp, perdidaTag;
        final XSSFCellStyle normalRow, altRow;
        final XSSFCellStyle highlight, highlightNum;
        final XSSFCellStyle disclaimer;

        Styles(XSSFWorkbook wb) {
            XSSFFont fntBold   = boldFont(wb, 11, COLOR_TEXT_LIGHT);
            XSSFFont fntTitle  = boldFont(wb, 13, COLOR_TEXT_LIGHT);
            XSSFFont fntNormal = normalFont(wb, 10, hex("1E293B"));
            XSSFFont fntGanBold = boldFont(wb, 10, hex("065F46"));
            XSSFFont fntPerBold = boldFont(wb, 10, hex("991B1B"));

            titulo    = base(wb); bgColor(wb, titulo, COLOR_HEADER);
            titulo.setFont(fntTitle); titulo.setAlignment(HorizontalAlignment.CENTER);
            titulo.setVerticalAlignment(VerticalAlignment.CENTER);

            subtitulo = base(wb); bgColor(wb, subtitulo, COLOR_SUBHEAD);
            subtitulo.setFont(normalFont(wb, 10, COLOR_TEXT_LIGHT));
            subtitulo.setAlignment(HorizontalAlignment.CENTER);

            seccion   = base(wb); bgColor(wb, seccion, COLOR_NEUTRAL);
            seccion.setFont(boldFont(wb, 10, COLOR_TEXT_LIGHT));

            subheader = base(wb); bgColor(wb, subheader, COLOR_SUBHEAD);
            subheader.setFont(fntBold);
            subheader.setAlignment(HorizontalAlignment.CENTER);

            normal      = base(wb); normal.setFont(fntNormal);
            moneda      = base(wb); moneda.setFont(fntNormal);
            moneda.setDataFormat(wb.createDataFormat().getFormat("#,##0"));

            usd = base(wb); usd.setFont(fntNormal);
            usd.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));

            clp = base(wb); clp.setFont(fntNormal);
            clp.setDataFormat(wb.createDataFormat().getFormat("#,##0"));

            // Ganancias
            ganancia    = base(wb); bgColor(wb, ganancia, COLOR_GANANCIA);
            ganancia.setFont(boldFont(wb, 10, COLOR_TEXT_LIGHT));

            gananciaNum = base(wb);
            gananciaNum.setFont(fntGanBold);
            gananciaNum.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));

            gananciaClp = base(wb);
            gananciaClp.setFont(fntGanBold);
            gananciaClp.setDataFormat(wb.createDataFormat().getFormat("#,##0"));

            gananciaTag = base(wb); bgColor(wb, gananciaTag, COLOR_GANANCIA);
            gananciaTag.setFont(boldFont(wb, 9, COLOR_TEXT_LIGHT));
            gananciaTag.setAlignment(HorizontalAlignment.CENTER);

            // Pérdidas
            perdida    = base(wb); bgColor(wb, perdida, COLOR_PERDIDA);
            perdida.setFont(boldFont(wb, 10, COLOR_TEXT_LIGHT));

            perdidaNum = base(wb);
            perdidaNum.setFont(fntPerBold);
            perdidaNum.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));

            perdidaClp = base(wb);
            perdidaClp.setFont(fntPerBold);
            perdidaClp.setDataFormat(wb.createDataFormat().getFormat("#,##0"));

            perdidaTag = base(wb); bgColor(wb, perdidaTag, COLOR_PERDIDA);
            perdidaTag.setFont(boldFont(wb, 9, COLOR_TEXT_LIGHT));
            perdidaTag.setAlignment(HorizontalAlignment.CENTER);

            // Filas alternadas
            normalRow = base(wb); normalRow.setFont(fntNormal);
            normalRow.setDataFormat(wb.createDataFormat().getFormat("General"));

            altRow = base(wb); bgColor(wb, altRow, COLOR_ALT_ROW);
            altRow.setFont(fntNormal);

            // Highlight (código 1032)
            highlight = base(wb); bgColor(wb, highlight, COLOR_HIGHLIGHT);
            highlight.setFont(boldFont(wb, 10, hex("92400E")));

            highlightNum = base(wb); bgColor(wb, highlightNum, COLOR_HIGHLIGHT);
            highlightNum.setFont(boldFont(wb, 10, hex("92400E")));
            highlightNum.setDataFormat(wb.createDataFormat().getFormat("#,##0"));

            // Disclaimer
            disclaimer = base(wb);
            disclaimer.setFont(normalFont(wb, 9, hex("64748B")));
            disclaimer.setWrapText(true);
            disclaimer.setVerticalAlignment(VerticalAlignment.TOP);
        }

        private XSSFCellStyle base(XSSFWorkbook wb) {
            XSSFCellStyle s = wb.createCellStyle();
            s.setBorderBottom(BorderStyle.THIN);
            s.setBorderTop(BorderStyle.THIN);
            s.setBorderLeft(BorderStyle.THIN);
            s.setBorderRight(BorderStyle.THIN);
            s.setVerticalAlignment(VerticalAlignment.CENTER);
            return s;
        }

        private void bgColor(XSSFWorkbook wb, XSSFCellStyle style, byte[] rgb) {
            style.setFillForegroundColor(xc(wb, rgb));
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }

        private XSSFFont boldFont(XSSFWorkbook wb, int size, byte[] rgb) {
            XSSFFont f = wb.createFont();
            f.setBold(true);
            f.setFontHeightInPoints((short) size);
            f.setColor(xc(wb, rgb));
            return f;
        }

        private XSSFFont normalFont(XSSFWorkbook wb, int size, byte[] rgb) {
            XSSFFont f = wb.createFont();
            f.setFontHeightInPoints((short) size);
            f.setColor(xc(wb, rgb));
            return f;
        }
    }
}
