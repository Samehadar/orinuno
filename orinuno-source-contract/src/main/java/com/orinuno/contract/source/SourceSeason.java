package com.orinuno.contract.source;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Objects;

/**
 * One season inside a series-shaped event ({@link SourceCatalogEvent.SeriesDiscovered} / {@link
 * SourceCatalogEvent.EpisodesUpdated}). Mirrors meter's {@code Season} record verbatim.
 *
 * <p>Order semantics match meter: {@code order = 0} is reserved for film-shaped content (collapsed
 * by movie events into a one-season-one-episode tuple). Series-shaped events use {@code order >=
 * 1}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SourceSeason(
        @Nullable String title,
        @Nullable String originalTitle,
        @Nullable String description,
        int order,
        @Nonnull List<SourceEpisode> episodes) {

    public SourceSeason {
        Objects.requireNonNull(episodes, "episodes");
        if (order < 0) {
            throw new IllegalArgumentException("order must be >= 0; got " + order);
        }
        episodes = List.copyOf(episodes);
    }
}
