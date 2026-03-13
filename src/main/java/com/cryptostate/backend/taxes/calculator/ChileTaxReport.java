package com.cryptostate.backend.taxes.calculator;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Resultado del cálculo tributario para Chile.
 *
 * Marco legal:
 *  - Circular SII N°43/2021
 *  - Art. 17 N°8 letra m) LIR — personas naturales → Impuesto Global Complementario
 *  - Art. 20 N°5 LIR         — personas jurídicas  → Primera Categoría
 *
 * Formulario 22:
 *  - Código 1032: "Otras rentas de fuente chilena afectas al IGC" → mayor valor neto positivo
 *  - La ganancia neta positiva (mayor valor) se suma a la base imponible del IGC/IA.
 *  - Las comisiones NO son deducibles para personas naturales (solo ajuste de costo por IPC).
 *
 * NOTA: El sistema calcula sobre datos disponibles (PnL realizados, conversión USD→CLP
 * al promedio anual del dólar observado). Para declaración definitiva, validar con
 * contador o tributarista.
 */
public record ChileTaxReport(

    // ── Identificación ─────────────────────────────────────────────────────────
    int anioTributario,            // ej: 2026 (para rentas del año comercial 2025)
    int anioComercial,             // ej: 2025
    String metodoCosto,            // "FIFO" para spot, "PnL directo" para futuros

    // ── Tipo de cambio ─────────────────────────────────────────────────────────
    BigDecimal tipoCambioUsdClp,   // Dólar observado promedio del año comercial
    String fuenteTipoCambio,       // Descripción de la fuente

    // ── Totales en USD (moneda del exchange) ────────────────────────────────────
    BigDecimal gananciasBrutasUsd,
    BigDecimal perdidasBrutasUsd,
    BigDecimal gananciaNetaUsd,
    BigDecimal totalComisionesUsd,

    // ── Conversión a CLP ────────────────────────────────────────────────────────
    BigDecimal gananciasBrutasClp,
    BigDecimal perdidasBrutasClp,
    BigDecimal gananciaNetaClp,
    BigDecimal totalComisionesClp,

    // ── Ajuste por IPC ─────────────────────────────────────────────────────────
    boolean ajusteIpcAplicado,     // false → usuario debe verificar ajuste por IPC
    String notaIpc,

    // ── Base imponible y F22 ────────────────────────────────────────────────────
    BigDecimal mayorValorBrutoClp,            // gananciasBrutasClp
    BigDecimal mayorValorNetoClp,             // gananciaNetaClp (positivo → declarar; negativo → sin impuesto)
    BigDecimal baseImponibleIgcClp,           // mayorValorNetoClp si > 0, else 0
    BigDecimal codigo1032,                    // F22 Línea Global Complementario: mayor valor neto (si positivo)

    // ── Cálculo IGC estimado ────────────────────────────────────────────────────
    TramosIgc tramosIgc,
    BigDecimal impuestoIgcEstimadoClp,
    BigDecimal tasaEfectivaIgcPct,

    // ── Tramo UTA ──────────────────────────────────────────────────────────────
    BigDecimal utaAnio,            // UTA diciembre del año
    String tramoNombre,            // nombre descriptivo del tramo IGC

    // ── Breakdown por tipo ─────────────────────────────────────────────────────
    BigDecimal pnlFuturosUsd,
    BigDecimal pnlSpotUsd,
    BigDecimal pnlEarnUsd,
    BigDecimal pnlOtrosUsd,

    // ── Breakdown mensual ─────────────────────────────────────────────────────
    List<BreakdownMensual> breakdownMensual,

    // ── Breakdown por activo ──────────────────────────────────────────────────
    List<BreakdownActivo> breakdownActivo,

    // ── Metadatos ─────────────────────────────────────────────────────────────
    String articuloLeyAplicable,
    String disclaimer,
    boolean tieneNegativo          // true si la ganancia neta es negativa (no hay impuesto)

) {
    public record TramosIgc(
        String descripcion,
        BigDecimal limiteInferiorClp,
        BigDecimal limiteSuperiorClp,
        BigDecimal tasaPct,
        BigDecimal cantidadRebajar
    ) {}

    public record BreakdownMensual(
        int mes,
        String mesNombre,
        BigDecimal gananciaUsd,
        BigDecimal perdidaUsd,
        BigDecimal netaUsd,
        BigDecimal netaClp,
        int cantidadOperaciones
    ) {}

    public record BreakdownActivo(
        String activo,
        BigDecimal gananciaUsd,
        BigDecimal perdidaUsd,
        BigDecimal netaUsd,
        int operaciones
    ) {}
}
