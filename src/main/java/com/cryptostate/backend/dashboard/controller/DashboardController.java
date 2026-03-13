package com.cryptostate.backend.dashboard.controller;

import com.cryptostate.backend.common.annotation.RequiresPlan;
import com.cryptostate.backend.auth.model.Plan;
import com.cryptostate.backend.dashboard.dto.DashboardSummary;
import com.cryptostate.backend.dashboard.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/summary")
    @RequiresPlan(Plan.PRO)
    public ResponseEntity<DashboardSummary> getSummary(Principal principal) {
        return ResponseEntity.ok(dashboardService.getSummary(UUID.fromString(principal.getName())));
    }
}
