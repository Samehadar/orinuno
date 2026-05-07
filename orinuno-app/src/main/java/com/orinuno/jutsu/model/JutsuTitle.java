package com.orinuno.jutsu.model;

import jakarta.annotation.Nullable;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MyBatis row for {@code jutsu_title} (ADR 0016 P1a). One row per anime slug — mirrors what jut.su
 * advertises on its catalog and anime-info pages.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JutsuTitle {
    private String slug;
    private String titleRu;
    @Nullable private String titleEn;
    @Nullable private JutsuTitleStatus status;
    @Nullable private Integer year;
    @Nullable private Integer episodesTotal;

    /**
     * Comma-separated list of {@code JutsuGenre} slugs as parsed off the catalog card. Stored as
     * CSV instead of a separate join table because we never query against individual slugs
     * server-side — the L1 catalog endpoints filter by {@code title_query} / {@code status} only.
     * Live-fallback handles slug-based filters.
     */
    @Nullable private String genres;

    /** Comma-separated list of {@code JutsuType} slugs. Same rationale as {@link #genres}. */
    @Nullable private String types;

    @Nullable private Integer movieCount;
    @Nullable private Long shikimoriId;
    @Nullable private Long malId;
    @Nullable private String description;
    @Nullable private String posterUrl;
    @Nullable private LocalDateTime lastSyncedAt;
    @Nullable private String sourceEtag;
}
