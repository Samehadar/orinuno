package com.orinuno.catalog.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Canonical content row (ARCH-0016 P1b — L3 universal catalog). Backs {@code catalog_content}.
 *
 * <p>One instance per logical title (a film, a series, an anime). Many per-source rows can attach
 * to the same canonical instance through {@code catalog_content_external_id} ({@link
 * CatalogContentExternalId}); identity columns ({@code shikimoriId}, {@code malId}, ...) are a
 * denormalised hot path for the P2 REST surface and the resolver's "do I already have this?"
 * lookup. The resolver keeps both representations in sync inside the same transaction.
 *
 * <p>{@link #id} is auto-generated. New rows are built without it (Lombok {@code @Builder}) and
 * MyBatis populates it via {@code useGeneratedKeys=true} on insert.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CatalogContent {

    /** Auto-increment primary key. {@code null} for fresh, not-yet-inserted rows. */
    private Long id;

    private String titleRu;
    private String titleEn;

    /**
     * Coarse type bucket (MOVIE / SERIES / ANIME). Stored as the lowercase wire string in the DB
     * via {@link CatalogContentKind#wire()}. Defaults to {@link CatalogContentKind#UNKNOWN} when we
     * receive a row with an unrecognised value.
     */
    private CatalogContentKind kind;

    /** Production year. Nullable because some sources surface only the year bucket. */
    private Integer year;

    /**
     * Identity columns. Each holds the canonical external id of its type for this row, with "first
     * writer wins" tie-break enforced by the resolver. The full set of attached ids (including
     * duplicates and per-source-context ids like Kodik raw / jut.su slug) lives in {@code
     * catalog_content_external_id}.
     */
    private String shikimoriId;

    private String malId;
    private String imdbId;
    private String kinopoiskId;
    private String mdlId;
    private String tmdbId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
