package com.orinuno.model.dto;

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
        List<String> screenshots,
        Boolean camrip,
        Boolean lgbt,
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
