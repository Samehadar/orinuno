package com.orinuno.aksor.model;

import jakarta.annotation.Nullable;

/**
 * MPEG-DASH manifest URLs the Aksor player API returns per episode. In practice only {@code q1080}
 * is populated today; the other slots are kept for forward compatibility.
 */
public record AksorVideoQualities(
        @Nullable String q1080,
        @Nullable String q720,
        @Nullable String q480,
        @Nullable String q360,
        @Nullable String q2k,
        @Nullable String q4k) {

    /** Best-available MPD URL: 4k → 2k → 1080p → 720p → 480p → 360p. Null if all slots blank. */
    @Nullable
    public String bestAvailable() {
        if (q4k != null && !q4k.isBlank()) return q4k;
        if (q2k != null && !q2k.isBlank()) return q2k;
        if (q1080 != null && !q1080.isBlank()) return q1080;
        if (q720 != null && !q720.isBlank()) return q720;
        if (q480 != null && !q480.isBlank()) return q480;
        if (q360 != null && !q360.isBlank()) return q360;
        return null;
    }

    public boolean isEmpty() {
        return bestAvailable() == null;
    }
}
