package com.orinuno.service.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orinuno.client.http.RotatingUserAgentProvider;
import com.orinuno.model.KodikContent;
import com.orinuno.model.KodikContentEnrichment;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * META-1 (ADR 0010) — pulls anime metadata from MyAnimeList via the public Jikan REST API ({@code
 * https://api.jikan.moe/v4}). Jikan does not require credentials but rate-limits to ~3 req/sec. The
 * orchestrator should serialise calls per content id; bursting is the caller's responsibility.
 *
 * <p>This client is keyed by Shikimori id today (Shikimori embeds the MAL id under the same numeric
 * value for a large fraction of titles). When orinuno acquires a real MAL id field on {@link
 * KodikContent}, switch to that.
 */
@Slf4j
@Component
public class MalEnrichmentClient implements EnrichmentClient {

    public static final String BASE_URL = "https://api.jikan.moe/v4";

    private final WebClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MalEnrichmentClient(WebClient.Builder builder) {
        this.client =
                builder.baseUrl(BASE_URL)
                        .defaultHeader(
                                "User-Agent",
                                RotatingUserAgentProvider.orinunoBot("mal-enrichment"))
                        .defaultHeader("Accept", "application/json")
                        .build();
    }

    @Override
    public String source() {
        return KodikContentEnrichment.Source.MAL;
    }

    @Override
    public Mono<KodikContentEnrichment> fetch(KodikContent content) {
        if (content == null
                || content.getShikimoriId() == null
                || content.getShikimoriId().isBlank()) {
            return Mono.empty();
        }
        return client.get()
                .uri("/anime/{id}", content.getShikimoriId())
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(this::parseJson)
                .map(node -> toRow(content, node))
                .onErrorResume(
                        ex -> {
                            log.warn(
                                    "META-1 MAL fetch failed for content_id={} (mal_id={}): {}",
                                    content.getId(),
                                    content.getShikimoriId(),
                                    ex.toString());
                            return Mono.empty();
                        });
    }

    private Mono<JsonNode> parseJson(String body) {
        try {
            return Mono.just(objectMapper.readTree(body));
        } catch (Exception ex) {
            return Mono.error(ex);
        }
    }

    static KodikContentEnrichment toRow(KodikContent content, JsonNode envelope) {
        JsonNode data = envelope.path("data");
        LocalDateTime now = LocalDateTime.now();
        BigDecimal score = null;
        if (!data.path("score").isMissingNode() && !data.path("score").isNull()) {
            try {
                score = new BigDecimal(data.path("score").asText());
            } catch (NumberFormatException ignored) {
                score = null;
            }
        }
        return KodikContentEnrichment.builder()
                .contentId(content.getId())
                .source(KodikContentEnrichment.Source.MAL)
                .externalId(asText(data, "mal_id"))
                .titleNative(asText(data, "title_japanese"))
                .titleEnglish(asText(data, "title_english"))
                .titleRussian(null)
                .description(asText(data, "synopsis"))
                .score(score)
                .rawPayload(data.isMissingNode() ? null : data.toString())
                .fetchedAt(now)
                .lastRefreshedAt(now)
                .build();
    }

    private static String asText(JsonNode node, String field) {
        if (node == null || node.path(field).isMissingNode() || node.path(field).isNull()) {
            return null;
        }
        String value = node.path(field).asText("").trim();
        return value.isEmpty() ? null : value;
    }
}
