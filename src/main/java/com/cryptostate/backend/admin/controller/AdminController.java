package com.cryptostate.backend.admin.controller;

import com.cryptostate.backend.auth.model.Plan;
import com.cryptostate.backend.auth.model.User;
import com.cryptostate.backend.auth.repository.UserRepository;
import com.cryptostate.backend.common.exception.ApiException;
import com.cryptostate.backend.common.util.MemcachedService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminController {

    private final UserRepository userRepository;
    private final MemcachedService memcachedService;

    @GetMapping("/users")
    public ResponseEntity<Page<User>> listUsers(@PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(userRepository.findAll(pageable));
    }

    @PatchMapping("/users/{id}/plan")
    public ResponseEntity<Map<String, String>> changePlan(
            @PathVariable UUID id, @RequestBody Map<String, String> body) {
        Plan newPlan = Plan.valueOf(body.get("plan").toUpperCase());

        User user = userRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));
        user.setPlan(newPlan);
        userRepository.save(user);

        // Invalidar cache del usuario afectado
        memcachedService.deleteUserSessions(id.toString());

        return ResponseEntity.ok(Map.of(
                "message", "Plan actualizado",
                "userId", id.toString(),
                "plan", newPlan.name()
        ));
    }

    @PatchMapping("/users/{id}/active")
    public ResponseEntity<Map<String, String>> toggleActive(
            @PathVariable UUID id, @RequestBody Map<String, Boolean> body) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));
        user.setActive(body.get("active"));
        userRepository.save(user);

        if (!Boolean.TRUE.equals(body.get("active"))) {
            memcachedService.deleteUserSessions(id.toString());
        }

        return ResponseEntity.ok(Map.of(
                "message", "Estado actualizado",
                "userId", id.toString(),
                "active", String.valueOf(user.isActive())
        ));
    }
}
