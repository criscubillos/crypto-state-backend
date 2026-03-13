package com.cryptostate.backend.exchange.dto;

public record UpdateConnectionRequest(
        String apiKey,
        String apiSecret,
        String label,
        Boolean resetSync
) {}
