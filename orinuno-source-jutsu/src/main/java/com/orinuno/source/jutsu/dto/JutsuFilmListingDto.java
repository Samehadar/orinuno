package com.orinuno.source.jutsu.dto;

import com.orinuno.jutsu.info.JutsuFilmListing;
import com.orinuno.source.jutsu.model.JutsuFilm;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * REST projection of {@link JutsuFilmListing}. Films are surfaced alongside seasons under {@code
 * JutsuAnimeInfoDto.films}; they don't fit into the season → episode nesting because jut.su renders
 * them in a parallel "Полнометражные фильмы" block.
 */
@Schema(description = "One full-length movie anchor on the anime info page.")
public record JutsuFilmListingDto(
        @Schema(example = "life-no-game") String slug,
        @Schema(description = "1-based film index from the URL", example = "1") int index,
        @Schema(example = "1 фильм") String label,
        @Schema(example = "/life-no-game/film-1.html") String url) {

    public static JutsuFilmListingDto from(JutsuFilmListing f) {
        return new JutsuFilmListingDto(f.slug(), f.index(), f.label(), f.url());
    }

    /** Build a DTO from a cached {@link JutsuFilm} row. */
    public static JutsuFilmListingDto fromCache(JutsuFilm row) {
        return new JutsuFilmListingDto(
                row.getSlug(),
                row.getFilmIndex(),
                row.getLabel() == null ? "" : row.getLabel(),
                row.getRelativeUrl());
    }
}
