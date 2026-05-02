package com.kodik.sdk.drift;

/**
 * Strategy for how many items inside a paginated {@code results} array to inspect during drift
 * detection. Trade-off: deeper sampling catches drift that only manifests on certain content types
 * (drama-only fields among anime, etc.) but costs more CPU per response.
 */
public enum ItemSamplingMode {
    NONE,
    FIRST_N,
    ALL
}
