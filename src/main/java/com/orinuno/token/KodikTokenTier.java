package com.orinuno.token;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Tier of a Kodik token. Mirrors AnimeParsers' {@code kdk_tokns/tokens.json} top-level keys.
 *
 * <ul>
 *   <li>{@link #STABLE} — fully working token that, by observation, rotates rarely.
 *   <li>{@link #UNSTABLE} — fully working but rotates more often than stable.
 *   <li>{@link #LEGACY} — partial scope (typically only {@code get_info} / {@code get_link} /
 *       {@code get_m3u8_playlist_link}).
 *   <li>{@link #DEAD} — retired; kept for audit so we don't re-add a known-bad token.
 * </ul>
 */
public enum KodikTokenTier {
    STABLE("stable"),
    UNSTABLE("unstable"),
    LEGACY("legacy"),
    DEAD("dead");

    private final String jsonKey;

    KodikTokenTier(String jsonKey) {
        this.jsonKey = jsonKey;
    }

    @JsonValue
    public String getJsonKey() {
        return jsonKey;
    }

    @JsonCreator
    public static KodikTokenTier fromJsonKey(String key) {
        for (KodikTokenTier tier : values()) {
            if (tier.jsonKey.equals(key)) {
                return tier;
            }
        }
        throw new IllegalArgumentException("Unknown Kodik token tier: " + key);
    }
}
