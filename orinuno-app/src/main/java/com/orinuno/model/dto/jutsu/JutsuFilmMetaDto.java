package com.orinuno.model.dto.jutsu;

import com.orinuno.jutsu.episode.JutsuFilmMeta;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

/** REST projection of {@link JutsuFilmMeta}; serialised with {@code kind: "film"}. */
@Schema(
        description =
                "Lightweight metadata for one jut.su full-length-film page (no video decode).")
public record JutsuFilmMetaDto(
        @Schema(example = "life-no-game") String slug,
        @Schema(example = "1") int filmIndex,
        @Schema(example = "Смотреть 1 фильм Нет игры - нет жизни") String displayTitle,
        @Schema(example = "Смотреть Нет игры - нет жизни 1 фильм на Jut.su") String pageTitle,
        @Schema(example = "https://jut.su/life-no-game/film-1.html") String canonicalUrl,
        @Schema(nullable = true) @Nullable String thumbnailUrl,
        @Schema(nullable = true) @Nullable String prevFilmUrl,
        @Schema(nullable = true) @Nullable String nextFilmUrl,
        @Schema(nullable = true) @Nullable String allEpisodesUrl,
        @Schema(description = "true when jut.su shows the Jutsu+ paywall instead of the player")
                boolean premiumGated)
        implements JutsuPageMetaDto {

    @Override
    @Schema(allowableValues = "film", example = "film")
    public String kind() {
        return "film";
    }

    public static JutsuFilmMetaDto from(JutsuFilmMeta m) {
        return new JutsuFilmMetaDto(
                m.slug(),
                m.filmIndex(),
                m.displayTitle(),
                m.pageTitle(),
                m.canonicalUrl(),
                m.thumbnailUrl(),
                m.prevFilmUrl(),
                m.nextFilmUrl(),
                m.allEpisodesUrl(),
                m.premiumGated());
    }
}
