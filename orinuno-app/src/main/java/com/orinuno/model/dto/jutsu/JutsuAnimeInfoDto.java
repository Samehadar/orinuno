package com.orinuno.model.dto.jutsu;

import com.orinuno.jutsu.info.JutsuAgeRating;
import com.orinuno.jutsu.info.JutsuAnimeInfo;
import com.orinuno.jutsu.model.JutsuEpisode;
import com.orinuno.jutsu.model.JutsuFilm;
import com.orinuno.jutsu.model.JutsuTitle;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** REST projection of {@link JutsuAnimeInfo}. */
@Schema(description = "Anime info page (GET /{slug}/) projection.")
public record JutsuAnimeInfoDto(
        @Schema(example = "onepuunchman") String slug,
        @Schema(example = "Ванпанчмен") String title,
        @Schema(example = "One Punch Man", nullable = true) @Nullable String originalTitle,
        @Schema(nullable = true) @Nullable String synopsis,
        @Schema(nullable = true) @Nullable String thumbnailUrl,
        @Schema(
                        nullable = true,
                        example = "2015-2023",
                        description =
                                "Coarse year bucket from the catalog filter form. Use `years` for"
                                        + " the per-season air years.")
                @Nullable
                String year,
        @Schema(
                        description =
                                "Per-season air years from the labelled info block (e.g."
                                        + " [2014, 2020, 2024]). Empty when the page didn't"
                                        + " surface them.")
                List<Integer> years,
        @Schema(
                        nullable = true,
                        example = "16+",
                        description =
                                "Russian age-rating classifier (`0+` / `6+` / `12+` / `16+` /"
                                        + " `18+`); null when the page didn't render the badge.")
                @Nullable
                String ageRating,
        @Schema(description = "Genre slugs from the page chrome") List<String> genres,
        @Schema(description = "Type slugs from the page chrome") List<String> types,
        @Schema(description = "Season blocks parsed from the page") List<JutsuSeasonDto> seasons,
        @Schema(
                        description =
                                "Full-length movies attached to the same series, rendered by jut.su"
                                        + " in the dedicated \"Полнометражные фильмы\" block. Empty"
                                        + " for anime without movies. Films are NOT counted in"
                                        + " `totalEpisodeCount`; use `totalFilmCount` instead.")
                List<JutsuFilmListingDto> films,
        @Schema(description = "Total number of episode anchors discovered") int totalEpisodeCount,
        @Schema(description = "Total number of full-length movie anchors discovered")
                int totalFilmCount) {

    public static JutsuAnimeInfoDto from(JutsuAnimeInfo info) {
        return new JutsuAnimeInfoDto(
                info.slug(),
                info.title(),
                info.originalTitle(),
                info.synopsis(),
                info.thumbnailUrl(),
                info.year().map(y -> y.slug()).orElse(null),
                info.years(),
                info.ageRating().map(JutsuAgeRating::wire).orElse(null),
                info.genres().stream().map(g -> g.slug()).toList(),
                info.types().stream().map(t -> t.slug()).toList(),
                info.seasons().stream().map(JutsuSeasonDto::from).toList(),
                info.films().stream().map(JutsuFilmListingDto::from).toList(),
                info.totalEpisodeCount(),
                info.totalFilmCount());
    }

    /**
     * Build a DTO from a cached title + its episode list + its film list. Episodes are grouped by
     * season into {@link JutsuSeasonDto} blocks ordered by season ASC; within a season episodes
     * order by episode number ASC. Films are ordered by film index ASC and surfaced as a flat
     * sibling list. The shape matches {@link #from(JutsuAnimeInfo)} so consumers can switch between
     * cache-hit and live-fetch responses transparently.
     */
    public static JutsuAnimeInfoDto fromCache(
            JutsuTitle row, List<JutsuEpisode> episodes, List<JutsuFilm> films) {
        List<JutsuSeasonDto> seasons = JutsuSeasonDto.fromCache(row.getSlug(), episodes);
        int totalEpisodes = seasons.stream().mapToInt(JutsuSeasonDto::episodeCount).sum();
        List<JutsuFilmListingDto> filmDtos = filmsToDto(films);
        return new JutsuAnimeInfoDto(
                row.getSlug(),
                row.getTitle() == null ? row.getSlug() : row.getTitle(),
                row.getOriginalTitle(),
                row.getSynopsis(),
                row.getThumbnailUrl(),
                row.getYearBucket(),
                splitYears(row.getYearsCsv()),
                row.getAgeRating(),
                splitCsv(row.getGenresCsv()),
                splitCsv(row.getTypesCsv()),
                seasons,
                filmDtos,
                totalEpisodes,
                filmDtos.size());
    }

    private static List<JutsuFilmListingDto> filmsToDto(@Nullable List<JutsuFilm> films) {
        if (films == null || films.isEmpty()) return List.of();
        List<JutsuFilm> sorted = new ArrayList<>(films);
        sorted.sort(Comparator.comparingInt(JutsuFilm::getFilmIndex));
        List<JutsuFilmListingDto> out = new ArrayList<>(sorted.size());
        for (JutsuFilm f : sorted) out.add(JutsuFilmListingDto.fromCache(f));
        return List.copyOf(out);
    }

    private static List<String> splitCsv(@Nullable String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * Parse the comma-joined "{@code 2014,2020,2024}" form. Non-numeric tokens are dropped silently
     * so a future widening of the column (e.g. ranges) doesn't break the read path.
     */
    private static List<Integer> splitYears(@Nullable String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<Integer> out = new ArrayList<>();
        for (String raw : csv.split(",")) {
            String token = raw.trim();
            if (token.isEmpty()) continue;
            try {
                out.add(Integer.parseInt(token));
            } catch (NumberFormatException ignore) {
                // Skip malformed token; cache write path keeps the column constrained.
            }
        }
        return List.copyOf(out);
    }
}
