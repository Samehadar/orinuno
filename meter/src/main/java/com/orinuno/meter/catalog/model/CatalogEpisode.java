package com.orinuno.meter.catalog.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Canonical episode row (ARCH-0016 P1b — L3). Backs {@code catalog_episode}.
 *
 * <p>One row per {@code (contentId, season, episode)} tuple. Per-source episode pointers ({@code
 * kodik_episode_variant}, {@code jutsu_episode}, future Sibnet rows) attach to this canonical row
 * via {@link CatalogEpisodeSourceLink} so the P2 {@code /catalog/content/{id}/episodes} REST
 * surface and {@code MultiSourceRanker} can answer "show me everything we have for this episode"
 * with one canonical key.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CatalogEpisode {

    private Long id;
    private Long contentId;
    private Integer season;
    private Integer episode;
    private String title;
    private LocalDate airDate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
