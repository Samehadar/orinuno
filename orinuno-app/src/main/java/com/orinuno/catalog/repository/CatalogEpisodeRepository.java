package com.orinuno.catalog.repository;

import com.orinuno.catalog.model.CatalogEpisode;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * MyBatis mapper for {@code catalog_episode} (ARCH-0016 P1b — L3 canonical episode tree).
 *
 * <p>Idempotent upsert keyed on {@code (contentId, season, episode)}. {@code title} and {@code
 * airDate} are protected by {@code COALESCE} on update so a sparse refresh (e.g. notice walk that
 * only learned the episode existed but not its title) doesn't blank fields a richer source had
 * already filled.
 */
@Mapper
public interface CatalogEpisodeRepository {

    Optional<CatalogEpisode> findById(@Param("id") long id);

    Optional<CatalogEpisode> findByContentSeasonEpisode(
            @Param("contentId") long contentId,
            @Param("season") int season,
            @Param("episode") int episode);

    List<CatalogEpisode> findByContentId(@Param("contentId") long contentId);

    /**
     * Insert-or-update by {@code (contentId, season, episode)}. The mapper populates the
     * auto-generated id back onto the argument on insert; on update, {@code id} stays whatever the
     * caller passed (which may be null — callers should re-read by the natural key if they need
     * it).
     */
    void upsert(@Param("episode") CatalogEpisode episode);

    long count();
}
