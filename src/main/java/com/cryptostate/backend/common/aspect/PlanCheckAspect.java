package com.cryptostate.backend.common.aspect;

import com.cryptostate.backend.auth.model.Plan;
import com.cryptostate.backend.common.annotation.RequiresPlan;
import com.cryptostate.backend.common.exception.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
public class PlanCheckAspect {

    @Before("@annotation(requiresPlan)")
    public void checkPlan(RequiresPlan requiresPlan) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !auth.isAuthenticated()) {
            throw ApiException.unauthorized("No autenticado");
        }

        Plan requiredPlan = requiresPlan.value();
        boolean hasPlan = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority -> {
                    if (!authority.startsWith("PLAN_")) return false;
                    try {
                        Plan userPlan = Plan.valueOf(authority.substring(5));
                        return userPlan.includes(requiredPlan);
                    } catch (IllegalArgumentException e) {
                        return false;
                    }
                });

        if (!hasPlan) {
            log.debug("Acceso denegado: se requiere plan {} para userId={}",
                    requiredPlan, auth.getName());
            throw ApiException.planRequired(requiredPlan.name());
        }
    }
}
