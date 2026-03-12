package com.cryptostate.backend.exchange.repository;

import com.cryptostate.backend.exchange.model.SyncJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SyncJobRepository extends JpaRepository<SyncJob, UUID> {
    Optional<SyncJob> findByIdAndUserId(UUID id, UUID userId);
    List<SyncJob> findByUserIdOrderByRequestedAtDesc(UUID userId);
}
