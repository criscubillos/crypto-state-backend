package com.cryptostate.backend.auth.service;

import com.cryptostate.backend.auth.dto.*;
import com.cryptostate.backend.auth.model.*;
import com.cryptostate.backend.auth.repository.*;
import com.cryptostate.backend.common.exception.ApiException;
import com.cryptostate.backend.common.util.MemcachedService;
import com.cryptostate.backend.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final JwtService jwtService;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final MemcachedService memcachedService;
    private final AppProperties appProperties;

    // ── Registro ────────────────────────────────────────────────────────────

    @Transactional
    public void register(RegisterRequest req) {
        if (userRepository.existsByEmail(req.email())) {
            throw ApiException.conflict("El email ya está registrado");
        }

        User user = User.builder()
                .email(req.email().toLowerCase().trim())
                .passwordHash(passwordEncoder.encode(req.password()))
                .name(req.name())
                .country(req.country().toUpperCase())
                .build();

        userRepository.save(user);

        String verificationToken = UUID.randomUUID().toString();
        emailVerificationTokenRepository.save(EmailVerificationToken.builder()
                .user(user)
                .token(verificationToken)
                .expiresAt(Instant.now().plusSeconds(86400)) // 24h
                .build());

        emailService.sendVerificationEmail(user.getEmail(), user.getName(), verificationToken);
        log.info("Usuario registrado: {}", user.getEmail());
    }

    // ── Verificación de email ────────────────────────────────────────────────

    @Transactional
    public void verifyEmail(String token) {
        EmailVerificationToken evt = emailVerificationTokenRepository.findByToken(token)
                .orElseThrow(() -> ApiException.badRequest("Token de verificación inválido"));

        if (!evt.isValid()) {
            throw ApiException.badRequest("Token de verificación expirado o ya usado");
        }

        evt.getUser().setEmailVerified(true);
        evt.setUsed(true);
        log.info("Email verificado: {}", evt.getUser().getEmail());
    }

    // ── Login ────────────────────────────────────────────────────────────────

    @Transactional
    public AuthResponse login(LoginRequest req) {
        User user = userRepository.findByEmail(req.email().toLowerCase().trim())
                .orElseThrow(() -> ApiException.unauthorized("Credenciales inválidas"));

        if (!user.isActive()) {
            throw ApiException.unauthorized("Cuenta desactivada");
        }
        if (!user.isEmailVerified()) {
            throw ApiException.unauthorized("Debes verificar tu email antes de iniciar sesión");
        }
        if (user.getPasswordHash() == null || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw ApiException.unauthorized("Credenciales inválidas");
        }

        return issueTokenPair(user);
    }

    // ── Refresh token ─────────────────────────────────────────────────────────

    @Transactional
    public AuthResponse refresh(String rawRefreshToken) {
        RefreshToken rt = refreshTokenRepository.findByToken(rawRefreshToken)
                .orElseThrow(() -> ApiException.unauthorized("Refresh token inválido"));

        if (!rt.isValid()) {
            throw ApiException.unauthorized("Refresh token expirado o revocado");
        }

        // Refresh token rotativo: revocar el actual
        rt.setRevoked(true);
        return issueTokenPair(rt.getUser());
    }

    // ── Logout ───────────────────────────────────────────────────────────────

    @Transactional
    public void logout(String userId) {
        userRepository.findById(UUID.fromString(userId)).ifPresent(user -> {
            refreshTokenRepository.revokeAllByUser(user);
            memcachedService.deleteUserSessions(userId);
            log.info("Logout efectivo para usuario={}", userId);
        });
    }

    // ── OAuth Google ──────────────────────────────────────────────────────────

    @Transactional
    public AuthResponse loginOrRegisterWithGoogle(String googleId, String email, String name) {
        User user = userRepository.findByGoogleId(googleId)
                .orElseGet(() -> userRepository.findByEmail(email.toLowerCase())
                        .map(existing -> {
                            existing.setGoogleId(googleId);
                            existing.setEmailVerified(true);
                            return existing;
                        })
                        .orElseGet(() -> userRepository.save(User.builder()
                                .email(email.toLowerCase())
                                .googleId(googleId)
                                .name(name)
                                .country("CL") // default; el usuario puede cambiarlo en su perfil
                                .emailVerified(true)
                                .build())));

        if (!user.isActive()) {
            throw ApiException.unauthorized("Cuenta desactivada");
        }

        return issueTokenPair(user);
    }

    // ── Utilidades ───────────────────────────────────────────────────────────

    private AuthResponse issueTokenPair(User user) {
        String accessToken = jwtService.generateAccessToken(user);

        String rawRefreshToken = UUID.randomUUID().toString();
        refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .token(rawRefreshToken)
                .expiresAt(Instant.now().plusMillis(
                        appProperties.getJwt().getRefreshTokenExpirationMs()))
                .build());

        // Almacenar sesión activa en Memcached
        int ttlSeconds = (int) (appProperties.getJwt().getAccessTokenExpirationMs() / 1000);
        memcachedService.set(MemcachedService.sessionKey(user.getId().toString()), ttlSeconds, "active");

        return new AuthResponse(accessToken, rawRefreshToken, user.getId().toString(),
                user.getEmail(), user.getName(), user.getPlan().name(), user.getRole().name());
    }
}
