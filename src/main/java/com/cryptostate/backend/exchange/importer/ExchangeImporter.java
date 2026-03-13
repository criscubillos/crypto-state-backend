package com.cryptostate.backend.exchange.importer;

import com.cryptostate.backend.transaction.model.NormalizedTransaction;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

public interface ExchangeImporter {
    String exchangeId();
    List<NormalizedTransaction> parse(InputStream input, UUID userId, UUID connectionId) throws Exception;
}
