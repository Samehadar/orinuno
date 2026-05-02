package com.orinuno.repository;

import com.orinuno.model.EpisodeSource;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * MyBatis mapper for {@code episode_source} (PLAYER-1, ADR 0005). Provider-specific parsers upsert
 * via {@link #upsert} which is keyed by {@code (content_id, season, episode, translator_id,
 * provider)}.
 */
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
