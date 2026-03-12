package com.cryptostate.backend.auth.repository;

import com.cryptostate.backend.auth.model.Plan;
import com.cryptostate.backend.auth.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);
    Optional<User> findByGoogleId(String googleId);
    boolean existsByEmail(String email);

    // ── Admin queries ────────────────────────────────────────────────────────

    @Query("""
        SELECT u FROM User u
        WHERE (:search IS NULL OR
               LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%')) OR
               LOWER(u.name)  LIKE LOWER(CONCAT('%', :search, '%')))
          AND (:plan    IS NULL OR CAST(u.plan    AS string) = :plan)
          AND (:country IS NULL OR u.country = :country)
          AND (:active  IS NULL OR u.active = :active)
        ORDER BY u.createdAt DESC
        """)
    Page<User> findFiltered(String search, String plan, String country,
                             Boolean active, Pageable pageable);

    long countByPlan(Plan plan);
    long countByActiveTrue();
}
