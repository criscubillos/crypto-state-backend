package com.cryptostate.backend.common.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class HistoricalPriceKey implements Serializable {

    @Column(name = "asset", length = 20, nullable = false)
    private String asset;

    @Column(name = "price_date", nullable = false)
    private LocalDate priceDate;
}
