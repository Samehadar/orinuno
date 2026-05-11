package com.orinuno.aksor.model;

import jakarta.annotation.Nullable;

/**
 * One episode as reported by the host page's videos API, optionally enriched with its resolved
 * {@link AksorVideoQualities} once {@code AksorApiClient} has been called for its {@code hash}.
 *
 * @param videoId host-side numeric id
 * @param number "1" / "2" / ... (some hosts use "1.5" — keep as String)
 * @param dubbing dubbing studio name (e.g. {@code "Озвучка AniLibria"}); free-form
 * @param player player name (e.g. {@code "Плеер Aksor"})
 * @param hash 32-char identifier inside Aksor's player iframe URL
 * @param iframeUrl original {@code https://player.aksor.tv/video/<hash>} link from the host
 * @param durationSec episode duration in seconds when reported
 * @param opening skip mark for the opening; nullable
 * @param ending skip mark for the ending; nullable
 * @param qualities {@code null} until the Aksor player API is called for this episode
 */
public record AksorEpisode(
        @Nullable Long videoId,
        @Nullable String number,
        @Nullable String dubbing,
        @Nullable String player,
        String hash,
        @Nullable String iframeUrl,
        @Nullable Integer durationSec,
        @Nullable AksorSkipMark opening,
        @Nullable AksorSkipMark ending,
        @Nullable AksorVideoQualities qualities) {

    public AksorEpisode {
        if (hash == null || hash.isBlank()) {
            throw new IllegalArgumentException("hash must not be blank");
        }
    }

    public AksorEpisode withQualities(AksorVideoQualities qualities) {
        return new AksorEpisode(
                videoId,
                number,
                dubbing,
                player,
                hash,
                iframeUrl,
                durationSec,
                opening,
                ending,
                qualities);
    }
}
