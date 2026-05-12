package com.orinuno.cvh.downloader.hls;

import java.util.List;
import java.util.OptionalDouble;

/**
 * Result of resolving an HLS manifest down to a media playlist. {@link #manifestUrl()} is the URL
 * the manifest text was fetched from (used as the base for relative segment URIs and as the {@code
 * Referer} header on segment GETs); {@link #segments()} are absolutized media-segment entries in
 * playback order, each carrying an optional {@code #EXTINF} duration.
 */
public record HlsMediaPlaylist(String manifestUrl, List<HlsSegment> segments) {

    public HlsMediaPlaylist {
        segments = segments == null ? List.of() : List.copyOf(segments);
    }

    /** Derived view over {@link #segments()} — absolutized URIs in playback order. */
    public List<String> segmentUrls() {
        return segments.stream().map(HlsSegment::url).toList();
    }

    /**
     * Sum of {@code #EXTINF} durations across segments, in seconds. Returns empty when no segment
     * carried a parseable duration (e.g., malformed playlist or one that omitted #EXTINF entirely).
     * Used for progress ETA without needing per-segment HEAD requests.
     */
    public OptionalDouble totalDurationSeconds() {
        double sum = 0d;
        boolean any = false;
        for (HlsSegment s : segments) {
            if (s.durationSeconds() != null) {
                sum += s.durationSeconds();
                any = true;
            }
        }
        return any ? OptionalDouble.of(sum) : OptionalDouble.empty();
    }

    public boolean isEmpty() {
        return segments.isEmpty();
    }

    public int size() {
        return segments.size();
    }
}
