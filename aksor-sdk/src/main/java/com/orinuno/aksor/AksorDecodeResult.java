package com.orinuno.aksor;

import com.orinuno.aksor.model.AksorAnime;
import jakarta.annotation.Nullable;

/**
 * Result of one Aksor pipeline decode.
 *
 * <ul>
 *   <li>On success — {@link #success()} is {@code true}, {@link #value()} carries the full {@link
 *       AksorAnime} with every episode's qualities resolved.
 *   <li>On failure — {@link #value()} is {@code null} and {@link #errorCode()} is one of the
 *       constants in {@link AksorErrorCodes}.
 * </ul>
 */
public record AksorDecodeResult(
        boolean success, @Nullable AksorAnime value, @Nullable String errorCode) {

    public static AksorDecodeResult success(AksorAnime value) {
        return new AksorDecodeResult(true, value, null);
    }

    public static AksorDecodeResult failure(String errorCode) {
        return new AksorDecodeResult(false, null, errorCode);
    }
}
