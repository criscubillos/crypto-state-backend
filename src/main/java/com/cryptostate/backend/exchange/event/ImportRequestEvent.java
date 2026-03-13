package com.cryptostate.backend.exchange.event;

/**
 * Evento publicado cuando el usuario sube un archivo Excel para importar.
 * Procesado por ImportWorker (Spring @Async) tras el commit de la transacción.
 */
public record ImportRequestEvent(
        String userId,
        String connectionId,
        String jobId,
        String exchangeId,
        byte[] fileBytes
) {}
