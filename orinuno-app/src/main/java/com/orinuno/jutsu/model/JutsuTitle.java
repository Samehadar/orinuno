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
    private String genresCsv;
    private String typesCsv;
    private Integer catalogEpisodeCount;
    private Integer catalogMovieCount;
    private Integer infoTotalSeasons;
    private Integer infoTotalEpisodes;
    private LocalDateTime infoFetchedAt;
    private LocalDateTime catalogFetchedAt;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
}
