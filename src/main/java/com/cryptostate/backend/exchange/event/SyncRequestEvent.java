package com.cryptostate.backend.exchange.event;

/**
 * Evento publicado cuando el usuario dispara una sincronización.
 * En local lo procesa SyncWorker (Spring @Async).
 * En producción equivale al mensaje publicado en Cloudflare Queue.
 */
public record SyncRequestEvent(String userId, String connectionId, String jobId, String exchangeId) {}
