package com.orinuno.sibnet;

/**
 * Stable error-code vocabulary returned by {@link SibnetClient#decode}. Kept as constants so
 * downstream code can switch on them without sprinkling string literals across the codebase.
 *
 * <p>Mirrors the strings the orinuno {@code provider-cdn-block.md} runbook references — do not
 * rename without updating both, operators grep these in alerts.
 */
public final class SibnetErrorCodes {

    /** Network error fetching the iframe (timeout, DNS, TLS, …). */
    public static final String SIBNET_FETCH_ERROR = "SIBNET_FETCH_ERROR";

    /**
     * The {@code player.src([{src:"…"}])} regex did not match. Means either an empty body or a
     * schema change on Sibnet's side — capture the response body and update the regex.
     */
    public static final String SIBNET_PLAYER_REGEX_BREAK = "SIBNET_PLAYER_REGEX_BREAK";

    /** Regex matched but the captured URL fragment was unparseable. */
    public static final String SIBNET_INVALID_SRC = "SIBNET_INVALID_SRC";

    /** Sibnet returned 404 — video is gone (deleted by uploader). Permanent, do not retry. */
    public static final String SIBNET_VIDEO_NOT_FOUND = "SIBNET_VIDEO_NOT_FOUND";

    private SibnetErrorCodes() {}
}
