package com.cryptostate.backend.exchange.model;

import com.cryptostate.backend.auth.model.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "exchange_connections")
@Getter @Setter @Builder
@NoArgsConstructor @AllArgsConstructor
public class ExchangeConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Identificador del exchange. Ej: "binance", "bingx", "bybit" */
    @Column(name = "exchange_id", nullable = false)
    private String exchangeId;

    /** API key encriptada con AES-256-GCM */
    @Column(nullable = false, length = 1024)
    private String apiKeyEncrypted;

    /** API secret encriptado con AES-256-GCM */
    @Column(nullable = false, length = 1024)
    private String apiSecretEncrypted;

    private String label;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    private Instant lastSyncAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;
}
