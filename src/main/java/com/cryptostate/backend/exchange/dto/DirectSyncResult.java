package com.cryptostate.backend.exchange.dto;

public record DirectSyncResult(
        String jobId,
        String status,
        String exchangeId,
        int transactionsSaved,
        String error
) {}
