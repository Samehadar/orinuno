package com.orinuno.jutsu.episode;

import jakarta.annotation.Nullable;

/**
 * Lightweight page metadata for one jut.su viewer page returned by {@link JutsuEpisodeMetaClient}.
 *
 * <p>Sealed because jut.su currently only renders two distinct viewer-page shapes against a single
 * URL grammar: regular episodes ({@code /{slug}/(season-N/)?episode-M.html}) and full-length movies
 * ({@code /{slug}/film-N.html}). Callers downcast to {@link JutsuEpisodeMeta} or {@link
 * JutsuFilmMeta} to access the kind-specific fields (season/episode vs film index). Common chrome —
 * title, thumbnail, paywall flag, navigation links — is exposed via the methods below so catalogue
 * UIs can render a card without pattern-matching unless they need the discriminator.
 */
public sealed interface JutsuPageMeta permits JutsuEpisodeMeta, JutsuFilmMeta {

    /** Anime slug ({@code onepuunchman}, {@code life-no-game}). */
    String slug();

    /** The rendered Russian heading from the page's {@code <h1>}; never blank. */
    String displayTitle();

    /** The {@code <title>} element verbatim (with {@code "на Jut.su"} suffix); never blank. */
    String pageTitle();

    /** Absolute canonical URL from {@code <link rel="canonical">}; never blank. */
    String canonicalUrl();

    /** Preview image from {@code og:image}, or null when missing. */
    @Nullable
    String thumbnailUrl();

    /**
     * Relative URL of the previous page (from the {@code vnleft} arrow), or null on the first
     * episode/film of an anime.
     */
    @Nullable
    String prevUrl();

    /**
     * Relative URL of the next page (from the {@code vnright} arrow), or null on the last
     * currently-published page.
     */
    @Nullable
    String nextUrl();

    /**
     * Relative URL of the anime info / "all episodes" page (from the {@code vncenter} link);
     * usually {@code /{slug}/}; never null on a healthy page.
     */
    @Nullable
    String allEpisodesUrl();

    /**
     * {@code true} when jut.su is showing the {@code tab_need_plus} paywall in place of the player
     * (caller must hold a Jutsu+ session to actually decode the video).
     */
    boolean premiumGated();
}
