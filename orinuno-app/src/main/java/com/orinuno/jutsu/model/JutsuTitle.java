package com.orinuno.jutsu.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * L1 per-source cache row for one jut.su title (ARCH-0016 P1a). Backs {@code jutsu_title}.
 *
 * <p>Mirrors the union of {@code JutsuCatalogEntry} (catalog page card) and {@code JutsuAnimeInfo}
 * (info page chrome) so a single row can be hydrated from either source. Catalog-only fields
 * ({@code catalogEpisodeCount}, {@code catalogMovieCount}) are populated during catalog crawls;
 * info-only fields ({@code synopsis}, {@code infoTotalSeasons}, {@code infoTotalEpisodes}) are
 * populated when an anime info page is fetched.
 *
 * <p>Genre / type / year columns are stored as comma-joined SDK slugs (matching {@code
 * JutsuGenre.slug()}, etc.) rather than parsed structures so the L1 row stays a faithful mirror of
 * upstream — re-parsing happens on the way out, not on the way in.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JutsuTitle {

    private String slug;
    private Integer siteId;
    private String title;
    private String originalTitle;
    private String synopsis;
    private String thumbnailUrl;
    private String yearBucket;

    /**
     * Comma-joined integer years from the labelled info block ({@code "2014,2020,2024"}). Captured
     * separately from {@link #yearBucket} so display ("Годы выпуска: 2014, 2020, 2024") stays
     * orthogonal to filter form ("2015-2023"). {@code null} when the parser couldn't find the
     * labelled block or the page legitimately doesn't list multi-year releases.
     */
    private String yearsCsv;

    /**
     * Russian age-rating wire form ({@code "0+"} / {@code "6+"} / {@code "12+"} / {@code "16+"} /
     * {@code "18+"}). {@code null} when the page didn't render the badge. Decoded via {@code
     * JutsuAgeRating.fromWire(...)}.
     */
    private String ageRating;

    private String genresCsv;
    private String typesCsv;
    private Integer catalogEpisodeCount;
    private Integer catalogMovieCount;

    /**
     * 1-based position in the last full catalog crawl ({@code (page - 1) * 30 + slot}). Drives the
     * default "by rating" sort on the read side because jut.su returns its default ranking via the
     * page order itself — page 1 = highest rated. {@code null} for rows that have only been seen by
     * the notice walker (no full-crawl observation yet); the read service sorts those last.
     */
    private Integer catalogPosition;

    private Integer infoTotalSeasons;
    private Integer infoTotalEpisodes;
    private LocalDateTime infoFetchedAt;
    private LocalDateTime catalogFetchedAt;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
}
