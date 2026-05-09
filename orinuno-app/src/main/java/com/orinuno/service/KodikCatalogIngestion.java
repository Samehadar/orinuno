package com.orinuno.service;

import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.contract.source.ContentKindHint;
import com.orinuno.contract.source.ExternalIds;
import com.orinuno.contract.source.Provenance;
import com.orinuno.contract.source.SourceCatalogEvent;
import com.orinuno.contract.source.SourceContentInfo;
import com.orinuno.contract.source.SourceEventEmitter;
import com.orinuno.contract.source.SourceIdentifier;
import com.orinuno.model.KodikContent;
import java.time.Instant;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Bridge between Kodik {@link KodikContent} writes and the producer-side event contract
 * (ARCH-0017). Hands a {@link SourceCatalogEvent.TitleObserved} to the configured {@link
 * SourceEventEmitter}; the default in-process emitter ({@code CatalogSinkEventEmitter}) translates
 * it back into a catalog identity request and writes into L3.
 *
 * <p>Behavioural contract is unchanged from ARCH-0016 P1b Step 1.C.B: when both this bridge and its
 * jut.su sibling are enabled, two source observations carrying the same external-database id
 * (Shikimori / IMDB / Kinopoisk) collapse into a single canonical row. The pipeline now goes {@code
 * KodikContent → SourceCatalogEvent → SourceEventEmitter → CatalogPublicApi} instead of the old
 * {@code KodikContent → CatalogIdentityRequest → CatalogPublicApi} — same transactional semantics,
 * same kill-switch, same failure isolation (the emitter swallows resolver RuntimeExceptions).
 *
 * <p>Failure isolation: the emitter's own try/catch is the safety net. This class still defends
 * against {@code null} content, missing identifiers, and the catalog-ingestion kill-switch ({@code
 * orinuno.kodik.catalog-ingestion.enabled}, default {@code false}) before constructing the event so
 * disabled deployments don't pay the (cheap) DTO build cost.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KodikCatalogIngestion {

    private final SourceEventEmitter emitter;
    private final OrinunoProperties properties;

    public void ingest(KodikContent content) {
        if (content == null) return;
        if (!properties.getKodik().getCatalogIngestion().isEnabled()) return;

        String sourceId = resolveSourceId(content);
        if (sourceId == null) {
            log.debug(
                    "kodik-catalog-ingest: no usable sourceId for content id={} (kodikId={},"
                            + " kinopoiskId={}); skipping",
                    content.getId(),
                    content.getKodikId(),
                    content.getKinopoiskId());
            return;
        }

        SourceCatalogEvent event =
                new SourceCatalogEvent.TitleObserved(
                        SourceIdentifier.of("kodik", sourceId),
                        toContentInfo(content),
                        provenance(content));
        emitter.emit(event);
    }

    /**
     * Build the per-Kodik-row sourceId. Prefer the upstream {@code kodikId} (e.g. {@code
     * "movie-12345"}) because that's what Kodik's own API uses to address the row; fall back to an
     * internal {@code kp:<kinopoiskId>} synthesis when {@code kodikId} is missing (rare, but
     * happens for partially-ingested rows). Returning {@code null} aborts ingestion — better to
     * skip than to attach a meaningless binding the resolver can never reverse-look-up.
     */
    static String resolveSourceId(KodikContent content) {
        if (content.getKodikId() != null && !content.getKodikId().isBlank()) {
            return content.getKodikId().trim();
        }
        if (content.getKinopoiskId() != null && !content.getKinopoiskId().isBlank()) {
            return "kp:" + content.getKinopoiskId().trim();
        }
        return null;
    }

    /**
     * Map a {@link KodikContent} row into a {@link SourceContentInfo}. Picks the best-fit kind hint
     * from Kodik's free-form {@code type} string (anime → ANIME, anything with {@code "serial"} →
     * SERIES, {@code "movie"} / {@code "film"} → MOVIE, otherwise {@link ContentKindHint#UNKNOWN}).
     * External-database ids ({@code shikimoriId} / {@code kinopoiskId} / {@code imdbId}) are passed
     * through as-is; the emitter forwards them to the resolver which uses them in its merge
     * priority order.
     */
    static SourceContentInfo toContentInfo(KodikContent content) {
        return SourceContentInfo.builder()
                .titleRu(content.getTitle())
                .titleEn(content.getTitleOrig())
                .kindHint(mapKind(content.getType()))
                .year(content.getYear())
                .externalIds(
                        ExternalIds.builder()
                                .shikimoriId(content.getShikimoriId())
                                .imdbId(content.getImdbId())
                                .kinopoiskId(content.getKinopoiskId())
                                .build())
                .build();
    }

    /**
     * Translate Kodik's free-form {@code type} string into the canonical kind vocabulary. Kodik
     * uses values like {@code "anime"}, {@code "anime-serial"}, {@code "russian-movie"}, {@code
     * "foreign-serial"}, {@code "documentary-serial"}, {@code "cartoon-serial"}, etc. The mapping
     * is intentionally pattern-based rather than exact-match because Kodik occasionally adds new
     * type slugs, and we'd rather collapse them sensibly than dump everything into UNKNOWN.
     *
     * <ul>
     *   <li>contains {@code "anime"} → ANIME (covers {@code anime}, {@code anime-serial})
     *   <li>contains {@code "serial"} → SERIES (covers all *-serial slugs that aren't anime)
     *   <li>contains {@code "movie"} or {@code "film"} → MOVIE
     *   <li>otherwise → UNKNOWN (resolver treats UNKNOWN as "leave the canonical kind alone")
     * </ul>
     */
    static ContentKindHint mapKind(String kodikType) {
        if (kodikType == null || kodikType.isBlank()) {
            return ContentKindHint.UNKNOWN;
        }
        String normalised = kodikType.trim().toLowerCase(Locale.ROOT);
        if (normalised.contains("anime")) return ContentKindHint.ANIME;
        if (normalised.contains("serial")) return ContentKindHint.SERIES;
        if (normalised.contains("movie") || normalised.contains("film")) {
            return ContentKindHint.MOVIE;
        }
        return ContentKindHint.UNKNOWN;
    }

    /**
     * Construct the contract's {@link Provenance} record from what we know about this Kodik upsert.
     * {@code sourceUrl} is the canonical Kodik API root because the L1 row was harvested via {@code
     * /list} or the search-then-decode pipeline — we don't carry the per-row upstream URL through
     * to {@code KodikContent} today, so the API root stands in as a coarse anchor. SDK version /
     * parser mode / drift flags are not threaded yet; {@link Provenance#of(String,
     * java.time.Instant)} keeps them null so the contract artifact's {@code @JsonInclude(NON_NULL)}
     * suppresses them on the wire.
     */
    private static Provenance provenance(KodikContent content) {
        Instant fetchedAt =
                content.getUpdatedAt() != null
                        ? content.getUpdatedAt().atZone(java.time.ZoneOffset.UTC).toInstant()
                        : Instant.now();
        return Provenance.of("https://kodik-api.com", fetchedAt);
    }
}
