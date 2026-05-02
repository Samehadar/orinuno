package com.orinuno.service.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import com.orinuno.model.KodikContent;
import com.orinuno.model.KodikContentEnrichment;
import com.orinuno.service.discovery.shikimori.ShikimoriClient;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** META-1 (ADR 0010) — pulls anime metadata from Shikimori using its existing client. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShikimoriEnrichmentClient implements EnrichmentClient {

    private final ShikimoriClient shikimoriClient;

    @Override
    public String source() {
        return KodikContentEnrichment.Source.SHIKIMORI;
    }

    @Override
    public Mono<KodikContentEnrichment> fetch(KodikContent content) {
        if (content == null
                || content.getShikimoriId() == null
                || content.getShikimoriId().isBlank()) {
            return Mono.empty();
        }
        long shikimoriId;
        try {
            shikimoriId = Long.parseLong(content.getShikimoriId());
        } catch (NumberFormatException ex) {
            log.debug(
                    "META-1 Shikimori: non-numeric shikimori_id={} on content_id={}",
                    content.getShikimoriId(),
                    content.getId());
            return Mono.empty();
        }
        return shikimoriClient
                .fetchAnime(shikimoriId)
                .map(node -> toRow(content, shikimoriId, node))
                .onErrorResume(
                        ex -> {
                            log.warn(
                                    "META-1 Shikimori fetch failed for content_id={}: {}",
                                    content.getId(),
                                    ex.toString());
                            return Mono.empty();
                        });
    }

    static KodikContentEnrichment toRow(KodikContent content, long shikimoriId, JsonNode node) {
        LocalDateTime now = LocalDateTime.now();
        return KodikContentEnrichment.builder()
                .contentId(content.getId())
                .source(KodikContentEnrichment.Source.SHIKIMORI)
                .externalId(String.valueOf(shikimoriId))
                .titleNative(asText(node, "japanese"))
                .titleEnglish(asText(node, "name"))
                .titleRussian(asText(node, "russian"))
                .description(asText(node, "description"))
                .score(asScore(node))
                .rawPayload(node == null ? null : node.toString())
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

    private static BigDecimal asScore(JsonNode node) {
        if (node == null || node.path("score").isMissingNode() || node.path("score").isNull()) {
            return null;
        }
        try {
            return new BigDecimal(node.path("score").asText());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
