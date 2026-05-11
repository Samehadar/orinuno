package com.orinuno.cvh.downloader.candidate;

import jakarta.annotation.Nullable;

/**
 * One playable URL the downloader can try. {@link #quality()} is only populated for {@link
 * DownloadFormat#MP4_DIRECT}; HLS and DASH variants are quality-agnostic at this level (the variant
 * within the master playlist drives that).
 */
public record DownloadCandidate(String url, DownloadFormat format, @Nullable Mp4Quality quality) {

    public DownloadCandidate {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url must not be blank");
        }
        if (format == null) {
            throw new IllegalArgumentException("format must not be null");
        }
        if (format != DownloadFormat.MP4_DIRECT && quality != null) {
            throw new IllegalArgumentException(
                    "quality is only meaningful for MP4_DIRECT, got format=" + format);
        }
    }

    public static DownloadCandidate mp4(String url, Mp4Quality quality) {
        return new DownloadCandidate(url, DownloadFormat.MP4_DIRECT, quality);
    }

    public static DownloadCandidate hls(String url) {
        return new DownloadCandidate(url, DownloadFormat.HLS, null);
    }

    public static DownloadCandidate dash(String url) {
        return new DownloadCandidate(url, DownloadFormat.DASH, null);
    }
}
