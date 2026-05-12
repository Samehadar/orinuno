package com.orinuno.cvh.downloader.hls;

import jakarta.annotation.Nullable;

/**
 * A media-segment entry inside a resolved {@link HlsMediaPlaylist}. {@link #url()} is the
 * absolutized GET target; {@link #durationSeconds()} is the value from the segment's preceding
 * {@code #EXTINF} header, or {@code null} when the playlist omitted/malformed the duration. Sum
 * across a playlist via {@link HlsMediaPlaylist#totalDurationSeconds()} for ETA / progress UI.
 */
public record HlsSegment(String url, @Nullable Double durationSeconds) {

    public HlsSegment {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("HlsSegment.url must be non-blank");
        }
    }
}
