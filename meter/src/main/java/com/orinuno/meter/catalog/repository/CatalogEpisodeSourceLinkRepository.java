package com.orinuno.meter.catalog.repository;

import com.orinuno.meter.catalog.model.CatalogEpisodeSourceLink;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * MyBatis mapper for {@code catalog_episode_source_link} (ARCH-0016 P1b — M:N link from L3
 * canonical episodes to L2 {@code episode_source}).
 *
 * <p>Idempotent insert by {@code (catalogEpisodeId, episodeSourceId)}. There is no update path:
 * once a link exists it just stays — the link itself carries no mutable state, and the underlying
 * {@code episode_source} row owns all decode bookkeeping.
 */
@Mapper
public interface CatalogEpisodeSourceLinkRepository {

    Optional<CatalogEpisodeSourceLink> findByEpisodeAndSource(
            @Param("catalogEpisodeId") long catalogEpisodeId,
            @Param("episodeSourceId") long episodeSourceId);

    List<CatalogEpisodeSourceLink> findByCatalogEpisode(
            @Param("catalogEpisodeId") long catalogEpisodeId);

    List<CatalogEpisodeSourceLink> findByEpisodeSource(
            @Param("episodeSourceId") long episodeSourceId);

    /**
     * Idempotent upsert (no-op on duplicate). Returns 1 if a new row was inserted, 0 if the link
     * already existed.
     */
    int upsert(@Param("link") CatalogEpisodeSourceLink link);

    long count();
}
