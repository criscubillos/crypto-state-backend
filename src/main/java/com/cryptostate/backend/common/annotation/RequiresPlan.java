package com.cryptostate.backend.common.annotation;

import com.cryptostate.backend.auth.model.Plan;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Restringe un endpoint o método al plan mínimo indicado.
 * Si el usuario autenticado no tiene el plan requerido, lanza 403 PLAN_REQUIRED.
 *
 * Uso:
 * {@code @RequiresPlan(Plan.PRO)}
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPlan {
    Plan value();
}
