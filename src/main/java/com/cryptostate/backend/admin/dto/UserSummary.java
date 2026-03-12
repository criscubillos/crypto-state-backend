package com.cryptostate.backend.admin.dto;

import com.cryptostate.backend.auth.model.Plan;
import com.cryptostate.backend.auth.model.Role;
import com.cryptostate.backend.auth.model.User;

import java.time.Instant;
import java.util.UUID;

public record UserSummary(
    UUID id,
    String email,
    String name,
    String country,
    Plan plan,
    Role role,
    boolean active,
    boolean emailVerified,
    Instant createdAt
) {
    public static UserSummary from(User u) {
        return new UserSummary(
            u.getId(), u.getEmail(), u.getName(), u.getCountry(),
            u.getPlan(), u.getRole(), u.isActive(), u.isEmailVerified(), u.getCreatedAt()
        );
    }
}
