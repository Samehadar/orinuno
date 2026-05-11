package com.orinuno.catalog.readonly;

import java.time.LocalDateTime;

/**
 * Plain row record returned by {@link CatalogContentReadRepository} (ADR 0018 Phase 5.4). The
 * read-side dual of {@code com.orinuno.catalog.model.CatalogContent} — kept structurally distinct
 * so a future Phase 6 swap from JDBC to a Kafka-fed local store can rebind the contract without
 * touching the catalog write-path model.
 *
 * <p>Fields mirror columns 1:1; null-safety is the caller's job. {@code mediaUrls} / episode
 * sub-structures live on dedicated rows queried separately.
 */
public record CatalogContentRow(
        long id,
        String titleRu,
        String titleEn,
        String kind,
        Integer year,
        String shikimoriId,
        String malId,
        String imdbId,
        String kinopoiskId,
        String mdlId,
        String tmdbId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {}
