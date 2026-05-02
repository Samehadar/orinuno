package com.orinuno.service.discovery.shikimori;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orinuno.client.http.RotatingUserAgentProvider;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * PLAYER-5 (ADR 0007) — minimal client for the Shikimori REST API. We only use the {@code
 * /api/animes/{id}} endpoint for metadata enrichment + a derivation of the player URLs visible on
 * the anime page (which Shikimori does not expose directly via the public API; we proxy through
 * {@code /animes/{id}/screenshots} and friends). For player discovery this client returns an empty
 * list — Shikimori's public API does not enumerate Kodik/Aniboom URLs. Use the metadata payload to
 * build a {@code KodikSearchRequest} with the Shikimori id instead.
 *
 * <p>Read-only, rate-limited per Shikimori's published limits (5 req/sec — caller is expected to
 * throttle).
 */
@Slf4j
@Component
public class ShikimoriClient {

    public static final String BASE_URL = "https://shikimori.one";

    private final WebClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ShikimoriClient(WebClient.Builder builder, RotatingUserAgentProvider userAgents) {
        this.client =
                builder.baseUrl(BASE_URL)
                        .defaultHeader(
                                "User-Agent",
                                RotatingUserAgentProvider.orinunoBot("shikimori-discovery"))
                        .defaultHeader("Accept", "application/json")
                        .build();
    }

    public Mono<JsonNode> fetchAnime(long shikimoriId) {
        return client.get()
                .uri("/api/animes/{id}", shikimoriId)
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(this::parseJson);
    }

    /**
     * Shikimori's public API does not expose an endpoint that lists player URLs (Kodik / Aniboom
     * iframes) for an anime. The standard pattern is to take the Shikimori id, hand it to Kodik's
     * {@code /search?shikimori_id=…} endpoint, and let Kodik return the player URL.
     *
     * <p>This method exists as a typed seam so consumers can express the dependency clearly and
     * future-Shikimori-API-versions that DO expose players can be wired in without breaking
     * callers. Today it always returns an empty list.
     */
    public Mono<List<ShikimoriPlayerLink>> fetchPlayerLinks(long shikimoriId) {
        log.debug(
                "PLAYER-5 Shikimori does not expose player URLs via public API; returning empty for"
                        + " shikimoriId={}",
                shikimoriId);
        return Mono.just(new ArrayList<>());
    }

    private Mono<JsonNode> parseJson(String body) {
        try {
            return Mono.just(objectMapper.readTree(body));
        } catch (Exception ex) {
            return Mono.error(ex);
        }
    }
}
