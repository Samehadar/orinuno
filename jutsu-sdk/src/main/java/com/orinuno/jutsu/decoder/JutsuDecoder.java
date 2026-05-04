package com.orinuno.jutsu.decoder;

import com.orinuno.jutsu.JutsuConfig;
import com.orinuno.jutsu.JutsuDecodeResult;
import com.orinuno.jutsu.JutsuErrorCodes;
import com.orinuno.jutsu.auth.JutsuSessionManager;
import com.orinuno.jutsu.parser.JutsuHtmlCharset;
import com.orinuno.jutsu.ratelimit.JutsuRateLimiter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * JutSu decoder. Fetches the episode page HTML, extracts every {@code <source src="..."
 * type="video/mp4">} tag and groups by quality based on the {@code label} or the URL path (jut.su
 * URLs encode the quality as {@code 720.mp4} / {@code 1080.mp4}).
 *
 * <p>Pipeline:
 *
 * <ol>
 *   <li>{@link JutsuRateLimiter#acquire()} — block until our outbound RPS budget allows the call.
 *   <li>{@link JutsuSessionManager#cookieHeader()} — fetch the cached DLE session cookies, lazily
 *       logging in via {@code POST /} if credentials are configured but no session exists.
 *   <li>GET the episode page with the cookies; {@link #extractFromHtml} classifies the response.
 *   <li>If the response is {@link JutsuErrorCodes#JUTSU_PREMIUM_REQUIRED} AND we are configured
 *       with credentials, invalidate the session and retry exactly once — handles silent
 *       server-side cookie expiry.
 * </ol>
 *
 * <p>Without credentials configured (default for fresh deployments) the decoder runs in anonymous
 * mode: the cookie header is empty, premium-gated episodes return {@link
 * JutsuErrorCodes#JUTSU_PREMIUM_REQUIRED}. Cloudflare challenges and missing-player responses
 * retain their dedicated error codes for runbook routing.
 */
@Slf4j
public final class JutsuDecoder {

    /**
     * Matches the entire {@code <source ...>} tag (greedy up to the closing {@code >}). We do NOT
     * try to capture {@code src} and {@code label} in one pass — premium-account URLs have
     * arbitrary attribute ordering and 6+ attributes per tag, which made the original combined
     * pattern silently lose the {@code label} group. Two narrow patterns ({@link #SRC_ATTR} +
     * {@link #LABEL_ATTR}) applied to the captured tag string are easier to reason about and let us
     * return one entry per quality even when the URL itself doesn't encode the resolution.
     */
    static final Pattern SOURCE_TAG =
            Pattern.compile("<source\\b[^>]*?>", Pattern.CASE_INSENSITIVE);

    static final Pattern SRC_ATTR =
            Pattern.compile("\\bsrc=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    static final Pattern LABEL_ATTR =
            Pattern.compile("\\blabel=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    static final Pattern RES_ATTR =
            Pattern.compile("\\bres=\"(\\d{3,4})\"", Pattern.CASE_INSENSITIVE);

    /**
     * Quality extracted from URL path. jut.su has used at least three URL shapes over the years:
     *
     * <ul>
     *   <li>{@code .../episode-N/720.mp4} — old layout, {@code /720.mp4} matches.
     *   <li>{@code .../{anime}/{episode}.{quality}.{hash}.mp4} — current premium layout, the {@code
     *       .{quality}.} segment matches.
     *   <li>{@code .../templates/school/images/pixel.png?720} — placeholder for gated content; we
     *       reject these via {@link #PREMIUM_MARKER} before extraction even sees them.
     * </ul>
     */
    static final Pattern QUALITY_FROM_URL =
            Pattern.compile(
                    "[./](\\d{3,4})(?:p)?(?:\\.[a-f0-9]+)?\\.mp4", Pattern.CASE_INSENSITIVE);

    /**
     * Premium-gating signals on jut.su. Both are ASCII so they survive whatever charset the page is
     * delivered in (jut.su responds with windows-1251).
     */
    static final Pattern PREMIUM_MARKER =
            Pattern.compile(
                    "(?:tab_need_plus|gen\\.jut\\.su/templates/school/images/pixel\\.png)",
                    Pattern.CASE_INSENSITIVE);

    /**
     * Detects whether the response body contains the actual {@code <video class=…vjs-…>} player
     * block at all. When jut.su's bot-detection trips (or the request is missing required cookies)
     * the page is rendered without the player — distinguish that from "player present but premium
     * gated" so operators can act on the right runbook.
     */
    static final Pattern VIDEO_BLOCK = Pattern.compile("<video[\\s>]", Pattern.CASE_INSENSITIVE);

    private final WebClient client;
    private final JutsuRateLimiter rateLimiter;
    private final JutsuSessionManager sessionManager;

    public JutsuDecoder(
            JutsuConfig config,
            JutsuRateLimiter rateLimiter,
            JutsuSessionManager sessionManager,
            WebClient.Builder webClientBuilder) {
        // jut.su responds with windows-1251. Spring's default String decoder honours the
        // Content-Type charset already, but spelling it out here means tests that don't go
        // through a real HTTP layer also decode bodies the same way the runtime does — and
        // keeps the {@code <source>} regex (which uses ASCII delimiters) charset-stable.
        this.client =
                webClientBuilder
                        .defaultHeader(HttpHeaders.USER_AGENT, config.userAgent())
                        .defaultHeader(
                                HttpHeaders.ACCEPT,
                                "text/html,application/xhtml+xml,application/xml;q=0.9,"
                                        + "image/avif,image/webp,*/*;q=0.8")
                        .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "ru-RU,ru;q=0.9,en;q=0.8")
                        .defaultHeader(HttpHeaders.REFERER, "https://jut.su/")
                        .build();
        this.rateLimiter = rateLimiter;
        this.sessionManager = sessionManager;
    }

    /** Test-only constructor: drives only {@link #extractFromHtml}. */
    JutsuDecoder() {
        this.client = null;
        this.rateLimiter = null;
        this.sessionManager = null;
    }

    public Mono<JutsuDecodeResult> decode(String episodeUrl) {
        return decodeOnce(episodeUrl, false);
    }

    /**
     * Single-shot decode. When the response is {@link JutsuErrorCodes#JUTSU_PREMIUM_REQUIRED} on
     * the first attempt AND we have credentials configured, we treat this as a stale-session signal
     * and retry once after invalidating the cookie jar. Subsequent failures are returned as-is —
     * re-trying twice for the same outcome would just waste rate-limit tokens.
     *
     * @param episodeUrl absolute jut.su episode URL
     * @param isRetry true on the second attempt after a forced re-login; gates the retry off
     */
    private Mono<JutsuDecodeResult> decodeOnce(String episodeUrl, boolean isRetry) {
        return rateLimiter
                .acquire()
                .then(sessionManager.cookieHeader().defaultIfEmpty(""))
                .flatMap(
                        cookieHeader ->
                                client.get()
                                        .uri(episodeUrl)
                                        .headers(
                                                h -> {
                                                    if (!cookieHeader.isEmpty()) {
                                                        h.add(HttpHeaders.COOKIE, cookieHeader);
                                                    }
                                                })
                                        .exchangeToMono(
                                                resp ->
                                                        resp.bodyToMono(byte[].class)
                                                                .defaultIfEmpty(new byte[0])
                                                                .map(
                                                                        bytes ->
                                                                                decodeBytes(
                                                                                        bytes,
                                                                                        resp.headers()
                                                                                                .contentType()
                                                                                                .orElse(
                                                                                                        null)))))
                .map(JutsuDecoder::extractFromHtml)
                .flatMap(
                        result -> {
                            if (!isRetry
                                    && JutsuErrorCodes.JUTSU_PREMIUM_REQUIRED.equals(
                                            result.errorCode())
                                    && sessionManager.peekHasCredentials()) {
                                log.info(
                                        "♻️ JutSu PREMIUM_REQUIRED on first attempt — invalidating"
                                                + " session and retrying once for {}",
                                        episodeUrl);
                                sessionManager.invalidate("premium-marker-after-login");
                                return decodeOnce(episodeUrl, true);
                            }
                            return Mono.just(result);
                        })
                .onErrorResume(
                        ex -> {
                            log.warn("JutSu decode error for {}: {}", episodeUrl, ex.toString());
                            return Mono.just(
                                    JutsuDecodeResult.failure(JutsuErrorCodes.JUTSU_FETCH_ERROR));
                        });
    }

    /**
     * Decode the response body with the charset declared on the {@code Content-Type} header,
     * falling back to {@code windows-1251} (jut.su's default) when none is present. Decoding the
     * page as UTF-8 mojibakes the cyrillic premium-overlay text, but our markers are all ASCII so
     * it does not affect detection — this is purely defence-in-depth for any future cyrillic
     * matchers we might add.
     *
     * <p>Delegates to {@link JutsuHtmlCharset#decode(byte[], MediaType)} so the catalog/info/notice
     * parsers get the exact same fallback behaviour without duplicating the resolution logic.
     */
    static String decodeBytes(byte[] bytes, MediaType contentType) {
        return JutsuHtmlCharset.decode(bytes, contentType);
    }

    public static JutsuDecodeResult extractFromHtml(String html) {
        if (html == null || html.isBlank()) {
            return JutsuDecodeResult.failure(JutsuErrorCodes.JUTSU_EMPTY_RESPONSE);
        }
        if (looksLikeCloudflareChallenge(html)) {
            return JutsuDecodeResult.failure(JutsuErrorCodes.JUTSU_CLOUDFLARE_BLOCKED);
        }
        // Check premium gating BEFORE extracting <source>. Premium pages still ship <source>
        // tags but their src points to gen.jut.su/.../pixel.png — looks valid to a naive
        // parser but does not play. Returning JUTSU_PREMIUM_REQUIRED here also avoids the
        // misleading JUTSU_SOURCE_TAG_MISSING when the page DOES have <source> tags but none
        // with .mp4 extension.
        if (PREMIUM_MARKER.matcher(html).find()) {
            return JutsuDecodeResult.failure(JutsuErrorCodes.JUTSU_PREMIUM_REQUIRED);
        }
        Map<String, String> qualities = new LinkedHashMap<>();
        Matcher tag = SOURCE_TAG.matcher(html);
        while (tag.find()) {
            String tagText = tag.group();
            Matcher srcMatcher = SRC_ATTR.matcher(tagText);
            if (!srcMatcher.find()) continue;
            String url = srcMatcher.group(1).trim();
            if (!url.toLowerCase(Locale.ROOT).contains(".mp4")) continue;
            String label = null;
            Matcher labelMatcher = LABEL_ATTR.matcher(tagText);
            if (labelMatcher.find()) label = labelMatcher.group(1);
            String res = null;
            Matcher resMatcher = RES_ATTR.matcher(tagText);
            if (resMatcher.find()) res = resMatcher.group(1);
            String quality = pickQuality(label, res, url);
            qualities.putIfAbsent(quality, url);
        }
        if (qualities.isEmpty()) {
            // Distinguish "page came back without a player block at all" (likely bot detection)
            // from "player block exists but no .mp4 sources" (likely upstream HTML changed).
            if (!VIDEO_BLOCK.matcher(html).find()) {
                return JutsuDecodeResult.failure(JutsuErrorCodes.JUTSU_PLAYER_MISSING);
            }
            return JutsuDecodeResult.failure(JutsuErrorCodes.JUTSU_SOURCE_TAG_MISSING);
        }
        return JutsuDecodeResult.success(qualities, "video/mp4");
    }

    /**
     * Resolution-naming priority on jut.su:
     *
     * <ol>
     *   <li>{@code label="720p"} — present on every modern player snippet, easiest to read.
     *   <li>{@code res="720"} — set alongside {@code label} on premium accounts; useful as a
     *       fallback when the label is set to something cosmetic like {@code "HD"}.
     *   <li>URL path digits, via {@link #QUALITY_FROM_URL}. Last-resort because the new per-account
     *       URL shape ({@code /{episode}.{quality}.{hash}.mp4}) has surprised us once already by
     *       surrounding the digits with dots instead of slashes.
     * </ol>
     *
     * <p>Returns {@code "auto"} when none of the three signals fire — the caller will still emit
     * the URL but operators reading metrics know to look at the response shape.
     */
    static String pickQuality(String label, String res, String url) {
        if (label != null && !label.isBlank()) {
            String stripped = label.trim().replaceAll("(?i)p$", "");
            if (stripped.matches("\\d{3,4}")) return stripped;
        }
        if (res != null && res.matches("\\d{3,4}")) return res;
        Matcher m = QUALITY_FROM_URL.matcher(url);
        if (m.find()) return m.group(1);
        return "auto";
    }

    /**
     * Backwards-compat shim — used by tests written before the {@code res=} attribute was an input
     * to quality picking. Callers in {@link #extractFromHtml} now pass the {@code res} value too.
     */
    static String pickQuality(String label, String url) {
        return pickQuality(label, null, url);
    }

    static boolean looksLikeCloudflareChallenge(String html) {
        return html.contains("Just a moment...")
                || html.contains("cf-browser-verification")
                || html.contains("__cf_chl_jschl_tk__");
    }
}
