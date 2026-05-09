package com.orinuno.contract.source;

import jakarta.annotation.Nonnull;
import java.util.Locale;
import java.util.Objects;

/**
 * Per-source identity tuple: which source observed this title, and which raw id that source uses to
 * address it. Open-string {@code sourceType} is intentional — a closed enum here would force every
 * new source addition into a coordinated release of this artifact, which contradicts ADR 0017's
 * goal of letting the contract evolve independently of any one source SDK.
 *
 * <p>Canonical wire form is the lowercase source name: {@code "kodik"}, {@code "jutsu"}, {@code
 * "aniboom"}, {@code "sibnet"}, {@code "shikimori"}, … Implementations build this via {@link
 * #of(String, String)} which lowercases and trims defensively. The constructor still accepts any
 * non-blank pair so a future wire-form change does not break re-deserialisation of stored events.
 *
 * <p>{@code sourceId} is the source's own primary key for the row: a Kodik raw id like {@code
 * "movie-12345"}, a jut.su slug like {@code "naruto"}, an MAL numeric id, etc. Two events sharing
 * the same {@code (sourceType, sourceId)} pair refer to the same upstream row.
 */
public record SourceIdentifier(@Nonnull String sourceType, @Nonnull String sourceId) {

    public SourceIdentifier {
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(sourceId, "sourceId");
        if (sourceType.isBlank()) {
            throw new IllegalArgumentException("sourceType must not be blank");
        }
        if (sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
    }

    /**
     * Build a {@link SourceIdentifier} with the canonical lowercase wire form for {@code
     * sourceType}. {@code sourceId} is preserved verbatim (apart from trimming) — Kodik raw ids
     * sometimes carry case that's significant.
     */
    public static SourceIdentifier of(String sourceType, String sourceId) {
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(sourceId, "sourceId");
        return new SourceIdentifier(sourceType.trim().toLowerCase(Locale.ROOT), sourceId.trim());
    }
}
