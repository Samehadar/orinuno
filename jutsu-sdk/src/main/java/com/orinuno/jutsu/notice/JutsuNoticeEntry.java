package com.orinuno.jutsu.notice;

import jakarta.annotation.Nullable;

/**
 * One entry in jut.su's "upcoming releases" notice feed (POST {@code
 * /engine/ajax/site_notice.php}).
 *
 * <p>Each entry is one episode that was just announced or just published. The site renders these
 * inside the bell-icon widget at the top of every page; the feed endpoint returns the same data as
 * raw HTML fragments grouped 50 entries per cursor.
 *
 * @param slug anime slug parsed from the anchor href ({@code shokugyou-kanteishi}); never blank
 * @param season 1-based season number; {@code 1} when the URL has no {@code season-N} segment
 * @param episode 1-based episode number
 * @param title rendered Russian title with episode info ({@code "Не герой, а временный инспектор: 6
 *     серия"}); never blank
 * @param episodeUrl absolute URL of the announced episode; never blank
 * @param thumbnailUrl absolute URL of the small notice thumbnail; may be null on entries jut.su
 *     hasn't generated a preview for yet
 * @param relativeDate the human-readable Russian date the site renders ({@code "сегодня ночью"},
 *     {@code "вчера вечером"}, {@code "27 апреля"}); never blank — the SDK preserves it verbatim
 *     because parsing it is locale-sensitive and brittle
 */
public record JutsuNoticeEntry(
        String slug,
        int season,
        int episode,
        String title,
        String episodeUrl,
        @Nullable String thumbnailUrl,
        String relativeDate) {

    public JutsuNoticeEntry {
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("slug must not be blank");
        }
        if (season < 1) throw new IllegalArgumentException("season must be ≥ 1: " + season);
        if (episode < 1) throw new IllegalArgumentException("episode must be ≥ 1: " + episode);
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (episodeUrl == null || episodeUrl.isBlank()) {
            throw new IllegalArgumentException("episodeUrl must not be blank");
        }
        if (relativeDate == null || relativeDate.isBlank()) {
            throw new IllegalArgumentException("relativeDate must not be blank");
        }
    }
}
