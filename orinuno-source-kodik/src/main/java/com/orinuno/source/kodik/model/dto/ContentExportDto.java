/*
 * ContentExportDto — ADR 0021 §C4.1.
 *
 * Wire format for GET /api/v1/export/* in source-kodik. Field-for-field
 * identical to orinuno-app's legacy ContentExportDto so the demo UI sees
 * no diff during the reverse-proxy cutover (C4.2).
 */
package com.orinuno.source.kodik.model.dto;

import java.util.List;

public record ContentExportDto(
        Long id,
        String type,
        String title,
        String titleOrig,
        String otherTitle,
        Integer year,
        String kinopoiskId,
        String imdbId,
        String shikimoriId,
        String posterUrl,
        List<String> screenshots,
        Boolean camrip,
        Boolean lgbt,
        Integer lastSeason,
        Integer lastEpisode,
        Integer episodesCount,
        String animeStatus,
        String dramaStatus,
        String allStatus,
        Boolean ongoing,
        List<SeasonExportDto> seasons) {
    public record SeasonExportDto(Integer seasonNumber, List<EpisodeExportDto> episodes) {}

    public record EpisodeExportDto(Integer episodeNumber, List<VariantExportDto> variants) {}

    public record VariantExportDto(
            Long id,
            Integer translationId,
            String translationTitle,
            String translationType,
            String quality,
            String mp4Link) {}
}
