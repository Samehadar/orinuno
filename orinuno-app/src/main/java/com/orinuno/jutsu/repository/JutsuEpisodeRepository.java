package com.orinuno.jutsu.repository;

import com.orinuno.jutsu.model.JutsuEpisode;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** MyBatis mapper for {@code jutsu_episode} (ADR 0016 P1a). */
@Mapper
public interface JutsuEpisodeRepository {

    Optional<JutsuEpisode> findByTitleAndPosition(
            @Param("titleSlug") String titleSlug,
            @Param("season") int season,
            @Param("episode") int episode);

    List<JutsuEpisode> listForTitle(@Param("titleSlug") String titleSlug);

    /**
     * Batch insert-or-update keyed by {@code (title_slug, season, episode)}. COALESCE'd to keep a
     * previously decoded {@code embed_url} when the new payload is partial.
     */
    void upsertBatch(@Param("episodes") List<JutsuEpisode> episodes);

    long countAll();
}
