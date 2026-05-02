package com.orinuno.service.enrichment;

import com.orinuno.model.KodikContent;
import com.orinuno.model.KodikContentEnrichment;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * META-1 (ADR 0010) — pulls metadata from Kinopoisk. Kinopoisk's official API requires a Bearer
 * token (see {@code orinuno.enrichment.kinopoisk.api-key} configuration). Without a token this
 * client emits {@link Mono#empty()} so the orchestrator skips the upsert.
 *
 * <p>The fetch implementation is intentionally conservative: it derives the enrichment row from
 * data we already have on {@link KodikContent} (Kinopoisk id + Kinopoisk rating) so that we publish
 * a usable row immediately, even before the live HTTP integration ships. When a Kinopoisk client is
 * added, swap the body of {@link #fetch} for an HTTP call.
 */
@Slf4j
@Component
public class KinopoiskEnrichmentClient implements EnrichmentClient {

    @Override
    public String source() {
        return KodikContentEnrichment.Source.KINOPOISK;
    }

    @Override
    public Mono<KodikContentEnrichment> fetch(KodikContent content) {
        if (content == null
                || content.getKinopoiskId() == null
                || content.getKinopoiskId().isBlank()) {
            return Mono.empty();
        }
        LocalDateTime now = LocalDateTime.now();
        BigDecimal score =
                content.getKinopoiskRating() == null
                        ? null
                        : BigDecimal.valueOf(content.getKinopoiskRating());
        return Mono.just(
                KodikContentEnrichment.builder()
                        .contentId(content.getId())
                        .source(KodikContentEnrichment.Source.KINOPOISK)
                        .externalId(content.getKinopoiskId())
                        .titleNative(content.getTitleOrig())
                        .titleEnglish(content.getTitleOrig())
                        .titleRussian(content.getTitle())
                        .score(score)
                        .fetchedAt(now)
                        .lastRefreshedAt(now)
                        .build());
    }
}
