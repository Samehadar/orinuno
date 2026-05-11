package com.orinuno.meter.catalog.repository;

import com.orinuno.meter.catalog.model.CatalogContent;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * MyBatis mapper for {@code catalog_content} (ARCH-0016 P1b — L3 universal canonical catalog).
 *
 * <p>Insert returns the auto-generated {@link CatalogContent#getId()} via {@code
 * useGeneratedKeys=true}. Updates target an existing row by {@code id}; nothing here knows about
 * external-id resolution — that's {@code CatalogIdentityResolver}'s job (P1b Step 1.B), which
 * orchestrates this repository together with {@link CatalogContentExternalIdRepository}.
 *
 * <p>The {@code findByIdentity*} family powers the resolver's hot-path lookup (shikimori → mal →
 * imdb → kinopoisk → mdl → tmdb) directly off the denormalised identity columns in {@code
 * catalog_content}, so the resolver doesn't pay a join on every probe.
 */
@Mapper
public interface CatalogContentRepository {

    Optional<CatalogContent> findById(@Param("id") long id);

    Optional<CatalogContent> findByShikimoriId(@Param("externalId") String externalId);

    Optional<CatalogContent> findByMalId(@Param("externalId") String externalId);

    Optional<CatalogContent> findByImdbId(@Param("externalId") String externalId);

    Optional<CatalogContent> findByKinopoiskId(@Param("externalId") String externalId);

    Optional<CatalogContent> findByMdlId(@Param("externalId") String externalId);

    Optional<CatalogContent> findByTmdbId(@Param("externalId") String externalId);

    /**
     * Insert a fresh canonical row. The mapper sets {@link CatalogContent#setId(Long)} on the
     * argument with the generated primary key.
     */
    void insert(@Param("content") CatalogContent content);

    /**
     * Apply changes to an existing canonical row by {@code id}. Identity columns are written with
     * {@code COALESCE(VALUES(...), col)} so a partial update never blanks an already-known external
     * id — only the resolver explicitly clears them through {@link #clearIdentityColumn(long,
     * String)}.
     */
    void update(@Param("content") CatalogContent content);

    /**
     * Surgically clear one identity column on a canonical row. Used by the resolver when it detects
     * a conflicting binding (e.g. shikimori_id moved to a different canonical row) and needs to
     * detach the loser before re-attaching elsewhere.
     *
     * <p>{@code column} is whitelisted at the mapper layer to one of {@code shikimori_id}, {@code
     * mal_id}, {@code imdb_id}, {@code kinopoisk_id}, {@code mdl_id}, {@code tmdb_id} — never pass
     * user input here.
     */
    void clearIdentityColumn(@Param("id") long id, @Param("column") String column);

    long count();
}
