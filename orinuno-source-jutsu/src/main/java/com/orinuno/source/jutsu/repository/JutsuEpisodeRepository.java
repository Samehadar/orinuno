package com.orinuno.source.jutsu.repository;

import com.orinuno.source.jutsu.model.JutsuEpisode;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * MyBatis mapper for {@code jutsu_episode} (ARCH-0016 P1a — L1 per-source cache for jut.su). One
 * row per ({@code slug}, {@code season}, {@code episode}); composite-key upsert is idempotent by
 * design.
 */
@Mapper
public interface JutsuEpisodeRepository {

    /** All episodes for one slug, ordered by ({@code season}, {@code episode}) ascending. */
    List<JutsuEpisode> findBySlug(@Param("slug") String slug);

    /** Episodes for one specific season. */
    List<JutsuEpisode> findBySlugAndSeason(@Param("slug") String slug, @Param("season") int season);

    long countBySlug(@Param("slug") String slug);

    /**
     * Bulk upsert. Wrapped in a single SQL statement to avoid per-episode round-trips during a full
     * info-page parse (a long-runner can have 200+ episodes; per-row INSERT would dominate the
     * latency). {@code last_seen_at} is always overwritten; {@code discovered_at} is preserved via
     * {@code COALESCE}.
     */
    void upsertAll(@Param("episodes") List<JutsuEpisode> episodes);

    /**
     * Delete episodes for {@code slug} whose ({@code season}, {@code episode}) tuple is NOT in the
     * keep-list. Used after a fresh info-page parse to evict episodes that disappeared from
     * upstream (rare, but happens when jut.su consolidates seasons or pulls a paywalled show).
     */
    int deleteMissing(@Param("slug") String slug, @Param("keep") List<JutsuEpisode> keepList);
}
