package com.orinuno.jutsu.repository;

import com.orinuno.jutsu.model.JutsuFilm;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * MyBatis mapper for {@code jutsu_film} (ARCH-0016 P1a — L1 per-source cache for jut.su full-
 * length movies). One row per ({@code slug}, {@code filmIndex}); composite-key upsert is idempotent
 * by design.
 */
@Mapper
public interface JutsuFilmRepository {

    /** All films for one slug, ordered by {@code filmIndex} ascending. */
    List<JutsuFilm> findBySlug(@Param("slug") String slug);

    long countBySlug(@Param("slug") String slug);

    /**
     * Bulk upsert. Wrapped in a single SQL statement to avoid per-film round-trips during a full
     * info-page parse. {@code last_seen_at} is always overwritten; {@code discovered_at} is
     * preserved via {@code COALESCE}; {@code paywalled} keeps its prior value when the new payload
     * carries {@code NULL} (catalog crawl doesn't probe the gate, but a follow-up resolver might).
     */
    void upsertAll(@Param("films") List<JutsuFilm> films);

    /**
     * Delete films for {@code slug} whose {@code filmIndex} is NOT in the keep-list. Used after a
     * fresh info-page parse to evict films that disappeared from upstream (rare, but happens when
     * jut.su consolidates entries or pulls a paywalled show).
     */
    int deleteMissing(@Param("slug") String slug, @Param("keep") List<JutsuFilm> keepList);
}
