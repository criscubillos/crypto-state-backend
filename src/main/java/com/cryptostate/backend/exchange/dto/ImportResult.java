package com.cryptostate.backend.exchange.dto;

public record ImportResult(
        String jobId,
        String status,
        String exchangeId,
        int transactionsSaved,
        String error
) {}
