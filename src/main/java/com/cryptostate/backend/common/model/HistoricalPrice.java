package com.cryptostate.backend.common.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Precio histórico diario de un activo en USD.
 * Persiste los resultados de CoinGecko para evitar llamadas repetidas.
 */
@Entity
@Table(name = "historical_prices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HistoricalPrice {

    @EmbeddedId
    private HistoricalPriceKey id;

    @Column(name = "price_usd", nullable = false, precision = 30, scale = 10)
    private BigDecimal priceUsd;

    public static HistoricalPrice of(String asset, LocalDate date, BigDecimal price) {
        return HistoricalPrice.builder()
                .id(new HistoricalPriceKey(asset, date))
                .priceUsd(price)
                .build();
    }

    public String getAsset() { return id.getAsset(); }
    public LocalDate getPriceDate() { return id.getPriceDate(); }
}
