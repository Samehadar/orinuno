package com.orinuno.repository;

import com.orinuno.model.EpisodeVideo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * MyBatis mapper for {@code episode_video} (PLAYER-1, ADR 0005). One row per (source_id, quality)
 * tuple. Decoded URLs upsert via {@link #upsertDecoded}; failures bump the counter via {@link
 * #recordFailure}.
 */
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
