package com.orinuno.cvh.downloader.hls;

import java.util.List;

/**
 * Result of resolving an HLS manifest down to a media playlist. {@link #manifestUrl()} is the URL
 * the manifest text was fetched from (used as the base for relative segment URIs and as the {@code
 * Referer} header on segment GETs); {@link #segmentUrls()} are absolutized media-segment URIs in
 * playback order.
 */
public record HlsMediaPlaylist(String manifestUrl, List<String> segmentUrls) {

    public HlsMediaPlaylist {
        segmentUrls = segmentUrls == null ? List.of() : List.copyOf(segmentUrls);
    }

    public boolean isEmpty() {
        return segmentUrls.isEmpty();
    }

    public int size() {
        return segmentUrls.size();
    }
}
