package com.cryptostate.backend.exchange.service;

import com.cryptostate.backend.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Publica mensajes mínimos a Cloudflare Queue.
 *
 * Estructura del mensaje (lo más pequeño posible para minimizar costo):
 * { "uid": "user-uuid", "eid": "exchange-id", "t": 1710000000 }
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CloudflareQueueService {

    private final AppProperties appProperties;
    private final WebClient.Builder webClientBuilder;

    public void publishSyncMessage(String userId, String exchangeId) {
        AppProperties.Cloudflare cf = appProperties.getCloudflare();

        // Mensaje mínimo: solo IDs + timestamp para deduplicación
        Map<String, Object> body = Map.of("messages", List.of(
            Map.of("body", Map.of(
                "uid", userId,
                "eid", exchangeId,
                "t",   Instant.now().getEpochSecond()
            ))
        ));

        String url = "https://api.cloudflare.com/client/v4/accounts/"
                + cf.getAccountId() + "/queues/" + cf.getQueueId() + "/messages";

        webClientBuilder.build()
                .post()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + cf.getApiToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .subscribe(
                        r -> log.info("Mensaje publicado en Cloudflare Queue: userId={} exchange={}", userId, exchangeId),
                        e -> log.error("Error publicando en Cloudflare Queue: {}", e.getMessage())
                );
    }
}
