package com.cryptostate.backend.common.filter;

import com.cryptostate.backend.common.util.MemcachedService;
import com.cryptostate.backend.config.AppProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final AppProperties appProperties;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        if (path.startsWith("/api/auth/login") || path.startsWith("/api/auth/register")) {
            AppProperties.RateLimit.Bucket cfg = appProperties.getRateLimit().getAuth();
            String key = "auth:" + getClientIp(request);

            Bucket bucket = buckets.computeIfAbsent(key, k -> buildBucket(cfg));

            if (!bucket.tryConsume(1)) {
                log.warn("Rate limit excedido para IP={} en path={}", getClientIp(request), path);
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType("application/json");
                response.getWriter().write("""
                    {"error":"RATE_LIMIT_EXCEEDED","message":"Demasiados intentos. Intenta más tarde."}
                    """);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private Bucket buildBucket(AppProperties.RateLimit.Bucket cfg) {
        Bandwidth limit = Bandwidth.classic(cfg.getCapacity(),
                Refill.intervally(cfg.getCapacity(),
                        Duration.ofMinutes(cfg.getRefillDurationMinutes())));
        return Bucket.builder().addLimit(limit).build();
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
