package com.cryptostate.backend.auth.dto;

public record AuthResponse(
    String accessToken,
    String refreshToken,
    String userId,
    String email,
    String name,
    String plan,
    String role
) {}
