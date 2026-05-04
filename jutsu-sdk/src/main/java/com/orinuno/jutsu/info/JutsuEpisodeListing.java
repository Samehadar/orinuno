package com.orinuno.jutsu.info;

import jakarta.annotation.Nullable;

/**
 * One episode's metadata as it appears in an anime info page's episode list.
 *
 * @param slug anime slug ({@code onepuunchman})
 * @param season 1-based season number; {@code 1} when the URL has no {@code season-N} segment
 *     (single-season anime)
 * @param episode 1-based episode number
 * @param label the visible label as displayed by jut.su (e.g. {@code "1 серия"} or {@code
 *     "Ванпанчмен 1 сезон 1 серия"}); never null but may be empty if the anchor had no text
 * @param url relative URL ({@code /{slug}/season-N/episode-M.html} or {@code
 *     /{slug}/episode-M.html}); never null
 */
public record JutsuEpisodeListing(String slug, int season, int episode, String label, String url) {

    public JutsuEpisodeListing {
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("slug must not be blank");
        }
        if (season < 1) throw new IllegalArgumentException("season must be ≥ 1: " + season);
        if (episode < 1) throw new IllegalArgumentException("episode must be ≥ 1: " + episode);
        label = label == null ? "" : label.trim();
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url must not be blank");
        }
    }

    /**
     * Convenience: absolute URL (jut.su HTTPS scheme + host). The relative form is what jut.su
     * embeds in the markup so callers that follow this URL without an HTTP layer get a resolvable
     * string.
     */
    public String absoluteUrl() {
        if (url.startsWith("http")) return url;
        if (url.startsWith("/")) return "https://jut.su" + url;
        return "https://jut.su/" + url;
    }

    /** Allows callers to attach an alternative label without re-validating the rest. */
    public JutsuEpisodeListing withLabel(@Nullable String newLabel) {
        return new JutsuEpisodeListing(
                slug, season, episode, newLabel == null ? "" : newLabel, url);
    }
}
