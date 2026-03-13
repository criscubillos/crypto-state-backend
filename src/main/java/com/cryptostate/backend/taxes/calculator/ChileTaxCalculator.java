package com.cryptostate.backend.taxes.calculator;

import com.cryptostate.backend.transaction.model.NormalizedTransaction;
import com.cryptostate.backend.transaction.model.TransactionType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Calculadora de impuestos para Chile — Formulario 22 SII.
 *
 * Marco legal (Circular SII N°43/2021):
 *  - Criptomonedas: bienes inmateriales, no divisas.
 *  - Art. 17 N°8 m) LIR — personas naturales → solo IGC, Código 1032.
 *  - Mayor valor = precio venta CLP − costo tributario actualizado por IPC.
 *  - Comisiones: NO deducibles para personas naturales.
 *  - Crypto-to-crypto = evento tributario (permuta).
 *
 * Tratamiento por tipo:
 *  - FUTURES_LONG/SHORT → realized_pnl_usd es el mayor valor neto.
 *  - SPOT_BUY/SELL      → usa realized_pnl_usd si disponible.
 *  - EARN/STAKING       → renta ordinaria afecta.
 *  - FUNDING/COMMISSION/DEPOSIT/WITHDRAWAL → excluidos.
 *
 * USD→CLP: dólar observado promedio anual (Banco Central de Chile).
 * Ajuste IPC: indicado al usuario pero no calculado automáticamente.
 */
@Slf4j
@Component
public class ChileTaxCalculator implements TaxCalculator {

    private record TramoIgcDef(long limSup, int tasa, long rebaja, String nombre) {}

    private static final Map<Integer, List<TramoIgcDef>> IGC_TABLAS;
    static {
        IGC_TABLAS = new HashMap<>();
        // AT2024 — UTA dic 2023 = $765.828
        IGC_TABLAS.put(2024, List.of(
            new TramoIgcDef(  9_913_560L,  0,          0L, "Exento (0%)"),
            new TramoIgcDef( 22_030_060L,  4,    396_542L, "Tramo 4%"),
            new TramoIgcDef( 36_716_760L,  8,  1_284_644L, "Tramo 8%"),
            new TramoIgcDef( 51_403_480L, 14,  3_487_044L, "Tramo 13,5%"),
            new TramoIgcDef( 66_090_160L, 23,  8_097_526L, "Tramo 23%"),
            new TramoIgcDef( 88_120_240L, 30, 12_722_538L, "Tramo 30,4%"),
            new TramoIgcDef(227_446_200L, 35, 17_128_550L, "Tramo 35%"),
            new TramoIgcDef(Long.MAX_VALUE, 40, 28_500_660L, "Tramo máximo 40%")
        ));
        // AT2025 — UTA dic 2024 = $807.528
        IGC_TABLAS.put(2025, List.of(
            new TramoIgcDef( 10_402_992L,  0,          0L, "Exento (0%)"),
            new TramoIgcDef( 23_117_760L,  4,    416_120L, "Tramo 4%"),
            new TramoIgcDef( 38_529_600L,  8,  1_340_830L, "Tramo 8%"),
            new TramoIgcDef( 53_941_440L, 14,  3_647_498L, "Tramo 13,5%"),
            new TramoIgcDef( 69_353_280L, 23,  8_584_395L, "Tramo 23%"),
            new TramoIgcDef( 92_471_040L, 30, 13_716_538L, "Tramo 30,4%"),
            new TramoIgcDef(238_883_520L, 35, 17_970_205L, "Tramo 35%"),
            new TramoIgcDef(Long.MAX_VALUE, 40, 29_914_381L, "Tramo máximo 40%")
        ));
        // AT2026 — UTA dic 2025 = $834.504
        IGC_TABLAS.put(2026, List.of(
            new TramoIgcDef( 10_901_628L,  0,          0L, "Exento (0%)"),
            new TramoIgcDef( 24_225_840L,  4,    436_065L, "Tramo 4%"),
            new TramoIgcDef( 40_376_400L,  8,  1_405_099L, "Tramo 8%"),
            new TramoIgcDef( 56_526_960L, 14,  3_831_925L, "Tramo 13,5%"),
            new TramoIgcDef( 72_677_520L, 23,  8_995_862L, "Tramo 23%"),
            new TramoIgcDef( 96_903_360L, 30, 14_373_998L, "Tramo 30,4%"),
            new TramoIgcDef(250_333_680L, 35, 18_831_553L, "Tramo 35%"),
            new TramoIgcDef(Long.MAX_VALUE, 40, 31_348_237L, "Tramo máximo 40%")
        ));
    }

    private static final Map<Integer, BigDecimal> UTA_POR_ANIO = Map.of(
        2023, new BigDecimal("765828"),
        2024, new BigDecimal("807528"),
        2025, new BigDecimal("834504")
    );

    private static final Map<Integer, BigDecimal> USD_CLP_PROMEDIO = Map.of(
        2021, new BigDecimal("759.4"),
        2022, new BigDecimal("872.9"),
        2023, new BigDecimal("839.0"),
        2024, new BigDecimal("950.0"),
        2025, new BigDecimal("958.0")
    );

    private static final String[] MESES = {
        "Enero","Febrero","Marzo","Abril","Mayo","Junio",
        "Julio","Agosto","Septiembre","Octubre","Noviembre","Diciembre"
    };

    private static final Set<TransactionType> TIPOS_EXCLUIDOS = Set.of(
        TransactionType.FEE,
        TransactionType.TRANSFER_IN,
        TransactionType.TRANSFER_OUT
    );

    // ── TaxCalculator interface ───────────────────────────────────────────────

    @Override
    public String getCountryCode() { return "CL"; }

    @Override
    public TaxResult calculate(List<NormalizedTransaction> transactions, int year) {
        ChileTaxReport r = calculateDetailed(transactions, year);
        return new TaxResult("CL", year,
            r.gananciasBrutasUsd(), r.perdidasBrutasUsd(),
            r.gananciaNetaUsd(), r.gananciaNetaClp(), "CLP",
            List.of(), Map.of("codigo_1032", r.codigo1032()));
    }

    // ── Cálculo principal ─────────────────────────────────────────────────────

    public ChileTaxReport calculateDetailed(List<NormalizedTransaction> transactions, int year) {
        log.info("Chile impuestos AT{} (comercial {}): {} txs", year + 1, year, transactions.size());

        int anioComercial  = year;
        int anioTributario = year + 1;

        BigDecimal tc  = USD_CLP_PROMEDIO.getOrDefault(anioComercial, new BigDecimal("950"));
        BigDecimal uta = UTA_POR_ANIO.getOrDefault(anioComercial, new BigDecimal("834504"));
        String fuenteTc = "Dólar observado promedio año " + anioComercial + " — Banco Central de Chile";

        BigDecimal ganBrutas = BigDecimal.ZERO, perBrutas = BigDecimal.ZERO, fees = BigDecimal.ZERO;
        BigDecimal ganFuturos = BigDecimal.ZERO, ganSpot = BigDecimal.ZERO;
        BigDecimal ganEarn = BigDecimal.ZERO, ganOtros = BigDecimal.ZERO;

        BigDecimal[] ganMes = init12(), perMes = init12();
        int[] opMes = new int[12];
        Map<String, BigDecimal> ganByA = new LinkedHashMap<>(), perByA = new LinkedHashMap<>();
        Map<String, Integer> opByA = new LinkedHashMap<>();

        for (NormalizedTransaction tx : transactions) {
            if (tx.getType() == TransactionType.FEE) {
                BigDecimal f = tx.getFeeUsd() != null ? tx.getFeeUsd().abs()
                    : (tx.getFee() != null ? tx.getFee().abs() : BigDecimal.ZERO);
                fees = fees.add(f);
                continue;
            }
            if (TIPOS_EXCLUIDOS.contains(tx.getType())) continue;

            BigDecimal pnl = resolverPnlUsd(tx);
            if (pnl == null || pnl.compareTo(BigDecimal.ZERO) == 0) continue;

            if (tx.getFeeUsd() != null) fees = fees.add(tx.getFeeUsd().abs());

            int mes = tx.getTimestamp().atZone(ZoneOffset.UTC).getMonthValue() - 1;
            String act = tx.getBaseAsset() != null ? tx.getBaseAsset() : "?";
            opMes[mes]++;
            opByA.merge(act, 1, Integer::sum);

            switch (tx.getType()) {
                case FUTURES_LONG, FUTURES_SHORT -> ganFuturos = ganFuturos.add(pnl);
                case SPOT_BUY, SPOT_SELL         -> ganSpot    = ganSpot.add(pnl);
                case EARN               -> ganEarn    = ganEarn.add(pnl);
                default                          -> ganOtros   = ganOtros.add(pnl);
            }

            if (pnl.compareTo(BigDecimal.ZERO) > 0) {
                ganBrutas = ganBrutas.add(pnl);
                ganMes[mes] = ganMes[mes].add(pnl);
                ganByA.merge(act, pnl, BigDecimal::add);
            } else {
                perBrutas = perBrutas.add(pnl.abs());
                perMes[mes] = perMes[mes].add(pnl.abs());
                perByA.merge(act, pnl.abs(), BigDecimal::add);
            }
        }

        BigDecimal netaUsd = ganBrutas.subtract(perBrutas);
        BigDecimal ganBrutasClp = clp(ganBrutas, tc);
        BigDecimal perBrutasClp = clp(perBrutas, tc);
        BigDecimal netaClp      = clp(netaUsd, tc);
        BigDecimal feesClp      = clp(fees, tc);
        BigDecimal baseClp      = netaClp.max(BigDecimal.ZERO);

        List<TramoIgcDef> tabla = IGC_TABLAS.getOrDefault(anioTributario, IGC_TABLAS.get(2026));
        ChileTaxReport.TramosIgc tramo = calcularTramo(baseClp, tabla);
        BigDecimal impuesto            = calcularIgc(baseClp, tabla);
        BigDecimal tasaEfectiva = BigDecimal.ZERO;
        if (baseClp.compareTo(BigDecimal.ZERO) > 0) {
            tasaEfectiva = impuesto.multiply(new BigDecimal("100"))
                .divide(baseClp, 2, RoundingMode.HALF_UP);
        }

        // Breakdown mensual
        List<ChileTaxReport.BreakdownMensual> bMensual = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            if (opMes[i] == 0) continue;
            BigDecimal neta = ganMes[i].subtract(perMes[i]);
            bMensual.add(new ChileTaxReport.BreakdownMensual(
                i + 1, MESES[i],
                r2(ganMes[i]), r2(perMes[i]), r2(neta), clp(neta, tc), opMes[i]
            ));
        }

        // Breakdown activo
        Set<String> allA = new LinkedHashSet<>();
        allA.addAll(ganByA.keySet()); allA.addAll(perByA.keySet());
        List<ChileTaxReport.BreakdownActivo> bActivo = allA.stream()
            .map(a -> {
                BigDecimal g = ganByA.getOrDefault(a, BigDecimal.ZERO);
                BigDecimal p = perByA.getOrDefault(a, BigDecimal.ZERO);
                return new ChileTaxReport.BreakdownActivo(a, r2(g), r2(p), r2(g.subtract(p)), opByA.getOrDefault(a, 0));
            })
            .sorted(Comparator.comparing(ChileTaxReport.BreakdownActivo::netaUsd).reversed())
            .collect(Collectors.toList());

        return new ChileTaxReport(
            anioTributario, anioComercial,
            "FIFO (spot) / PnL directo (futuros)",
            tc, fuenteTc,
            r2(ganBrutas), r2(perBrutas), r2(netaUsd), r2(fees),
            ganBrutasClp, perBrutasClp, netaClp, feesClp,
            false,
            "El costo tributario debe ajustarse por variación del IPC entre compra y venta " +
            "(Art. 17 N°8 m) LIR). Consulta con tu contador para el cálculo definitivo.",
            ganBrutasClp, netaClp, baseClp, baseClp,
            tramo, impuesto, tasaEfectiva, uta, tramo.descripcion(),
            r2(ganFuturos), r2(ganSpot), r2(ganEarn), r2(ganOtros),
            bMensual, bActivo,
            "Art. 17 N°8 letra m) LIR (personas naturales) / Art. 20 N°5 LIR (personas jurídicas). Circular SII N°43/2021.",
            "ADVERTENCIA: Valores estimados con fines informativos. No constituye asesoría tributaria. " +
            "USD→CLP al dólar observado promedio anual " + anioComercial + " (Banco Central). " +
            "Se omite ajuste IPC del costo. Valida siempre con contador antes de declarar al SII.",
            netaClp.compareTo(BigDecimal.ZERO) <= 0
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BigDecimal resolverPnlUsd(NormalizedTransaction tx) {
        if (tx.getRealizedPnlUsd() != null) return tx.getRealizedPnlUsd();
        if (tx.getRealizedPnl() == null) return null;
        String q = tx.getQuoteAsset();
        if ("USDT".equals(q) || "USDC".equals(q) || "USD".equals(q) || "BUSD".equals(q))
            return tx.getRealizedPnl();
        return null;
    }

    private BigDecimal clp(BigDecimal usd, BigDecimal tc) {
        if (usd == null) return BigDecimal.ZERO;
        return usd.multiply(tc).setScale(0, RoundingMode.HALF_UP);
    }

    private BigDecimal r2(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal[] init12() {
        BigDecimal[] a = new BigDecimal[12];
        Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }

    private BigDecimal calcularIgc(BigDecimal baseClp, List<TramoIgcDef> tabla) {
        if (baseClp.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        long base = baseClp.longValue();
        for (TramoIgcDef t : tabla) {
            if (base <= t.limSup()) {
                if (t.tasa() == 0) return BigDecimal.ZERO;
                long imp = Math.round(base * (t.tasa() / 100.0)) - t.rebaja();
                return BigDecimal.valueOf(Math.max(imp, 0));
            }
        }
        return BigDecimal.ZERO;
    }

    private ChileTaxReport.TramosIgc calcularTramo(BigDecimal baseClp, List<TramoIgcDef> tabla) {
        if (baseClp.compareTo(BigDecimal.ZERO) <= 0)
            return new ChileTaxReport.TramosIgc("Sin impuesto (ganancia neta ≤ 0)",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        long base = baseClp.longValue(), limAnt = 0;
        for (TramoIgcDef t : tabla) {
            if (base <= t.limSup()) {
                return new ChileTaxReport.TramosIgc(
                    t.nombre(),
                    BigDecimal.valueOf(limAnt),
                    t.limSup() == Long.MAX_VALUE ? null : BigDecimal.valueOf(t.limSup()),
                    new BigDecimal(t.tasa()),
                    BigDecimal.valueOf(t.rebaja())
                );
            }
            limAnt = t.limSup();
        }
        TramoIgcDef last = tabla.get(tabla.size() - 1);
        return new ChileTaxReport.TramosIgc(last.nombre(),
            BigDecimal.valueOf(limAnt), null,
            new BigDecimal(last.tasa()), BigDecimal.valueOf(last.rebaja()));
    }
}
