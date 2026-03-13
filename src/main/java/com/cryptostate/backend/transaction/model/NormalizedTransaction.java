package com.cryptostate.backend.transaction.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "normalized_transactions",
    indexes = {
        @Index(columnList = "user_id"),
        @Index(columnList = "exchange_id"),
        @Index(columnList = "connection_id"),
        @Index(columnList = "timestamp"),
        @Index(columnList = "user_id, connection_id, external_id", unique = true)
    })
@Getter @Setter @Builder
@NoArgsConstructor @AllArgsConstructor
public class NormalizedTransaction {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** ID de la conexión (cuenta API) que originó esta transacción */
    @Column(name = "connection_id")
    private UUID connectionId;

    @Column(name = "exchange_id", nullable = false)
    private String exchangeId;

    /** ID original del exchange — usado para deduplicación */
    @Column(name = "external_id", nullable = false)
    private String externalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    private String baseAsset;
    private String quoteAsset;

    @Column(precision = 30, scale = 10)
    private BigDecimal quantity;

    /** Precio en quoteAsset */
    @Column(precision = 30, scale = 10)
    private BigDecimal price;

    @Column(precision = 30, scale = 10)
    private BigDecimal fee;

    private String feeAsset;

    /** Ganancia/pérdida realizada en esta transacción */
    @Column(precision = 30, scale = 10)
    private BigDecimal realizedPnl;

    /** G/P realizado convertido a USD (1:1 para USDT, precio histórico para otras monedas) */
    @Column(name = "realized_pnl_usd", precision = 30, scale = 10)
    private BigDecimal realizedPnlUsd;

    /** Comisión convertida a USD */
    @Column(name = "fee_usd", precision = 30, scale = 10)
    private BigDecimal feeUsd;

    @Column(nullable = false)
    private Instant timestamp;

    /**
     * Datos crudos originales del exchange en formato JSONB.
     * Preserva toda la información original para auditoría y re-normalización.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> rawData;
}
