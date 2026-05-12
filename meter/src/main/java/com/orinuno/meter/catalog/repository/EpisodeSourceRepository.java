/*
 * EpisodeSourceRepository — ADR 0021 Block B3-a (L2 in meter).
 *
 * MyBatis mapper for episode_source. Mirror of orinuno-app's legacy
 * com.orinuno.repository.EpisodeSourceRepository, scoped to the
 * orinuno_catalog schema (meter's DB). Block B2 wires this into the
 * Kodik event-poller; Block B1 retires the orinuno-app copy.
 */
package com.orinuno.meter.catalog.repository;

import com.orinuno.meter.catalog.model.EpisodeSource;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface EpisodeSourceRepository {

    Optional<EpisodeSource> findById(@Param("id") Long id);

    List<EpisodeSource> findByContent(@Param("contentId") Long contentId);

    List<EpisodeSource> findByEpisode(
            @Param("contentId") Long contentId,
            @Param("season") Integer season,
            @Param("episode") Integer episode);

    Optional<EpisodeSource> findByUniqueKey(
            @Param("contentId") Long contentId,
            @Param("season") Integer season,
            @Param("episode") Integer episode,
            @Param("translatorId") String translatorId,
            @Param("provider") String provider);

    /** Insert-or-update by the natural key. {@code discovered_at} is preserved on conflict. */
    void upsert(@Param("source") EpisodeSource source);
}
