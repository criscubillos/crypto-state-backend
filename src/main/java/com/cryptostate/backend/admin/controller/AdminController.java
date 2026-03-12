package com.cryptostate.backend.admin.controller;

import com.cryptostate.backend.admin.dto.AdminStats;
import com.cryptostate.backend.admin.dto.UserSummary;
import com.cryptostate.backend.admin.service.AdminService;
import com.cryptostate.backend.auth.model.Plan;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    // ── Estadísticas ─────────────────────────────────────────────────────────

    @GetMapping("/stats")
    public ResponseEntity<AdminStats> getStats() {
        return ResponseEntity.ok(adminService.getStats());
    }

    // ── Usuarios ─────────────────────────────────────────────────────────────

    @GetMapping("/users")
    public ResponseEntity<Page<UserSummary>> listUsers(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String plan,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) Boolean active,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return ResponseEntity.ok(adminService.listUsers(search, plan, country, active, pageable));
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<UserSummary> getUser(@PathVariable UUID id) {
        return ResponseEntity.ok(adminService.getUser(id));
    }

    @PatchMapping("/users/{id}/plan")
    public ResponseEntity<UserSummary> changePlan(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body,
            Principal principal) {
        Plan newPlan = Plan.valueOf(body.get("plan").toUpperCase());
        return ResponseEntity.ok(adminService.changePlan(id, newPlan, principal.getName()));
    }

    @PatchMapping("/users/{id}/active")
    public ResponseEntity<UserSummary> toggleActive(
            @PathVariable UUID id,
            @RequestBody Map<String, Boolean> body,
            Principal principal) {
        return ResponseEntity.ok(adminService.toggleActive(id, body.get("active"), principal.getName()));
    }
}
