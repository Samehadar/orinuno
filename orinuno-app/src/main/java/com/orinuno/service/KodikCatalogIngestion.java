package com.orinuno.service;

import com.orinuno.catalog.api.CatalogIdentityRequest;
import com.orinuno.catalog.api.CatalogPublicApi;
import com.orinuno.catalog.model.CatalogContentKind;
import com.orinuno.catalog.model.CatalogSourceType;
import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.model.KodikContent;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Bridge between Kodik {@link KodikContent} writes and the L3 universal canonical catalog
 * (ARCH-0016 P1b Step 1.C.B). Mirror of {@link com.orinuno.jutsu.sync.JutsuCatalogIngestion} for
 * the {@code kodik} bounded context — package placement reflects the current orinuno-app layout
 * where Kodik code is spread across {@code com.orinuno.{client,service,repository,model}}; ADR 0016
 * records this as a tech debt to fold once we re-package by bounded context (P3).
 *
 * <p>This is the second half of P1b: jut.su brings rows in from one direction (anchored on {@code
 * (JUTSU, slug)}), Kodik brings them in from another (anchored on {@code (KODIK, kodikId)} plus
 * Kinopoisk / IMDB / Shikimori external ids harvested from upstream). When both bridges are enabled
 * together, the resolver merges the two views into a single canonical row the moment the external
 * ids overlap — a jut.su slug for "Naruto" and a Kodik raw id for the same anime, both carrying
 * {@code shikimoriId="1"}, end up bound to the same {@code catalog_content.id}.
 *
 * <p>Failure isolation: any {@link RuntimeException} from the resolver is caught and logged at
 * WARN. {@link ContentService#findOrCreateContent(KodikContent)} stays the system of record for
 * "Kodik gave us this title" — it must never break because L3 produced a transient deadlock or the
 * resolver hit a not-yet-fixed bug. Subsequent write attempts re-run ingestion (idempotent by
 * design).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KodikCatalogIngestion {

    private final CatalogPublicApi catalog;
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
        try {
            CatalogIdentityRequest request = toRequest(sourceId, content);
            catalog.findOrCreateContent(request);
        } catch (RuntimeException ex) {
            log.warn(
                    "kodik-catalog-ingest: catalog ingestion for kodikContentId={} failed"
                            + " ({}: {}); kodik_content row stays untouched, will retry on next"
                            + " write",
                    content.getId(),
                    ex.getClass().getSimpleName(),
                    ex.getMessage());
        }
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
     * Map a {@link KodikContent} row into a {@link CatalogIdentityRequest}. Picks the best-fit
     * canonical kind from Kodik's free-form {@code type} string (anime → ANIME, anything with
     * {@code "serial"} → SERIES, {@code "movie"} / {@code "film"} → MOVIE, otherwise {@link
     * CatalogContentKind#UNKNOWN}). External-database ids ({@code shikimoriId}, {@code
     * kinopoiskId}, {@code imdbId}) are passed through to the resolver, which uses them in its
     * shikimori → mal → imdb → kinopoisk priority order to merge rows across sources.
     */
    static CatalogIdentityRequest toRequest(String sourceId, KodikContent content) {
        return CatalogIdentityRequest.builder(CatalogSourceType.KODIK, sourceId)
                .titleRu(content.getTitle())
                .titleEn(content.getTitleOrig())
                .kind(mapKind(content.getType()))
                .year(content.getYear())
                .shikimoriId(blankToNull(content.getShikimoriId()))
                .imdbId(blankToNull(content.getImdbId()))
                .kinopoiskId(blankToNull(content.getKinopoiskId()))
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
     *   <li>otherwise → UNKNOWN (resolver writes UNKNOWN; future call-site with a richer source can
     *       promote the kind via COALESCE)
     * </ul>
     */
    static CatalogContentKind mapKind(String kodikType) {
        if (kodikType == null || kodikType.isBlank()) {
            return CatalogContentKind.UNKNOWN;
        }
        String normalised = kodikType.trim().toLowerCase(Locale.ROOT);
        if (normalised.contains("anime")) return CatalogContentKind.ANIME;
        if (normalised.contains("serial")) return CatalogContentKind.SERIES;
        if (normalised.contains("movie") || normalised.contains("film")) {
            return CatalogContentKind.MOVIE;
        }
        return CatalogContentKind.UNKNOWN;
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
