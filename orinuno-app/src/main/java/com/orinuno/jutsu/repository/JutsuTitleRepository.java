package com.orinuno.jutsu.repository;

import com.orinuno.jutsu.model.JutsuTitle;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * MyBatis mapper for {@code jutsu_title} (ARCH-0016 P1a — L1 per-source cache for jut.su).
 * Idempotent upsert by {@code slug}; {@code first_seen_at} is protected via {@code COALESCE} in the
 * SQL so re-fetches don't shift the discovery timestamp.
 */
@Mapper
public interface JutsuTitleRepository {

    Optional<JutsuTitle> findBySlug(@Param("slug") String slug);

    /** Bulk-load by slug list. Returns one row per match (no nulls). */
    List<JutsuTitle> findBySlugs(@Param("slugs") List<String> slugs);

    /** Page through the cache ordered by {@code last_seen_at DESC} for ops dashboards. */
    List<JutsuTitle> findRecentlySeen(@Param("limit") int limit, @Param("offset") int offset);

    /**
     * Count titles whose {@code info_fetched_at} is older than the given watermark (or NULL). Used
     * by the sync worker to size its per-tick "refresh-info" batch.
     */
    long countInfoFetchedBefore(@Param("watermark") LocalDateTime watermark);

    /**
     * Pick titles whose {@code info_fetched_at} is NULL or older than the given watermark. Order is
     * stable ({@code info_fetched_at ASC NULLS FIRST}) so the worker resumes from the oldest entry
     * on every tick.
     */
    List<JutsuTitle> pickStaleInfo(
            @Param("watermark") LocalDateTime watermark, @Param("limit") int limit);

    long count();

    /**
     * Insert-or-update by {@code slug}. Caller passes the full row; columns we don't want to
     * overwrite on conflict ({@code first_seen_at}) are protected via {@code COALESCE} in the SQL.
     * Catalog-only and info-only columns are upserted with {@code COALESCE(VALUES(c), c)} so a
     * catalog-only refresh doesn't blank info-page fields and vice versa.
     */
    void upsert(@Param("title") JutsuTitle title);

    /**
     * Read-side catalog query for the cache-first REST surface (ARCH-0016 P1a Step 3.A). The filter
     * parameters mirror jut.su's catalog form: genres / types are AND-combined ({@code
     * FIND_IN_SET}), years are OR-combined (a title can only live in one bucket). The {@code sort}
     * argument is a whitelisted enum slug interpolated via {@code ${...}}; do NOT pass user input
     * here — wire it through {@code JutsuCatalogReadService} which whitelists the sort against the
     * {@code JutsuSort} enum before calling.
     */
    List<JutsuTitle> findCatalogPage(
            @Param("genres") List<String> genres,
            @Param("types") List<String> types,
            @Param("years") List<String> years,
            @Param("sort") String sort,
            @Param("limit") int limit,
            @Param("offset") int offset);

    /**
     * Total row count under the same filters as {@link #findCatalogPage}. Drives {@code hasMore}.
     */
    long countCatalogRows(
            @Param("genres") List<String> genres,
            @Param("types") List<String> types,
            @Param("years") List<String> years);
}
