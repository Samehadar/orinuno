package com.orinuno.model.dto.jutsu;

import com.orinuno.jutsu.info.JutsuAnimeInfo;
import com.orinuno.jutsu.model.JutsuEpisode;
import com.orinuno.jutsu.model.JutsuTitle;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import java.util.Arrays;
import java.util.List;

/** REST projection of {@link JutsuAnimeInfo}. */
@Schema(description = "Anime info page (GET /{slug}/) projection.")
public record JutsuAnimeInfoDto(
        @Schema(example = "onepuunchman") String slug,
        @Schema(example = "Ванпанчмен") String title,
        @Schema(example = "One Punch Man", nullable = true) @Nullable String originalTitle,
        @Schema(nullable = true) @Nullable String synopsis,
        @Schema(nullable = true) @Nullable String thumbnailUrl,
        @Schema(nullable = true, example = "2015") @Nullable String year,
        @Schema(description = "Genre slugs from the page chrome") List<String> genres,
        @Schema(description = "Type slugs from the page chrome") List<String> types,
        @Schema(description = "Season blocks parsed from the page") List<JutsuSeasonDto> seasons,
        @Schema(description = "Total number of episode anchors discovered") int totalEpisodeCount) {

    public static JutsuAnimeInfoDto from(JutsuAnimeInfo info) {
        return new JutsuAnimeInfoDto(
                info.slug(),
                info.title(),
                info.originalTitle(),
                info.synopsis(),
                info.thumbnailUrl(),
                info.year().map(y -> y.slug()).orElse(null),
                info.genres().stream().map(g -> g.slug()).toList(),
                info.types().stream().map(t -> t.slug()).toList(),
                info.seasons().stream().map(JutsuSeasonDto::from).toList(),
                info.totalEpisodeCount());
    }

    /**
     * Build a DTO from a cached title + its episode list. Episodes are grouped by season into
     * {@link JutsuSeasonDto} blocks ordered by season ASC; within a season episodes order by
     * episode number ASC. The shape matches {@link #from(JutsuAnimeInfo)} so consumers can switch
     * between cache-hit and live-fetch responses transparently.
     */
    public static JutsuAnimeInfoDto fromCache(JutsuTitle row, List<JutsuEpisode> episodes) {
        List<JutsuSeasonDto> seasons = JutsuSeasonDto.fromCache(row.getSlug(), episodes);
        int total = seasons.stream().mapToInt(JutsuSeasonDto::episodeCount).sum();
        return new JutsuAnimeInfoDto(
                row.getSlug(),
                row.getTitle() == null ? row.getSlug() : row.getTitle(),
                row.getOriginalTitle(),
                row.getSynopsis(),
                row.getThumbnailUrl(),
                row.getYearBucket(),
                splitCsv(row.getGenresCsv()),
                splitCsv(row.getTypesCsv()),
                seasons,
                total);
    }

    private static List<String> splitCsv(@Nullable String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
