package com.orinuno.cvh.model;

import jakarta.annotation.Nullable;
import java.time.Instant;

/**
 * Signed CDN URLs returned by {@code /api/v1/player/sv/video/{vkId}}.
 *
 * <p>{@code expiresAt} is parsed out of the {@code expires=} query parameter of the HLS URL (Unix
 * millis). All URLs share the same expiry — the cache keys off this value to refresh before TTL.
 *
 * <p>Field naming intentionally collapses the awkward {@code mpegXxxUrl} response shape into
 * resolution-tagged aliases. {@code mp4Quad} / {@code mp4Two_k} / {@code mp4Four_k} are present in
 * the wire schema but typically empty; they are intentionally not exposed yet.
 */
public record CvhVideoSources(
        @Nullable Long vkId,
        @Nullable Integer durationSec,
        @Nullable String thumbnailUrl,
        @Nullable String hlsUrl,
        @Nullable String dashUrl,
        @Nullable String mp4_1080p,
        @Nullable String mp4_720p,
        @Nullable String mp4_480p,
        @Nullable String mp4_360p,
        @Nullable String mp4_240p,
        @Nullable String mp4_144p,
        @Nullable Instant expiresAt) {}
