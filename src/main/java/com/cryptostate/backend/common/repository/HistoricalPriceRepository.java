package com.cryptostate.backend.common.repository;

import com.cryptostate.backend.common.model.HistoricalPrice;
import com.cryptostate.backend.common.model.HistoricalPriceKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface HistoricalPriceRepository extends JpaRepository<HistoricalPrice, HistoricalPriceKey> {
}
