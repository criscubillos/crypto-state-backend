package com.cryptostate.backend.exchange.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateConnectionRequest(
    @NotBlank String exchangeId,
    @NotBlank String apiKey,
    @NotBlank String apiSecret,
    String label
) {}
