package com.orinuno.jutsu.sync;

import com.orinuno.catalog.api.CatalogIdentityRequest;
import com.orinuno.catalog.api.CatalogPublicApi;
import com.orinuno.catalog.model.CatalogContentKind;
import com.orinuno.catalog.model.CatalogSourceType;
import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.jutsu.model.JutsuTitle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Bridge between the jut.su L1 cache and the L3 universal canonical catalog (ARCH-0016 P1b Step
 * 1.C). This is the only class in the {@code jutsu} bounded context that ever reaches into {@code
 * com.orinuno.catalog.api.*} — it lives here, not in the {@code catalog} package, because ADR 0016
 * zoning rules go one direction: {@code jutsu} depends on {@code catalog}, never the other way
 * around.
 *
 * <p>Each {@link JutsuTitle} upsert in {@link JutsuCatalogSyncService} (full crawl, notice walk
 * info-fetch, notice walk placeholder) calls {@link #ingest(JutsuTitle)} synchronously. The
 * resolver inside {@link CatalogPublicApi} runs {@code @Transactional} so a partial commit
 * (canonical row inserted, binding insert failed, etc.) rolls back without touching the L1 upsert
 * that already succeeded one transaction earlier.
 *
 * <p>Failure isolation: any {@link RuntimeException} from the resolver is caught and logged at
 * WARN. The L1 sync is the system of record for "we observed this title on jut.su today" — it must
 * not break because L3 binding produced a transient deadlock or the resolver hit a not-yet-fixed
 * bug. A subsequent tick re-attempts the binding (idempotent by design).
 *
 * <p>jut.su's {@link JutsuTitle} carries no third-party identifiers (no Shikimori id, no MAL id, no
 * IMDB id) — the SDK doesn't extract them today. So the canonical row created from a jut.su upsert
 * is initially anchored only by {@code (JUTSU, slug)}. Merging with rows from Kodik (the Kodik
 * bridge that lands in Step 1.C.B) happens later when the Kodik upsert calls {@code
 * findOrCreateContent} carrying a {@code shikimori_id} that resolves to the same canonical row.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JutsuCatalogIngestion {

    private final CatalogPublicApi catalog;
    private final OrinunoProperties properties;

    public void ingest(JutsuTitle title) {
        if (title == null || title.getSlug() == null || title.getSlug().isBlank()) {
            return;
        }
        if (!properties.getProviders().getJutsu().getSync().getCatalogIngestion().isEnabled()) {
            return;
        }
        try {
            CatalogIdentityRequest request = toRequest(title);
            catalog.findOrCreateContent(request);
        } catch (RuntimeException ex) {
            log.warn(
                    "jutsu-sync: catalog ingestion for slug='{}' failed ({}: {}); L1 row stays"
                            + " untouched, will retry on next tick",
                    title.getSlug(),
                    ex.getClass().getSimpleName(),
                    ex.getMessage());
        }
    }

    /**
     * Map a {@link JutsuTitle} into a {@link CatalogIdentityRequest}. {@code titleRu} comes from
     * jut.su's primary {@code title} field (always Russian on jut.su); {@code titleEn} is the SDK's
     * {@code originalTitle} (English / original language). {@code kind} is hardcoded {@link
     * CatalogContentKind#ANIME} because jut.su itself is an anime-only site — every row in {@code
     * jutsu_title} is an anime by definition.
     *
     * <p>{@code year} is parsed from {@code yearBucket}. jut.su exposes the bucket as a slug like
     * {@code "2020"}, {@code "before2000"}, or {@code "ongoing"}; we extract a parseable integer
     * when possible and leave {@code null} otherwise. The resolver's COALESCE-protected update
     * fills the canonical {@code year} only if it's currently null.
     */
    static CatalogIdentityRequest toRequest(JutsuTitle title) {
        return CatalogIdentityRequest.builder(CatalogSourceType.JUTSU, title.getSlug())
                .titleRu(title.getTitle())
                .titleEn(title.getOriginalTitle())
                .kind(CatalogContentKind.ANIME)
                .year(parseYear(title.getYearBucket()))
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
}
