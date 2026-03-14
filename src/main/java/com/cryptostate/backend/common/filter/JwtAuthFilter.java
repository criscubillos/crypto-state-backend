package com.cryptostate.backend.common.filter;

import com.cryptostate.backend.auth.repository.UserRepository;
import com.cryptostate.backend.auth.service.JwtService;
import com.cryptostate.backend.common.util.MemcachedService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final MemcachedService memcachedService;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String token = extractToken(request);

        if (token != null && jwtService.isValid(token)) {
            Claims claims = jwtService.validateAndParse(token);
            String userId = claims.getSubject();

            // Verificar que la sesión sigue activa en Memcached (logout efectivo)
            if (memcachedService.exists(MemcachedService.sessionKey(userId))) {
                String role = claims.get("role", String.class);
                String plan = claims.get("plan", String.class);

                var auth = new UsernamePasswordAuthenticationToken(
                        userId, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role),
                                new SimpleGrantedAuthority("PLAN_" + plan)));
                auth.setDetails(claims);
                SecurityContextHolder.getContext().setAuthentication(auth);
            } else {
                log.debug("Sesión no encontrada en Memcached para userId={}", userId);
            }
        }

        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        // Fallback para SSE: EventSource no puede enviar headers personalizados,
        // se acepta el token como query param ?token= en rutas de streaming.
        String paramToken = request.getParameter("token");
        if (StringUtils.hasText(paramToken)) {
            return paramToken;
        }
        return null;
    }
}
