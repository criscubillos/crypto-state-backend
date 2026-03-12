package com.cryptostate.backend.taxes.controller;

import com.cryptostate.backend.auth.model.User;
import com.cryptostate.backend.auth.repository.UserRepository;
import com.cryptostate.backend.common.annotation.RequiresPlan;
import com.cryptostate.backend.common.exception.ApiException;
import com.cryptostate.backend.auth.model.Plan;
import com.cryptostate.backend.taxes.calculator.TaxCalculatorRegistry;
import com.cryptostate.backend.taxes.calculator.TaxResult;
import com.cryptostate.backend.transaction.service.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/taxes")
@RequiredArgsConstructor
public class TaxController {

    private final TaxCalculatorRegistry taxCalculatorRegistry;
    private final TransactionService transactionService;
    private final UserRepository userRepository;

    @GetMapping("/{year}")
    @RequiresPlan(Plan.PRO)
    public ResponseEntity<?> calculate(Principal principal, @PathVariable int year) {
        UUID userId = UUID.fromString(principal.getName());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));

        var transactions = transactionService.findForTaxYear(userId, year);

        // Si hay calculadora específica para el país → resultado detallado
        var calc = taxCalculatorRegistry.getForCountry(user.getCountry());
        if (calc.isPresent()) {
            return ResponseEntity.ok(calc.get().calculate(transactions, year));
        }

        // Otros países: solo totales
        double totals = transactions.stream()
                .filter(t -> t.getRealizedPnl() != null)
                .mapToDouble(t -> t.getRealizedPnl().doubleValue())
                .sum();
        return ResponseEntity.ok(Map.of(
                "year", year,
                "country", user.getCountry(),
                "totalRealizedPnl", totals,
                "note", "Cálculo detallado disponible solo para Chile. " +
                        "Consulta a tu asesor tributario local."
        ));
    }
}
