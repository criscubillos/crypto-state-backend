package com.cryptostate.backend.transaction.controller;

import com.cryptostate.backend.auth.model.Plan;
import com.cryptostate.backend.common.annotation.RequiresPlan;
import com.cryptostate.backend.transaction.dto.TransactionResponse;
import com.cryptostate.backend.transaction.dto.TransactionTotals;
import com.cryptostate.backend.transaction.model.TransactionType;
import com.cryptostate.backend.transaction.service.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    /** Lista paginada de transacciones con filtros opcionales. Accesible FREE y PRO. */
    @GetMapping
    public ResponseEntity<Page<TransactionResponse>> list(
            Principal principal,
            @RequestParam(required = false) String exchangeId,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @PageableDefault(size = 50, sort = "timestamp") Pageable pageable) {

        Page<TransactionResponse> result = transactionService.findFiltered(
                UUID.fromString(principal.getName()), exchangeId, type, from, to, pageable);
        return ResponseEntity.ok(result);
    }

    /** Totales globales para los filtros aplicados — solo PRO. */
    @GetMapping("/totals")
    @RequiresPlan(Plan.PRO)
    public ResponseEntity<TransactionTotals> totals(
            Principal principal,
            @RequestParam(required = false) String exchangeId,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {

        TransactionTotals totals = transactionService.getTotals(
                UUID.fromString(principal.getName()), exchangeId, type, from, to);
        return ResponseEntity.ok(totals);
    }
}
