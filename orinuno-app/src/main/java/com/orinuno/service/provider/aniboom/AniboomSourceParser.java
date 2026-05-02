package com.orinuno.service.provider.aniboom;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PLAYER-2 (ADR 0006) — pure URL-shape parser for Aniboom (aniboom.one). Recognizes the {@code
 * /embed/<id>} embed-link shape used across animego.org / animevost.org / etc.
 */
public final class AniboomSourceParser {

    private static final Pattern EMBED_URL =
            Pattern.compile("aniboom\\.one/embed/([a-zA-Z0-9_-]+)", Pattern.CASE_INSENSITIVE);

    private AniboomSourceParser() {}

    public static Optional<String> extractEmbedId(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        Matcher m = EMBED_URL.matcher(url);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    public static String toEmbedUrl(String embedId) {
        return "https://aniboom.one/embed/" + embedId;
    }
}
