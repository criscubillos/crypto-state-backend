package com.cryptostate.backend.transaction.controller;

import com.cryptostate.backend.auth.model.Plan;
import com.cryptostate.backend.common.annotation.RequiresPlan;
import com.cryptostate.backend.transaction.dto.TopTransactionsResponse;
import com.cryptostate.backend.transaction.dto.TransactionResponse;
import com.cryptostate.backend.transaction.dto.TransactionTotals;
import com.cryptostate.backend.transaction.model.TransactionType;
import com.cryptostate.backend.transaction.service.PnlCalculatorService;
import com.cryptostate.backend.transaction.service.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;
    private final PnlCalculatorService pnlCalculatorService;

    /** Lista paginada de transacciones con filtros opcionales. Accesible FREE y PRO. */
    @GetMapping
    public ResponseEntity<Page<TransactionResponse>> list(
            Principal principal,
            @RequestParam(required = false) String connectionId,
            @RequestParam(required = false) String exchangeId,
            @RequestParam(required = false) List<TransactionType> types,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @PageableDefault(size = 50, sort = "timestamp") Pageable pageable) {

        UUID connId = Optional.ofNullable(connectionId).map(UUID::fromString).orElse(null);
        Page<TransactionResponse> result = transactionService.findFiltered(
                UUID.fromString(principal.getName()), connId, exchangeId, types, from, to, pageable);
        return ResponseEntity.ok(result);
    }

    /** Recalcula PnL FIFO para todas las transacciones del usuario. */
    @PostMapping("/recalculate-pnl")
    public ResponseEntity<java.util.Map<String, String>> recalculatePnl(Principal principal) {
        pnlCalculatorService.recalculateForUser(UUID.fromString(principal.getName()));
        return ResponseEntity.ok(java.util.Map.of("status", "ok"));
    }

    /** Totales globales para los filtros aplicados — solo PRO. */
    @GetMapping("/totals")
    @RequiresPlan(Plan.PRO)
    public ResponseEntity<TransactionTotals> totals(
            Principal principal,
            @RequestParam(required = false) String connectionId,
            @RequestParam(required = false) String exchangeId,
            @RequestParam(required = false) List<TransactionType> types,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {

        UUID connId = Optional.ofNullable(connectionId).map(UUID::fromString).orElse(null);
        TransactionTotals totals = transactionService.getTotals(
                UUID.fromString(principal.getName()), connId, exchangeId, types, from, to);
        return ResponseEntity.ok(totals);
    }

    /** Top 10 ganancias y pérdidas — solo PRO. */
    @GetMapping("/top")
    @RequiresPlan(Plan.PRO)
    public ResponseEntity<TopTransactionsResponse> getTop(
            Principal principal,
            @RequestParam(required = false) String connectionId,
            @RequestParam(required = false) String exchangeId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) String asset) {
        UUID userId = UUID.fromString(principal.getName());
        UUID connId = Optional.ofNullable(connectionId).map(UUID::fromString).orElse(null);
        return ResponseEntity.ok(transactionService.getTopTransactions(userId, connId, exchangeId, year, month, asset));
    }
}
