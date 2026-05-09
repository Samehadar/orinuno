package com.orinuno.contract.source;

import java.util.Locale;

/**
 * Source-level coarse hint about what kind of content was observed. Maps 1:1 to the canonical
 * catalog's {@code CatalogContentKind} on the consumer side, but lives here so the contract
 * artifact stays free of catalog-internal types.
 *
 * <p>{@link #UNKNOWN} is the default when the source cannot classify the row (Kodik returns a blank
 * {@code type}, jut.su's catalog crawl that hasn't fetched the info page yet, …). Consumers must
 * treat {@code UNKNOWN} as "leave the canonical kind alone" — never as "downgrade to UNKNOWN" —
 * because a richer source observation may have already classified this row correctly.
 */
public enum ContentKindHint {
    MOVIE,
    SERIES,
    ANIME,
    UNKNOWN;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ContentKindHint fromWire(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }
}
