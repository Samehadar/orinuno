package com.orinuno.service.provider;

import java.util.Map;

/**
 * Result of a provider-specific decode attempt. Carries either:
 *
 * <ul>
 *   <li>{@code qualities} — non-empty map of quality bucket → URL on success
 *   <li>{@code errorCode} — provider-specific failure code on failure (e.g. {@code
 *       SIBNET_VIDEO_NOT_FOUND}, {@code ANIBOOM_GEO_BLOCKED}, {@code JUTSU_PREMIUM_REQUIRED})
 * </ul>
 *
 * <p>{@link #format} is the MIME type hint we persist to {@code episode_video.video_format}.
 */
public record ProviderDecodeResult(
        boolean success, Map<String, String> qualities, String format, String errorCode) {

    public static ProviderDecodeResult success(Map<String, String> qualities, String format) {
        return new ProviderDecodeResult(true, qualities, format, null);
    }

    public static ProviderDecodeResult failure(String errorCode) {
        return new ProviderDecodeResult(false, Map.of(), null, errorCode);
    }
}
