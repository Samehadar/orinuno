package com.orinuno.service.provider.jutsu;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PLAYER-4 (ADR 0009) — pure URL-shape parser for JutSu (jut.su). The episode page URL contains the
 * anime slug + episode marker.
 */
public final class JutsuSourceParser {

    private static final Pattern EPISODE_URL =
            Pattern.compile(
                    "jut\\.su/([a-z0-9-]+)/(?:season-\\d+/)?episode-(\\d+)\\.html",
                    Pattern.CASE_INSENSITIVE);

    private JutsuSourceParser() {}

    public record JutsuEpisodeRef(String slug, int episode) {}

    public static Optional<JutsuEpisodeRef> extractRef(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        Matcher m = EPISODE_URL.matcher(url);
        if (!m.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new JutsuEpisodeRef(m.group(1), Integer.parseInt(m.group(2))));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }
}
