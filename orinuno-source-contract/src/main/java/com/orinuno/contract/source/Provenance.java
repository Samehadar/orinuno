package com.orinuno.contract.source;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Provenance metadata attached to every {@link SourceCatalogEvent}. Records *where* the data came
 * from and *how* it was parsed so downstream consumers can reason about freshness, drift, and
 * parser-mode-specific gotchas without having to instrument the source contexts.
 *
 * <p>Out-of-tree downstream consumers may swallow drift internally and drop the whole record on
 * translation. OSS L3 ingestion / drift dashboards persist them.
 *
 * <p>{@code parserMode} is intentionally a free-form string ("lenient" / "strict" for jut.su per
 * ADR 0015, but future sources may add their own modes). {@code schemaDriftFlags} is a list of
 * opaque tags — consumers treat them as observable strings, not as a closed enum.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Provenance(
        @Nonnull String sourceUrl,
        @Nonnull Instant fetchedAt,
        @Nullable String sdkVersion,
        @Nullable String parserMode,
        @Nullable List<String> schemaDriftFlags) {

    public Provenance {
        Objects.requireNonNull(sourceUrl, "sourceUrl");
        Objects.requireNonNull(fetchedAt, "fetchedAt");
        if (sourceUrl.isBlank()) {
            throw new IllegalArgumentException("sourceUrl must not be blank");
        }
        if (schemaDriftFlags != null) {
            schemaDriftFlags = List.copyOf(schemaDriftFlags);
        }
    }

    /**
     * Lightweight factory for events whose ingestion path doesn't (yet) populate {@code sdkVersion}
     * or {@code parserMode} — the L1 → L3 hand-off inside {@code orinuno-app} uses this to keep the
     * existing {@code KodikContent}/{@code JutsuTitle} call sites narrow.
     */
    public static Provenance of(String sourceUrl, Instant fetchedAt) {
        return new Provenance(sourceUrl, fetchedAt, null, null, null);
    }
}
