package com.orinuno.aniboom;

/**
 * Stable error-code vocabulary returned by {@link AniboomClient#decode}. Operators grep these in
 * alerts; do not rename without updating {@code provider-cdn-block.md} and {@code
 * quirks-and-hacks.md} in orinuno's repo.
 */
public final class AniboomErrorCodes {

    /** Network error fetching the embed page (timeout, DNS, TLS, …). */
    public static final String ANIBOOM_FETCH_ERROR = "ANIBOOM_FETCH_ERROR";

    /** Page returned but the {@code <input id="video-data" data-parameters="…">} blob is absent. */
    public static final String ANIBOOM_DATA_INPUT_MISSING = "ANIBOOM_DATA_INPUT_MISSING";

    /**
     * Blob is present but empty ({@code "{}"}). Aniboom returns this when the request hits from a
     * blocked geo (most non-CIS egress) — switch to a CIS exit.
     */
    public static final String ANIBOOM_GEO_BLOCKED = "ANIBOOM_GEO_BLOCKED";

    /**
     * {@code data-parameters} contained malformed JSON — likely a schema change on Aniboom's side.
     */
    public static final String ANIBOOM_JSON_PARSE_ERROR = "ANIBOOM_JSON_PARSE_ERROR";

    /** JSON parsed but neither {@code hls} nor {@code dash} key was present. */
    public static final String ANIBOOM_NO_PLAYLIST = "ANIBOOM_NO_PLAYLIST";

    private AniboomErrorCodes() {}
}
