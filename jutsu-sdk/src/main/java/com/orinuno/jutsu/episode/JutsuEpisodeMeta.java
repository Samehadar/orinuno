package com.orinuno.jutsu.episode;

import jakarta.annotation.Nullable;

/**
 * Lightweight metadata for one jut.su episode page ({@code GET
 * /{slug}/(season-N/)?episode-M.html}).
 *
 * <p>Returned by {@link JutsuEpisodeMetaClient} when callers want page chrome (title, thumbnail,
 * navigation links, gating status) without paying the cost of the full video-decode pipeline. The
 * existing {@code JutsuDecoder} still owns the heavy decoding path; this record is the cheap
 * sibling that exists for catalogue/listing UIs that need to render an episode card.
 *
 * @param slug anime slug ({@code onepuunchman})
 * @param season 1-based season; {@code 1} when the URL has no {@code season-N} segment
 * @param episode 1-based episode number
 * @param displayTitle the rendered Russian heading from the page's {@code <h1>} (e.g. {@code
 *     "Ванпанчмен 1 сезон 1 серия"}); never blank
 * @param pageTitle the {@code <title>} element verbatim (with {@code "на Jut.su"} suffix, etc.);
 *     never blank
 * @param canonicalUrl absolute canonical URL from {@code <link rel="canonical">}; never blank
 * @param thumbnailUrl preview image from {@code og:image}, or null when missing
 * @param prevEpisodeUrl relative URL of the previous episode (from the {@code vnleft} arrow), or
 *     null on the first episode of an anime
 * @param nextEpisodeUrl relative URL of the next episode (from the {@code vnright} arrow), or null
 *     on the last currently-published episode
 * @param allEpisodesUrl relative URL of the anime info / "all episodes" page (from the {@code
 *     vncenter} link); usually {@code /{slug}/}; never null on a healthy page
 * @param premiumGated {@code true} when jut.su is showing the {@code tab_need_plus} paywall in
 *     place of the player (caller must hold a Jutsu+ session to actually decode the video)
 */
public record JutsuEpisodeMeta(
        String slug,
        int season,
        int episode,
        String displayTitle,
        String pageTitle,
        String canonicalUrl,
        @Nullable String thumbnailUrl,
        @Nullable String prevEpisodeUrl,
        @Nullable String nextEpisodeUrl,
        @Nullable String allEpisodesUrl,
        boolean premiumGated) {

    public JutsuEpisodeMeta {
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("slug must not be blank");
        }
        if (season < 1) throw new IllegalArgumentException("season must be ≥ 1: " + season);
        if (episode < 1) throw new IllegalArgumentException("episode must be ≥ 1: " + episode);
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

    /** True when the page advertises a next-episode arrow. */
    public boolean hasNext() {
        return nextEpisodeUrl != null && !nextEpisodeUrl.isBlank();
    }

    /** True when the page advertises a previous-episode arrow. */
    public boolean hasPrev() {
        return prevEpisodeUrl != null && !prevEpisodeUrl.isBlank();
    }
}
