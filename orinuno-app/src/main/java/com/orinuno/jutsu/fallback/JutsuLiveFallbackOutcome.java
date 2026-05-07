package com.orinuno.jutsu.fallback;

/** Tag values for the {@code jutsu_live_fallback_total} Micrometer counter. */
public enum JutsuLiveFallbackOutcome {
    HIT,
    MISS,
    UPSTREAM_ERROR,
    RATE_LIMITED,
    DISABLED,
    NEGATIVE_CACHE;

    public String tag() {
        return name().toLowerCase();
    }
}
