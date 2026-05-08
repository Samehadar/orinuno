package com.orinuno.catalog.model;

import java.util.Optional;

/**
 * Enumerates the kinds of external ids that can be attached to a canonical {@link CatalogContent}
 * row through {@code catalog_content_external_id} (ARCH-0016 P1b).
 *
 * <p>Two flavours coexist intentionally:
 *
 * <ul>
 *   <li><strong>Source contexts</strong> ({@link #KODIK}, {@link #JUTSU}, ...) — the per-source L1
 *       cache key. Lets the canonical resolver answer "do we already have a canonical row for Kodik
 *       raw id {@code abc123}?" without scanning {@code kodik_content}.
 *   <li><strong>External databases</strong> ({@link #SHIKIMORI}, {@link #MAL}, {@link #IMDB},
 *       {@link #KINOPOISK}, {@link #MDL}, {@link #TMDB}) — third-party identifiers harvested from
 *       source metadata. Used by {@code CatalogIdentityResolver} (P1b Step 1.B) to merge entries
 *       from multiple sources into one canonical row.
 * </ul>
 *
 * <p>The wire format on the {@code source_type} column is the lowercase enum name (e.g. {@code
 * kodik}, {@code shikimori}). The conversion is intentionally lossy on the read side — unknown
 * values from the database surface as {@link Optional#empty()} rather than throwing, so a future
 * source addition without a code deploy doesn't crash the resolver.
 */
public enum CatalogSourceType {
    KODIK,
    JUTSU,
    SHIKIMORI,
    MAL,
    KINOPOISK,
    IMDB,
    MDL,
    TMDB;

    /**
     * Returns the lowercase wire form persisted to {@code catalog_content_external_id.source_type}.
     */
    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Tolerant reverse mapping from the wire form. Unknown values become {@link Optional#empty()}
     * so the resolver can skip them without exploding.
     */
    public static Optional<CatalogSourceType> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    /**
     * True if this type is a third-party database id (Shikimori / MAL / Kinopoisk / IMDB / MDL /
     * TMDB) rather than a per-source L1 key. Drives the resolver's lookup priority — external db
     * ids merge equally-typed bindings across sources, source-context ids do not.
     */
    public boolean isExternalDatabase() {
        return switch (this) {
            case SHIKIMORI, MAL, KINOPOISK, IMDB, MDL, TMDB -> true;
            case KODIK, JUTSU -> false;
        };
    }
}
