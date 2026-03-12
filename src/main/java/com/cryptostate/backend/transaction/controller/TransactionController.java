package com.cryptostate.backend.transaction.controller;

import com.cryptostate.backend.transaction.model.NormalizedTransaction;
import com.cryptostate.backend.transaction.model.TransactionType;
import com.cryptostate.backend.transaction.service.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
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

    /**
     * Lista de transacciones con filtros opcionales.
     * FREE: acceso permitido — el plan se aplica en el frontend para ocultar totales generales.
     * PRO: acceso completo incluyendo exportación.
     */
    @GetMapping
    public ResponseEntity<Page<NormalizedTransaction>> list(
            Principal principal,
            @RequestParam(required = false) String exchangeId,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @PageableDefault(size = 50) Pageable pageable) {

        Page<NormalizedTransaction> result = transactionService.findFiltered(
                UUID.fromString(principal.getName()), exchangeId, type, from, to, pageable);

        return ResponseEntity.ok(result);
    }
}
