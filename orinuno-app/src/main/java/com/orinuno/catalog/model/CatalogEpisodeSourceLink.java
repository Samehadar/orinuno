package com.orinuno.catalog.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * M:N link between an L3 {@link CatalogEpisode} and an L2 {@code episode_source} pointer (ARCH-0005
 * / ARCH-0016 P1b). Backs {@code catalog_episode_source_link}.
 *
 * <p>{@link #episodeSourceId} is a soft reference into the {@code core} context's {@code
 * episode_source} table — no FK constraint per ADR 0016 zoning rules. Reads tolerate dangling links
 * (per-source episode dropped) by left-joining and skipping the orphan rows; inserts are idempotent
 * on {@code (catalogEpisodeId, episodeSourceId)}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CatalogEpisodeSourceLink {

    private Long id;
    private Long catalogEpisodeId;
    private Long episodeSourceId;
    private LocalDateTime createdAt;
}
