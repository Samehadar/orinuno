package com.orinuno.model.dto.jutsu;

import com.orinuno.jutsu.info.JutsuAnimeInfo;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
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
}
