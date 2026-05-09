package com.orinuno.contract.source;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.time.Duration;
import java.util.Objects;

/**
 * One playable variant for an episode (e.g. one translation track for a Kodik episode, one quality
 * level for a sibnet upload, one direct mp4 link). Mirrors meter's {@code EpisodeVariant} with the
 * consumer-coupled bits replaced:
 *
 * <ul>
 *   <li>{@code identifier} stays the same shape (per-source key) but uses {@link SourceIdentifier}
 *       (open-string source type) instead of meter's closed {@code SourceType}.
 *   <li>{@code mediaUrl} replaces meter's {@code filepath} — meaning is the same (a URL/path that a
 *       player can resolve), but the field name no longer implies a MinIO key. the external aggregator's{@code
 *       external bridge} translates {@code mediaUrl} → {@code filepath} on its side.
 *   <li>{@code streamQuality} is a plain string ({@code "1080p"}, {@code "HD"}, …) instead of
 *       meter's closed {@code StreamQuality} enum.
 * </ul>
 *
 * <p>{@code mediaUrl} is currently the only non-nullable field beyond {@code identifier} — variants
 * that arrive without a playable URL should not be emitted as variants in the first place; emit a
 * {@link SourceCatalogEvent.TitleObserved} instead.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SourceEpisodeVariant(
        @Nonnull SourceIdentifier identifier,
        @Nonnull String mediaUrl,
        @Nullable String title,
        @Nullable String streamQuality,
        @Nullable Duration duration,
        @Nullable String previewImageUrl) {

    public SourceEpisodeVariant {
        Objects.requireNonNull(identifier, "identifier");
        Objects.requireNonNull(mediaUrl, "mediaUrl");
        if (mediaUrl.isBlank()) {
            throw new IllegalArgumentException("mediaUrl must not be blank");
        }
    }
}
