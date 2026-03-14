package com.cryptostate.backend.exchange.dto;

/**
 * Evento emitido por SSE durante una sincronización masiva.
 *
 * type: START | DONE | ERROR | COMPLETE
 */
public record SyncProgressEvent(
        String type,
        String exchangeId,
        String label,
        String message,
        int saved
) {}
