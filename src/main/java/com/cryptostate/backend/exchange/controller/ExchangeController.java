package com.cryptostate.backend.exchange.controller;

import com.cryptostate.backend.exchange.dto.ConnectionResponse;
import com.cryptostate.backend.exchange.dto.CreateConnectionRequest;
import com.cryptostate.backend.exchange.dto.DirectSyncResult;
import com.cryptostate.backend.exchange.dto.ImportResult;
import com.cryptostate.backend.exchange.dto.UpdateConnectionRequest;
import com.cryptostate.backend.exchange.model.SyncJob;
import com.cryptostate.backend.exchange.service.ExchangeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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
                        req.apiKey(), req.apiSecret(), req.label(), req.resetSync()));
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

    @PostMapping("/connections/{id}/sync/direct")
    public ResponseEntity<DirectSyncResult> triggerDirectSync(
            Principal principal, @PathVariable String id) {
        return ResponseEntity.ok(exchangeService.triggerDirectSync(principal.getName(), id));
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

    @PostMapping("/connections/{id}/import")
    public ResponseEntity<Map<String, String>> queueImport(
            Principal principal, @PathVariable String id,
            @RequestParam("file") MultipartFile file) throws IOException {
        SyncJob job = exchangeService.queueImport(principal.getName(), id, file.getBytes());
        return ResponseEntity.accepted()
                .body(Map.of("jobId", job.getId().toString(), "status", job.getStatus().name()));
    }

    @PostMapping("/connections/{id}/import/direct")
    public ResponseEntity<ImportResult> directImport(
            Principal principal, @PathVariable String id,
            @RequestParam("file") MultipartFile file) throws IOException {
        return ResponseEntity.ok(exchangeService.directImport(principal.getName(), id, file.getBytes()));
    }
}
