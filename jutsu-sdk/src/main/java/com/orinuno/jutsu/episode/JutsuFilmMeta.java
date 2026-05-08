package com.orinuno.jutsu.episode;

import jakarta.annotation.Nullable;

/**
 * Lightweight metadata for one jut.su full-length movie page ({@code GET /{slug}/film-N.html}).
 *
 * <p>jut.su renders movie pages on the same template as episodes — same chrome, same paywall
 * mechanic — but with a distinct URL shape ({@code film-N.html} instead of {@code
 * (season-N/)?episode-M.html}) and a different navigation cohort: {@code vnleft}/{@code vnright}
 * step between sibling films, not episodes. Modelling them as a separate record keeps the
 * "season/episode" contract of {@link JutsuEpisodeMeta} sharp instead of overloading it with
 * synthetic season=1/episode=N for films, which would mislead any downstream that integrates the
 * two structures.
 *
 * @param slug anime slug ({@code life-no-game})
 * @param filmIndex 1-based film index from the URL ({@code /life-no-game/film-1.html} → 1)
 * @param displayTitle the rendered Russian heading from the page's {@code <h1>}; never blank
 * @param pageTitle the {@code <title>} element verbatim; never blank
 * @param canonicalUrl absolute canonical URL from {@code <link rel="canonical">}; never blank
 * @param thumbnailUrl preview image from {@code og:image}, or null when missing
 * @param prevFilmUrl relative URL of the previous sibling film (from the {@code vnleft} arrow), or
 *     null when this is the first film of the anime
 * @param nextFilmUrl relative URL of the next sibling film (from the {@code vnright} arrow), or
 *     null when this is the last published film
 * @param allEpisodesUrl relative URL of the anime info page (from the {@code vncenter} link);
 *     usually {@code /{slug}/}
 * @param premiumGated {@code true} when jut.su is showing the {@code tab_need_plus} paywall in
 *     place of the player; films are most often paywalled by default
 */
public record JutsuFilmMeta(
        String slug,
        int filmIndex,
        String displayTitle,
        String pageTitle,
        String canonicalUrl,
        @Nullable String thumbnailUrl,
        @Nullable String prevFilmUrl,
        @Nullable String nextFilmUrl,
        @Nullable String allEpisodesUrl,
        boolean premiumGated)
        implements JutsuPageMeta {

    public JutsuFilmMeta {
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("slug must not be blank");
        }
        if (filmIndex < 1) {
            throw new IllegalArgumentException("filmIndex must be ≥ 1: " + filmIndex);
        }
        if (displayTitle == null || displayTitle.isBlank()) {
            throw new IllegalArgumentException("displayTitle must not be blank");
        }
        if (pageTitle == null || pageTitle.isBlank()) {
            throw new IllegalArgumentException("pageTitle must not be blank");
        }
        if (canonicalUrl == null || canonicalUrl.isBlank()) {
            throw new IllegalArgumentException("canonicalUrl must not be blank");
        }
    }

    public boolean hasNext() {
        return nextFilmUrl != null && !nextFilmUrl.isBlank();
    }

    public boolean hasPrev() {
        return prevFilmUrl != null && !prevFilmUrl.isBlank();
    }

    @Override
    @Nullable
    public String prevUrl() {
        return prevFilmUrl;
    }

    @Override
    @Nullable
    public String nextUrl() {
        return nextFilmUrl;
    }
}
