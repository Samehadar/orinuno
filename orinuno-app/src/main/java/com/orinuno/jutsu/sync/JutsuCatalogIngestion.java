package com.orinuno.jutsu.sync;

import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.contract.source.ContentKindHint;
import com.orinuno.contract.source.ExternalIds;
import com.orinuno.contract.source.Provenance;
import com.orinuno.contract.source.SourceCatalogEvent;
import com.orinuno.contract.source.SourceContentInfo;
import com.orinuno.contract.source.SourceEventEmitter;
import com.orinuno.contract.source.SourceIdentifier;
import com.orinuno.jutsu.model.JutsuTitle;
import java.time.Instant;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Bridge between the jut.su L1 cache and the producer-side event contract (ARCH-0017). Hands a
 * {@link SourceCatalogEvent.TitleObserved} to the configured {@link SourceEventEmitter} once per
 * {@link JutsuTitle} upsert; the default in-process emitter ({@code CatalogSinkEventEmitter})
 * translates it back into a catalog identity request and writes into L3.
 *
 * <p>This is the jut.su half of P1b: jut.su brings rows in anchored on {@code (JUTSU, slug)}, Kodik
 * brings them in anchored on {@code (KODIK, kodikId)} plus any external-database ids it harvested.
 * When both bridges are enabled together, the resolver merges the two views into a single canonical
 * row the moment external-database ids overlap.
 *
 * <p>Failure isolation: the emitter swallows resolver {@link RuntimeException}s; this class
 * additionally short-circuits on {@code null} / blank slug or when the catalog-ingestion
 * kill-switch ({@code orinuno.providers.jutsu.sync.catalog-ingestion.enabled}, default {@code
 * false}) is off, so disabled deployments don't pay the (cheap) DTO build cost.
 *
 * <p>jut.su's {@link JutsuTitle} carries no third-party identifiers today (no Shikimori id, no MAL
 * id, no IMDB id) — the canonical row created from a jut.su upsert is initially anchored only by
 * {@code (JUTSU, slug)}. Merging happens later when a Kodik upsert arrives carrying a Shikimori id
 * that resolves to the same canonical row.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JutsuCatalogIngestion {

    private final SourceEventEmitter emitter;
    private final OrinunoProperties properties;

    public void ingest(JutsuTitle title) {
        if (title == null || title.getSlug() == null || title.getSlug().isBlank()) {
            return;
        }
        if (!properties.getProviders().getJutsu().getSync().getCatalogIngestion().isEnabled()) {
            return;
        }
        SourceCatalogEvent event =
                new SourceCatalogEvent.TitleObserved(
                        SourceIdentifier.of("jutsu", title.getSlug()),
                        toContentInfo(title),
                        provenance(title));
        emitter.emit(event);
    }

    /**
     * Map a {@link JutsuTitle} into a {@link SourceContentInfo}. {@code titleRu} comes from
     * jut.su's primary {@code title} field (always Russian on jut.su); {@code titleEn} is the SDK's
     * {@code originalTitle} (English / original language). {@code kindHint} is hardcoded {@link
     * ContentKindHint#ANIME} because jut.su itself is an anime-only site.
     *
     * <p>{@code year} is parsed from {@code yearBucket}. jut.su exposes the bucket as a slug like
     * {@code "2020"}, {@code "before2000"}, or {@code "ongoing"}; we extract a parseable integer
     * when possible and leave {@code null} otherwise. The resolver's COALESCE-protected update
     * fills the canonical {@code year} only if it's currently null.
     */
    static SourceContentInfo toContentInfo(JutsuTitle title) {
        return SourceContentInfo.builder()
                .titleRu(title.getTitle())
                .titleEn(title.getOriginalTitle())
                .kindHint(ContentKindHint.ANIME)
                .year(parseYear(title.getYearBucket()))
                .externalIds(ExternalIds.empty())
                .build();
    }

    /**
     * Parse {@code "2020"} → 2020. Anything that's not a 4-digit year (jut.su's own buckets like
     * {@code "before2000"}, {@code "ongoing"}, NULL, blank) returns null and the canonical year
     * stays unset until a richer source provides one.
     */
    static Integer parseYear(String bucket) {
        if (bucket == null || bucket.isBlank()) return null;
        String trimmed = bucket.trim();
        if (trimmed.length() != 4) return null;
        for (int i = 0; i < trimmed.length(); i++) {
            if (!Character.isDigit(trimmed.charAt(i))) return null;
        }
        try {
            int parsed = Integer.parseInt(trimmed);
            if (parsed < 1900 || parsed > 2100) return null;
            return parsed;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Best-effort {@link Provenance} for a jut.su L1 upsert. {@code sourceUrl} points at the
     * title's anime-info page (the most informative URL we can derive without round-tripping back
     * to the SDK), {@code fetchedAt} comes from {@link JutsuTitle#getLastSeenAt()} when present,
     * otherwise {@code now}.
     */
    private static Provenance provenance(JutsuTitle title) {
        Instant fetchedAt =
                title.getLastSeenAt() != null
                        ? title.getLastSeenAt().atZone(ZoneOffset.UTC).toInstant()
                        : Instant.now();
        return Provenance.of("https://jut.su/" + title.getSlug() + "/", fetchedAt);
    }
}
