/*
 * EpisodeVideoRepository — ADR 0021 Block B3-a (L2 in meter).
 *
 * MyBatis mapper for episode_video. Mirror of orinuno-app's legacy
 * com.orinuno.repository.EpisodeVideoRepository, scoped to the
 * orinuno_catalog schema. upsertDecoded never clobbers a valid URL with
 * NULL on conflict; recordFailure bumps the counter without touching the
 * URL.
 */
package com.orinuno.meter.catalog.repository;

import com.orinuno.meter.catalog.model.EpisodeVideo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface EpisodeVideoRepository {

    Optional<EpisodeVideo> findById(@Param("id") Long id);

    List<EpisodeVideo> findBySource(@Param("sourceId") Long sourceId);

    /**
     * Insert-or-update keyed by (source_id, quality). COALESCE'd to never overwrite a valid URL
     * with NULL.
     */
    void upsertDecoded(@Param("video") EpisodeVideo video);

    /** Bump the failure counter without touching {@code video_url} / {@code decoded_at}. */
    void recordFailure(
            @Param("sourceId") Long sourceId,
            @Param("quality") String quality,
            @Param("error") String error,
            @Param("now") LocalDateTime now);

    int countAll();
}
