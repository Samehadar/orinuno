package com.orinuno.model.dto.jutsu;

import com.orinuno.jutsu.info.JutsuAnimeInfo;
import com.orinuno.jutsu.model.JutsuEpisode;
import com.orinuno.jutsu.model.JutsuTitle;
import com.orinuno.model.dto.jutsu.JutsuSeasonDto.JutsuEpisodeListingDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
     * Project an L1 anime row plus its cached episodes onto the live SDK wire shape so the demo UI
     * and API consumers see one uniform contract regardless of cache hit / fallback. Genres, types,
     * and synopsis come from {@code jutsu_title} where present; seasons are grouped by the
     * episode's season index.
     */
    public static JutsuAnimeInfoDto fromTitleWithEpisodes(
            JutsuTitle title, List<JutsuEpisode> episodes) {
        Map<Integer, List<JutsuEpisode>> grouped = new TreeMap<>();
        for (JutsuEpisode ep : episodes) {
            grouped.computeIfAbsent(ep.getSeason(), k -> new ArrayList<>()).add(ep);
        }
        List<JutsuSeasonDto> seasons = new ArrayList<>();
        for (Map.Entry<Integer, List<JutsuEpisode>> entry : grouped.entrySet()) {
            int seasonIndex = entry.getKey();
            List<JutsuEpisode> rows = entry.getValue();
            rows.sort(Comparator.comparingInt(JutsuEpisode::getEpisode));
            List<JutsuEpisodeListingDto> ordered = new ArrayList<>(rows.size());
            for (JutsuEpisode ep : rows) {
                String url =
                        ep.getEmbedUrl() != null
                                ? ep.getEmbedUrl()
                                : "/"
                                        + title.getSlug()
                                        + "/season-"
                                        + ep.getSeason()
                                        + "/episode-"
                                        + ep.getEpisode()
                                        + ".html";
                ordered.add(
                        new JutsuEpisodeListingDto(
                                title.getSlug(),
                                ep.getSeason(),
                                ep.getEpisode(),
                                ep.getEpisode() + " серия",
                                url));
            }
            seasons.add(
                    new JutsuSeasonDto(
                            seasonIndex, seasonIndex + " сезон", ordered.size(), ordered));
        }
        int totalEpisodeCount =
                title.getEpisodesTotal() != null ? title.getEpisodesTotal() : episodes.size();
        String year = title.getYear() == null ? null : Integer.toString(title.getYear());
        return new JutsuAnimeInfoDto(
                title.getSlug(),
                title.getTitleRu(),
                title.getTitleEn(),
                title.getDescription(),
                title.getPosterUrl(),
                year,
                JutsuCatalogEntryDto.splitCsv(title.getGenres()),
                JutsuCatalogEntryDto.splitCsv(title.getTypes()),
                seasons,
                totalEpisodeCount);
    }
}
