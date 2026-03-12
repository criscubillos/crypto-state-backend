package com.cryptostate.backend.exchange.model;

import com.cryptostate.backend.auth.model.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sync_jobs")
@Getter @Setter @Builder
@NoArgsConstructor @AllArgsConstructor
public class SyncJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "exchange_id", nullable = false)
    private String exchangeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private SyncStatus status = SyncStatus.PENDING;

    @Column(length = 1024)
    private String error;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant requestedAt;

    private Instant completedAt;

    public enum SyncStatus {
        PENDING, PROCESSING, DONE, FAILED
    }
}
