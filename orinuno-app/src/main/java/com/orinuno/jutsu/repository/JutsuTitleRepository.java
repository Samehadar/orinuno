package com.orinuno.jutsu.repository;

import com.orinuno.jutsu.model.JutsuTitle;
import jakarta.annotation.Nullable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * MyBatis mapper for {@code jutsu_title} (ADR 0016 P1a). Reads serve {@code
 * /api/v1/sources/jutsu/catalog} and {@code /api/v1/sources/jutsu/anime/{slug}} from the L1 mirror;
 * upserts come from {@link com.orinuno.jutsu.sync.JutsuCatalogSyncService} during full / notice
 * crawls and from the live-fallback path on a cache miss.
 */
@Mapper
public interface JutsuTitleRepository {

    Optional<JutsuTitle> findBySlug(@Param("slug") String slug);

    /**
     * Page-backed list with optional title (LIKE on title_ru / title_en) and status filters. {@code
     * status} is the raw enum dbValue; {@code null} disables the filter.
     */
    List<JutsuTitle> listFiltered(
            @Param("titleQuery") @Nullable String titleQuery,
            @Param("status") @Nullable String status,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countFiltered(
            @Param("titleQuery") @Nullable String titleQuery,
            @Param("status") @Nullable String status);

    /**
     * Insert-or-update keyed by {@code slug}. COALESCE'd so a partial update never overwrites a
     * previously good column with NULL.
     */
    void upsert(@Param("title") JutsuTitle title);

    long countAll();

    /**
     * Slugs whose {@code last_synced_at} is older than {@code threshold} or NULL. Used by the sync
     * worker to prioritize stale records on incremental ticks.
     */
    List<String> findStaleSlugs(
            @Param("threshold") LocalDateTime threshold, @Param("limit") int limit);
}
