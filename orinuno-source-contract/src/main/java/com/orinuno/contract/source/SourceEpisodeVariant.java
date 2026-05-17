package com.orinuno.contract.source;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.time.Duration;
import java.util.Objects;

/**
 * One playable variant for an episode (e.g. one translation track for a Kodik episode, one quality
 * level for a sibnet upload, one direct mp4 link). Consumer-neutral shape:
 *
 * <ul>
 *   <li>{@code identifier} is the per-source key wrapped in {@link SourceIdentifier} with an
 *       open-string source type, so new sources can be added without recompiling the contract.
 *   <li>{@code mediaUrl} is the playable URL/path the consumer should hand to its player or
 *       download pipeline. Downstream aggregators that store media in their own object store
 *       translate {@code mediaUrl} → their internal filepath shape on the consumer side.
 *   <li>{@code streamQuality} is a plain string ({@code "1080p"}, {@code "HD"}, …) instead of a
 *       closed enum.
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
