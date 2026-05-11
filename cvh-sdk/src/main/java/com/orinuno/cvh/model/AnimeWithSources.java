package com.orinuno.cvh.model;

import java.util.List;

/** Full pipeline output: host-page metadata + every track resolved to playable CDN URLs. */
public record AnimeWithSources(AnimeContent metadata, List<TrackWithSources> tracks) {

    public AnimeWithSources {
        tracks = tracks == null ? List.of() : List.copyOf(tracks);
    }
}
