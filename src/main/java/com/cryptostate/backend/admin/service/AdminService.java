package com.cryptostate.backend.admin.service;

import com.cryptostate.backend.admin.dto.AdminStats;
import com.cryptostate.backend.admin.dto.UserSummary;
import com.cryptostate.backend.auth.model.Plan;
import com.cryptostate.backend.auth.model.Role;
import com.cryptostate.backend.auth.model.User;
import com.cryptostate.backend.auth.repository.UserRepository;
import com.cryptostate.backend.common.exception.ApiException;
import com.cryptostate.backend.common.util.MemcachedService;
import com.cryptostate.backend.exchange.repository.SyncJobRepository;
import com.cryptostate.backend.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final SyncJobRepository syncJobRepository;
    private final MemcachedService memcachedService;

    public Page<UserSummary> listUsers(String search, String plan, String country,
                                       Boolean active, Pageable pageable) {
        return userRepository
                .findFiltered(search, plan, country, active, pageable)
                .map(UserSummary::from);
    }

    public UserSummary getUser(UUID userId) {
        return userRepository.findById(userId)
                .map(UserSummary::from)
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));
    }

    @Transactional
    public UserSummary changePlan(UUID userId, Plan newPlan, String adminId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));

        Plan oldPlan = user.getPlan();
        user.setPlan(newPlan);
        userRepository.save(user);
        memcachedService.deleteUserSessions(userId.toString());

        log.info("Admin={} cambió plan de userId={} de {} a {}", adminId, userId, oldPlan, newPlan);
        return UserSummary.from(user);
    }

    @Transactional
    public UserSummary toggleActive(UUID userId, boolean active, String adminId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("Usuario no encontrado"));

        user.setActive(active);
        userRepository.save(user);

        if (!active) {
            memcachedService.deleteUserSessions(userId.toString());
        }

        log.info("Admin={} {} cuenta userId={}", adminId, active ? "activó" : "desactivó", userId);
        return UserSummary.from(user);
    }

    public AdminStats getStats() {
        long total   = userRepository.count();
        long free    = userRepository.countByPlan(Plan.FREE);
        long pro     = userRepository.countByPlan(Plan.PRO);
        long active  = userRepository.countByActiveTrue();
        long txCount = transactionRepository.count();
        long syncs   = syncJobRepository.count();

        return new AdminStats(total, free, pro, active, txCount, syncs);
    }
}
