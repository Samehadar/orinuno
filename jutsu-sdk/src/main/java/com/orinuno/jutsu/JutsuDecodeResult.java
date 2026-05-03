package com.orinuno.jutsu;

import jakarta.annotation.Nullable;
import java.util.Map;

/**
 * Result of one episode-page decode attempt.
 *
 * <ul>
 *   <li>On success — {@link #success()} is {@code true}, {@link #qualities()} contains the
 *       quality-bucket → mp4-URL map (typically {@code 360}, {@code 480}, {@code 720}, {@code
 *       1080}, sometimes {@code 2160}), and {@link #format()} is {@code video/mp4}.
 *   <li>On failure — {@link #success()} is {@code false}, {@link #qualities()} is an empty map,
 *       {@link #errorCode()} carries one of the {@link JutsuErrorCodes} constants.
 * </ul>
 *
 * <p>This is an SDK-local mirror of orinuno's {@code ProviderDecodeResult}. We deliberately keep a
 * separate copy in the SDK so the SDK has zero compile-time coupling to orinuno-app — see the
 * "duplication tax" rationale in the API/module split notes (no shared SPI module).
 */
public record JutsuDecodeResult(
        boolean success,
        Map<String, String> qualities,
        @Nullable String format,
        @Nullable String errorCode) {

    public static JutsuDecodeResult success(Map<String, String> qualities, String format) {
        return new JutsuDecodeResult(true, Map.copyOf(qualities), format, null);
    }

    public static JutsuDecodeResult failure(String errorCode) {
        return new JutsuDecodeResult(false, Map.of(), null, errorCode);
    }
}
