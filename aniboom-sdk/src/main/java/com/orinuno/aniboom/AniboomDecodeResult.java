package com.orinuno.aniboom;

import jakarta.annotation.Nullable;
import java.util.Map;

/**
 * Result of one Aniboom embed decode attempt.
 *
 * <ul>
 *   <li>On success — {@link #success()} is {@code true}, {@link #qualities()} contains {@code
 *       "auto"} for the HLS master playlist and/or {@code "dash"} for the DASH manifest; {@link
 *       #format()} is {@code application/x-mpegURL} when HLS is present, else {@code
 *       application/dash+xml}.
 *   <li>On failure — {@link #qualities()} is empty, {@link #errorCode()} is one of {@link
 *       AniboomErrorCodes}.
 * </ul>
 *
 * <p>SDK-local mirror of orinuno-app's {@code ProviderDecodeResult} — kept separate on purpose so
 * the SDK does not depend on orinuno-app types.
 */
public record AniboomDecodeResult(
        boolean success,
        Map<String, String> qualities,
        @Nullable String format,
        @Nullable String errorCode) {

    public static AniboomDecodeResult success(Map<String, String> qualities, String format) {
        return new AniboomDecodeResult(true, Map.copyOf(qualities), format, null);
    }

    public static AniboomDecodeResult failure(String errorCode) {
        return new AniboomDecodeResult(false, Map.of(), null, errorCode);
    }
}
