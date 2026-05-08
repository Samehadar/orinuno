package com.orinuno.catalog.model;

import java.util.Optional;

/**
 * Coarse classification of a canonical content row (ARCH-0016 P1b — L3). Stored as the lowercase
 * wire string in {@code catalog_content.kind}.
 *
 * <p>The vocabulary is deliberately small. Per-source rows ({@code kodik_content}, {@code
 * jutsu_title}) carry richer type taxonomies (foreign-cinema, anime-serial, full-length, ...);
 * those are useful for source-level filters but the canonical layer collapses them to one of three
 * buckets. {@link #UNKNOWN} exists for forward-compat with an external value the database still has
 * but the current code doesn't recognise.
 */
public enum CatalogContentKind {
    MOVIE,
    SERIES,
    ANIME,
    UNKNOWN;

    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static CatalogContentKind fromWire(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }

    public static Optional<CatalogContentKind> tryFromWire(String value) {
        CatalogContentKind kind = fromWire(value);
        return kind == UNKNOWN ? Optional.empty() : Optional.of(kind);
    }
}
