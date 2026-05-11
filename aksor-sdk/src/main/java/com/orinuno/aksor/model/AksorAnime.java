package com.orinuno.aksor.model;

import jakarta.annotation.Nullable;
import java.util.List;

/**
 * Aksor-relevant metadata for one anime title, populated by a host page parser. {@code animeId} is
 * the host's internal numeric id (e.g. yummyani.me's {@code data-id="10531"}). {@code episodes}
 * lists every episode known to embed the Aksor player; each {@link AksorEpisode} carries its hash
 * but may have a {@code null} {@code qualities} block until the pipeline resolves it through the
 * Aksor player API.
 */
public record AksorAnime(
        @Nullable String animeId,
        @Nullable String slug,
        @Nullable String pageUrl,
        @Nullable String title,
        @Nullable String posterUrl,
        List<AksorEpisode> episodes) {

    public AksorAnime {
        episodes = episodes == null ? List.of() : List.copyOf(episodes);
    }
}
