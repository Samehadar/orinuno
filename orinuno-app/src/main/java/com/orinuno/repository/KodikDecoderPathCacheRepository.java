package com.orinuno.repository;

import com.orinuno.model.KodikDecoderPathCacheEntry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * MyBatis mapper for {@code kodik_decoder_path_cache} (DECODE-2). Idempotent upsert pattern keyed
 * by {@code netloc}; on conflict the path is overwritten only if it actually changed and {@code
 * hit_count} is bumped by 1.
 */
@Mapper
public interface KodikDecoderPathCacheRepository {

    /** Insert or update one cache row by netloc. Bumps {@code hit_count} on every call. */
    void upsertCachedPath(
            @Param("netloc") String netloc,
            @Param("videoInfoPath") String videoInfoPath,
            @Param("now") LocalDateTime now);

    Optional<KodikDecoderPathCacheEntry> findByNetloc(@Param("netloc") String netloc);

    List<KodikDecoderPathCacheEntry> findAll();
}
