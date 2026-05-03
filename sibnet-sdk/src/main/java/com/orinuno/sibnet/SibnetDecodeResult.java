package com.orinuno.sibnet;

import jakarta.annotation.Nullable;
import java.util.Map;

/**
 * Result of one Sibnet shell.php decode attempt.
 *
 * <ul>
 *   <li>On success — {@link #success()} is {@code true}, {@link #qualities()} contains exactly one
 *       entry keyed by {@code "720"} (Sibnet does not expose multi-quality), {@link #format()} is
 *       {@code video/mp4}.
 *   <li>On failure — {@link #qualities()} is empty, {@link #errorCode()} is one of {@link
 *       SibnetErrorCodes}.
 * </ul>
 *
 * <p>SDK-local mirror of orinuno-app's {@code ProviderDecodeResult} — kept separate on purpose so
 * the SDK does not depend on orinuno-app types.
 */
public record SibnetDecodeResult(
        boolean success,
        Map<String, String> qualities,
        @Nullable String format,
        @Nullable String errorCode) {

    public static SibnetDecodeResult success(Map<String, String> qualities, String format) {
        return new SibnetDecodeResult(true, Map.copyOf(qualities), format, null);
    }

    public static SibnetDecodeResult failure(String errorCode) {
        return new SibnetDecodeResult(false, Map.of(), null, errorCode);
    }
}
