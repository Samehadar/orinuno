package com.orinuno.service.enrichment;

import com.orinuno.model.KodikContent;
import com.orinuno.model.KodikContentEnrichment;
import reactor.core.publisher.Mono;

/**
 * META-1 (ADR 0010) — provider contract for metadata enrichment. Each implementation pulls one
 * source (Shikimori / Kinopoisk / MAL) and converts the upstream payload into a {@link
 * KodikContentEnrichment} row. Implementations MUST tolerate missing external ids by emitting
 * {@link Mono#empty()} (the service will skip the upsert).
 */
public interface EnrichmentClient {

    /** Source discriminator written to {@code kodik_content_enrichment.source}. */
    String source();

    Mono<KodikContentEnrichment> fetch(KodikContent content);
}
