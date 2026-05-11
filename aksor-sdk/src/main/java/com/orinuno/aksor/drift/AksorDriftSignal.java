package com.orinuno.aksor.drift;

/**
 * Stable identifiers for the kinds of schema drift we recognise on Aksor / its host pages.
 * Operators grep these in alerts; do not rename without updating drift dashboards.
 */
public enum AksorDriftSignal {

    /** yummyani page HTML has no {@code [data-id]} element with a numeric value. */
    YUMMY_PAGE_NO_ANIME_ID,

    /**
     * yummyani {@code /api/anime/{id}/videos} response is not a JSON object with {@code response}
     * array.
     */
    YUMMY_VIDEOS_RESPONSE_NOT_ARRAY,

    /** yummyani videos entry passed the player filter but no 32-hex hash could be extracted. */
    YUMMY_EPISODE_NO_HASH,

    /**
     * yummyani videos entry has unknown {@code data.player} value (neither Aksor nor a known peer).
     */
    YUMMY_EPISODE_UNKNOWN_PLAYER,

    /** {@code player.aksor.tv/api/video/{hash}} body lacks the {@code qualities} node. */
    AKSOR_QUALITIES_MISSING,

    /** {@code qualities} node present but every slot ({@code q1080..q4k}) is null/blank. */
    AKSOR_QUALITIES_ALL_NULL
}
