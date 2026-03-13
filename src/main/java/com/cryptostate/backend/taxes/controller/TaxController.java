package com.cryptostate.backend.taxes.controller;

import com.cryptostate.backend.auth.model.User;
import com.cryptostate.backend.auth.repository.UserRepository;
import com.cryptostate.backend.common.annotation.RequiresPlan;
import com.cryptostate.backend.common.exception.ApiException;
import com.cryptostate.backend.auth.model.Plan;
import com.cryptostate.backend.taxes.calculator.ChileTaxCalculator;
import com.cryptostate.backend.taxes.calculator.ChileTaxReport;
import com.cryptostate.backend.taxes.calculator.TaxCalculatorRegistry;
import com.cryptostate.backend.taxes.calculator.TaxResult;
import com.cryptostate.backend.taxes.service.TaxExportService;
import com.cryptostate.backend.transaction.model.NormalizedTransaction;
import com.cryptostate.backend.transaction.service.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/taxes")
@RequiredArgsConstructor
public class TaxController {

    private final TaxCalculatorRegistry taxCalculatorRegistry;
    private final ChileTaxCalculator chileTaxCalculator;
    private final TaxExportService taxExportService;
    private final TransactionService transactionService;
    private final UserRepository userRepository;

    /**
     * Cálculo del año tributario.
     * @param year año COMERCIAL (ej: 2025 para AT2026).
     */
    @GetMapping("/{year}")
    @RequiresPlan(Plan.PRO)
    public ResponseEntity<?> calculate(Principal principal, @PathVariable int year) {
        UUID userId = UUID.fromString(principal.getName());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));

        var transactions = transactionService.findForTaxYear(userId, year);

        // Chile: cálculo detallado con F22
        if ("CL".equalsIgnoreCase(user.getCountry())) {
            return ResponseEntity.ok(chileTaxCalculator.calculateDetailed(transactions, year));
        }

        // Otros países: totales informativos
        double totals = transactions.stream()
                .filter(t -> t.getRealizedPnl() != null)
                .mapToDouble(t -> t.getRealizedPnl().doubleValue())
                .sum();
        return ResponseEntity.ok(Map.of(
                "year", year,
                "country", user.getCountry(),
                "totalRealizedPnl", totals,
                "note", "Cálculo detallado disponible solo para Chile. Consulta a tu asesor tributario local."
        ));
    }

    /**
     * Exporta el detalle tributario del año comercial como archivo Excel (.xlsx).
     * @param year año COMERCIAL (ej: 2025 para AT2026).
     */
    @GetMapping("/{year}/export")
    @RequiresPlan(Plan.PRO)
    public ResponseEntity<byte[]> exportExcel(Principal principal, @PathVariable int year) {
        UUID userId = UUID.fromString(principal.getName());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));

        if (!"CL".equalsIgnoreCase(user.getCountry())) {
            throw ApiException.badRequest("La exportación Excel detallada solo está disponible para Chile.");
        }

        List<NormalizedTransaction> transactions = transactionService.findForTaxYear(userId, year);
        ChileTaxReport report = chileTaxCalculator.calculateDetailed(transactions, year);
        byte[] xlsx = taxExportService.generarExcel(transactions, report);

        String filename = "impuestos_crypto_" + year + "_AT" + (year + 1) + ".xlsx";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDisposition(
            ContentDisposition.attachment().filename(filename).build());
        headers.setContentLength(xlsx.length);

        return ResponseEntity.ok().headers(headers).body(xlsx);
    }
}
