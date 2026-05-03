package com.orinuno.jutsu;

/**
 * Stable set of error codes the JutSu decoder can return. Codes are defined here as constants so
 * downstream code can switch on them without sprinkling string literals across the codebase.
 *
 * <p>Mirrors the strings the orinuno {@code ProvidersController} / runbook {@code
 * provider-cdn-block.md} document. Do not rename without updating both — operators rely on grepping
 * these in alerts.
 */
public final class JutsuErrorCodes {

    /** Network error fetching the episode page (timeout, DNS, TLS, …). */
    public static final String JUTSU_FETCH_ERROR = "JUTSU_FETCH_ERROR";

    /** Response body was empty — usually upstream returned {@code 204} or a truncated stream. */
    public static final String JUTSU_EMPTY_RESPONSE = "JUTSU_EMPTY_RESPONSE";

    /**
     * Cloudflare's "Just a moment…" / browser-verification challenge intercepted the request.
     * Operator action: rotate egress IP or wait for the challenge cookie to expire upstream.
     */
    public static final String JUTSU_CLOUDFLARE_BLOCKED = "JUTSU_CLOUDFLARE_BLOCKED";

    /**
     * Episode requires a {@code Jutsu+} subscription. Either no credentials were configured or the
     * cached session was rejected by jut.su (e.g. concurrent login from another IP).
     */
    public static final String JUTSU_PREMIUM_REQUIRED = "JUTSU_PREMIUM_REQUIRED";

    /**
     * The episode page came back without the {@code <video>} player block at all. Almost always
     * bot-detection — request headers, IP reputation, or rate-limit cadence triggered jut.su to
     * serve the lite/bot HTML.
     */
    public static final String JUTSU_PLAYER_MISSING = "JUTSU_PLAYER_MISSING";

    /**
     * Player block exists but no {@code <source src="…mp4">} matched. Unexpected — usually a schema
     * change on jut.su's side. Capture the response body and update the regexes.
     */
    public static final String JUTSU_SOURCE_TAG_MISSING = "JUTSU_SOURCE_TAG_MISSING";

    private JutsuErrorCodes() {}
}
