package com.cryptostate.backend.common.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.spy.memcached.MemcachedClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.Future;

/**
 * Wrapper sobre MemcachedClient con manejo de errores y logging.
 * Usar este servicio en lugar de inyectar MemcachedClient directamente.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemcachedService {

    private final MemcachedClient memcachedClient;

    public void set(String key, int ttlSeconds, Object value) {
        try {
            memcachedClient.set(sanitizeKey(key), ttlSeconds, value);
        } catch (Exception e) {
            log.warn("Memcached set failed for key={}: {}", key, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        try {
            return (T) memcachedClient.get(sanitizeKey(key));
        } catch (Exception e) {
            log.warn("Memcached get failed for key={}: {}", key, e.getMessage());
            return null;
        }
    }

    public void delete(String key) {
        try {
            memcachedClient.delete(sanitizeKey(key));
        } catch (Exception e) {
            log.warn("Memcached delete failed for key={}: {}", key, e.getMessage());
        }
    }

    public boolean exists(String key) {
        return get(key) != null;
    }

    /** Eliminar todas las claves de un usuario. Llama a delete por cada patrón conocido. */
    public void deleteUserSessions(String userId) {
        delete(sessionKey(userId));
        delete(dashboardKey(userId));
        log.info("Cache invalidado para usuario={}", userId);
    }

    // ── Fábricas de claves ──────────────────────────────────────────────────

    public static String sessionKey(String userId) {
        return "session:" + userId;
    }

    public static String refreshTokenKey(String userId) {
        return "refresh:" + userId;
    }

    public static String dashboardKey(String userId) {
        return "dashboard:" + userId;
    }

    public static String rateLimitKey(String prefix, String identifier) {
        return "rl:" + prefix + ":" + identifier;
    }

    // ── Utilidades ──────────────────────────────────────────────────────────

    /** Memcached no admite espacios ni caracteres especiales en claves. */
    private String sanitizeKey(String key) {
        return key.replaceAll("\\s+", "_");
    }
}
