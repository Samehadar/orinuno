package com.orinuno.jutsu.info;

import jakarta.annotation.Nullable;

/**
 * One full-length movie ("полнометражный фильм") attached to an anime entry on jut.su. Films are a
 * parallel concept to seasons / episodes — they live under {@code /{slug}/film-N.html} URLs and
 * jut.su renders them in a dedicated {@code <h2 class="…films_title">Полнометражные фильмы</h2>}
 * block, separate from the season grids. We model them as a sibling list to {@link JutsuSeason}
 * inside {@link JutsuAnimeInfo} so consumers don't have to overload "season" semantics with a
 * sentinel value.
 *
 * @param slug anime slug ({@code life-no-game})
 * @param index 1-based film number as it appears in the URL ({@code /life-no-game/film-1.html} →
 *     {@code 1})
 * @param label the visible label as displayed by jut.su (e.g. {@code "1 фильм"} or {@code "Нет игры
 *     - нет жизни 1 фильм"}); never null but may be empty if the anchor had no text
 * @param url relative URL ({@code /{slug}/film-N.html}); never null
 */
public record JutsuFilmListing(String slug, int index, String label, String url) {

    public JutsuFilmListing {
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("slug must not be blank");
        }
        if (index < 1) throw new IllegalArgumentException("index must be ≥ 1: " + index);
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
    public JutsuFilmListing withLabel(@Nullable String newLabel) {
        return new JutsuFilmListing(slug, index, newLabel == null ? "" : newLabel, url);
    }
}
