package com.orinuno.service.provider.sibnet;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PLAYER-3 (ADR 0006) — pure URL-shape parser for Sibnet (video.sibnet.ru). Recognizes the two URL
 * flavours Sibnet publishes:
 *
 * <ul>
 *   <li>{@code https://video.sibnet.ru/video<id>-<slug>.html} — page URL, embeds the iframe.
 *   <li>{@code https://video.sibnet.ru/shell.php?videoid=<id>} — direct iframe URL with the
 *       playlist embedded in the response body.
 * </ul>
 *
 * <p>Both shapes carry the same numeric {@code videoid} which is the natural key. {@link
 * #extractVideoId(String)} returns it for either shape. {@link #toIframeUrl(long)} canonicalizes to
 * the iframe form (the shape the decoder fetches).
 */
public final class SibnetSourceParser {

    private static final Pattern PAGE_URL =
            Pattern.compile(
                    "video\\.sibnet\\.ru/video(\\d+)(?:-[^/]+)?\\.html", Pattern.CASE_INSENSITIVE);

    private static final Pattern SHELL_URL =
            Pattern.compile(
                    "video\\.sibnet\\.ru/shell\\.php\\?(?:[^&]*&)*videoid=(\\d+)",
                    Pattern.CASE_INSENSITIVE);

    private SibnetSourceParser() {}

    public static Optional<Long> extractVideoId(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        Matcher shell = SHELL_URL.matcher(url);
        if (shell.find()) {
            return parse(shell.group(1));
        }
        Matcher page = PAGE_URL.matcher(url);
        if (page.find()) {
            return parse(page.group(1));
        }
        return Optional.empty();
    }

    public static String toIframeUrl(long videoId) {
        return "https://video.sibnet.ru/shell.php?videoid=" + videoId;
    }

    private static Optional<Long> parse(String numeric) {
        try {
            return Optional.of(Long.parseLong(numeric));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }
}
