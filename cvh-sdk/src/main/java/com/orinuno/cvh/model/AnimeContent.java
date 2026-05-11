package com.orinuno.cvh.model;

import jakarta.annotation.Nullable;
import java.util.List;

/**
 * Host-page metadata + CVH player attributes. Produced by a {@link
 * com.orinuno.cvh.host.CvhHostPageParser} from the raw HTML of one host title page.
 *
 * <p>{@code cvhTitleId} / {@code cvhPublisherId} / {@code cvhAggregator} drive the first plapi hop.
 * If {@code cvhTitleId} is {@code null}, the page does not embed CVH and the pipeline returns the
 * metadata with empty tracks.
 *
 * <p>{@code kodikIframeSrc} is jut-su-specific (some pages also expose a Kodik fallback). Other
 * hosts may leave it {@code null}.
 */
public record AnimeContent(
        @Nullable String slug,
        @Nullable String pageUrl,
        @Nullable String title,
        @Nullable String titleOriginal,
        @Nullable String description,
        List<String> genres,
        @Nullable String releaseDate,
        @Nullable String country,
        @Nullable String posterUrl,
        @Nullable RatingInfo rating,
        @Nullable String cvhTitleId,
        @Nullable String cvhPublisherId,
        @Nullable String cvhAggregator,
        @Nullable String cvhPriorityVoice,
        @Nullable String kodikIframeSrc) {

    public AnimeContent {
        genres = genres == null ? List.of() : List.copyOf(genres);
    }
}
