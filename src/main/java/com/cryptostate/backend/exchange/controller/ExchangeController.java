package com.cryptostate.backend.exchange.controller;

import com.cryptostate.backend.exchange.dto.ConnectionResponse;
import com.cryptostate.backend.exchange.dto.CreateConnectionRequest;
import com.cryptostate.backend.exchange.dto.UpdateConnectionRequest;
import com.cryptostate.backend.exchange.model.SyncJob;
import com.cryptostate.backend.exchange.service.ExchangeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/exchanges")
@RequiredArgsConstructor
public class ExchangeController {

    private final ExchangeService exchangeService;

    @PostMapping("/connections")
    public ResponseEntity<ConnectionResponse> createConnection(
            Principal principal,
            @Valid @RequestBody CreateConnectionRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(exchangeService.createConnection(principal.getName(), req));
    }

    @GetMapping("/connections")
    public ResponseEntity<List<ConnectionResponse>> listConnections(Principal principal) {
        return ResponseEntity.ok(exchangeService.listConnections(principal.getName()));
    }

    @PatchMapping("/connections/{id}")
    public ResponseEntity<ConnectionResponse> updateConnection(
            Principal principal, @PathVariable String id,
            @RequestBody UpdateConnectionRequest req) {
        return ResponseEntity.ok(
                exchangeService.updateConnection(principal.getName(), id,
                        req.apiKey(), req.apiSecret(), req.label()));
    }

    @DeleteMapping("/connections/{id}")
    public ResponseEntity<Map<String, String>> deleteConnection(
            Principal principal, @PathVariable String id) {
        exchangeService.deleteConnection(principal.getName(), id);
        return ResponseEntity.ok(Map.of("message", "Conexión eliminada"));
    }

    @PostMapping("/connections/{id}/sync")
    public ResponseEntity<Map<String, String>> triggerSync(
            Principal principal, @PathVariable String id) {
        SyncJob job = exchangeService.triggerSync(principal.getName(), id);
        return ResponseEntity.accepted()
                .body(Map.of("jobId", job.getId().toString(), "status", job.getStatus().name()));
    }

    @GetMapping("/sync-jobs/{jobId}")
    public ResponseEntity<Map<String, String>> getSyncJob(
            Principal principal, @PathVariable String jobId) {
        SyncJob job = exchangeService.getSyncJob(principal.getName(), jobId);
        return ResponseEntity.ok(Map.of(
                "jobId", job.getId().toString(),
                "status", job.getStatus().name(),
                "exchangeId", job.getExchangeId()
        ));
    }
}
