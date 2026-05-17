package com.orinuno.meter.catalog.api;

import com.orinuno.meter.catalog.model.CatalogContent;
import com.orinuno.meter.catalog.model.CatalogContentExternalId;
import com.orinuno.meter.catalog.model.CatalogSourceType;
import java.util.List;
import java.util.Optional;

/**
 * Public surface of the {@code catalog} bounded context (ARCH-0016 P1b Step 1.B). The {@code
 * kodik}, {@code jutsu}, and any future source contexts call into the canonical layer
 * <strong>only</strong> through this interface — internal classes ({@code CatalogIdentityResolver},
 * the repositories, the type handlers) stay package-local. ArchUnit zoning rules enforce this in
 * P3.
 *
 * <p>Calls are expected to run inside the same transaction as the source upsert (no Rabbit / Kafka,
 * ADR 0016 §"What does NOT change"). Implementations must be idempotent: calling {@link
 * #findOrCreateContent(CatalogIdentityRequest)} twice with the same payload returns the same
 * canonical row and produces no extra side effects on the second call.
 */
public interface CatalogPublicApi {

    /**
     * Resolve the canonical {@link CatalogContent} for a per-source observation, creating one if
     * none exists yet.
     *
     * <p>Lookup order, mirroring {@code meter}'s {@code CatalogContentFindOrCreateService} but
     * stripped of consumer business logic: shikimori → mal → imdb → kinopoisk → mdl → tmdb →
     * (sourceType, sourceId). The first hit wins; the others are still attached as bindings to the
     * resolved canonical row, but never trigger a merge of two existing canonical rows (P1b "first
     * writer wins" tie-break — auto-merge is deferred to a later phase, see {@code TECH_DEBT.md}).
     *
     * <p>If none of the lookups hit, a fresh canonical row is inserted with the payload's chrome
     * (titleRu / titleEn / kind / year) and the corresponding identity columns populated from the
     * supplied external ids.
     *
     * <p>After the canonical row is identified, every external id from the request that doesn't
     * already point at a different canonical row is attached as a binding in {@code
     * catalog_content_external_id}. External-id conflicts (binding already points elsewhere) are
     * logged but never auto-resolved.
     */
    CatalogContent findOrCreateContent(CatalogIdentityRequest request);

    /**
     * Idempotently attach a single external id to an existing canonical row. Used by source
     * contexts that learn an additional external id <em>after</em> the initial canonical row is
     * resolved (e.g. a Kodik enrichment job that resolves the Shikimori id later).
     *
     * <p>Returns the existing or freshly-inserted binding. If the binding already points at a
     * different canonical row, returns the conflicting binding unchanged — caller decides whether
     * to log, no-op, or escalate.
     */
    CatalogContentExternalId attachExternalId(
            long contentId, CatalogSourceType sourceType, String externalId);

    /**
     * Look up a canonical row by id (read-only path used by the {@code core} context's
     * orchestrators when they hold a canonical id but need to materialise the row).
     */
    Optional<CatalogContent> findContentById(long contentId);

    /**
     * Look up a canonical row by an external-id binding. Convenience over {@link
     * #findContentById(long)} — equivalent to the {@code (sourceType, externalId)} unique-index
     * lookup followed by {@code findById(...)}.
     */
    Optional<CatalogContent> findContentByExternalId(
            CatalogSourceType sourceType, String externalId);

    /** Read every external-id binding attached to {@code contentId}. */
    List<CatalogContentExternalId> findAttachedExternalIds(long contentId);
}
