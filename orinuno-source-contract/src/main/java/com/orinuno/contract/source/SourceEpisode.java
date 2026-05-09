package com.orinuno.contract.source;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * One episode within a season. Mirrors meter's {@code Episode} with the {@code filepath} → {@code
 * mediaUrl} rename (see {@link SourceEpisodeVariant}) and a relaxed contract: variants are non-null
 * but allowed to be empty for events where the source has identified the episode but not yet
 * decoded any playable URLs (e.g. a notice-feed observation on jut.su).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SourceEpisode(
        @Nullable String title,
        @Nullable String originalTitle,
        @Nullable String description,
        @Nullable Duration duration,
        @Nullable String mediaUrl,
        @Nullable String posterUrl,
        int order,
        @Nonnull List<SourceEpisodeVariant> variants) {

    public SourceEpisode {
        Objects.requireNonNull(variants, "variants");
        if (order < 0) {
            throw new IllegalArgumentException("order must be >= 0; got " + order);
        }
        variants = List.copyOf(variants);
    }
}
