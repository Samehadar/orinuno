package com.orinuno.cvh;

import com.orinuno.cvh.model.AnimeWithSources;
import jakarta.annotation.Nullable;

/**
 * Result of one CVH pipeline decode.
 *
 * <ul>
 *   <li>On success — {@link #success()} is {@code true}, {@link #value()} carries the full {@link
 *       AnimeWithSources} payload, {@link #errorCode()} is {@code null}.
 *   <li>On failure — {@link #value()} is {@code null}, {@link #errorCode()} is one of the constants
 *       in {@link CvhErrorCodes}.
 * </ul>
 */
public record CvhDecodeResult(
        boolean success, @Nullable AnimeWithSources value, @Nullable String errorCode) {

    public static CvhDecodeResult success(AnimeWithSources value) {
        return new CvhDecodeResult(true, value, null);
    }

    public static CvhDecodeResult failure(String errorCode) {
        return new CvhDecodeResult(false, null, errorCode);
    }
}
