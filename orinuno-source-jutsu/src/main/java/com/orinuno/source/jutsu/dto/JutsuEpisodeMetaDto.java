package com.orinuno.source.jutsu.dto;

import com.orinuno.jutsu.episode.JutsuEpisodeMeta;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

/** REST projection of {@link JutsuEpisodeMeta}; serialised with {@code kind: "episode"}. */
@Schema(description = "Lightweight metadata for one jut.su episode page (no video decode).")
public record JutsuEpisodeMetaDto(
        @Schema(example = "onepuunchman") String slug,
        @Schema(example = "1") int season,
        @Schema(example = "1") int episode,
        @Schema(example = "Ванпанчмен 1 сезон 1 серия") String displayTitle,
        @Schema(example = "Смотреть Ванпанчмен 1 сезон 1 серия на Jut.su") String pageTitle,
        @Schema(example = "https://jut.su/onepuunchman/season-1/episode-1.html")
                String canonicalUrl,
        @Schema(nullable = true) @Nullable String thumbnailUrl,
        @Schema(nullable = true) @Nullable String prevEpisodeUrl,
        @Schema(nullable = true) @Nullable String nextEpisodeUrl,
        @Schema(nullable = true) @Nullable String allEpisodesUrl,
        @Schema(description = "true when jut.su shows the Jutsu+ paywall instead of the player")
                boolean premiumGated)
        implements JutsuPageMetaDto {

    @Override
    @Schema(allowableValues = "episode", example = "episode")
    public String kind() {
        return "episode";
    }

    public static JutsuEpisodeMetaDto from(JutsuEpisodeMeta m) {
        return new JutsuEpisodeMetaDto(
                m.slug(),
                m.season(),
                m.episode(),
                m.displayTitle(),
                m.pageTitle(),
                m.canonicalUrl(),
                m.thumbnailUrl(),
                m.prevEpisodeUrl(),
                m.nextEpisodeUrl(),
                m.allEpisodesUrl(),
                m.premiumGated());
    }
}
