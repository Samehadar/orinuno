package com.orinuno.source.jutsu.dto;

import com.orinuno.jutsu.info.JutsuEpisodeListing;
import com.orinuno.jutsu.info.JutsuSeason;
import com.orinuno.source.jutsu.model.JutsuEpisode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** REST projection of {@link JutsuSeason}. */
@Schema(description = "One season block on the anime info page.")
public record JutsuSeasonDto(
        @Schema(description = "1-based season index", example = "1") int index,
        @Schema(description = "Rendered season name", example = "1 сезон") String name,
        @Schema(description = "Episode count for this season") int episodeCount,
        @Schema(description = "Ordered list of episodes in this season")
                List<JutsuEpisodeListingDto> episodes) {

    public static JutsuSeasonDto from(JutsuSeason s) {
        return new JutsuSeasonDto(
                s.index(),
                s.name(),
                s.episodeCount(),
                s.episodes().stream().map(JutsuEpisodeListingDto::from).toList());
    }

    /**
     * Group a flat list of cached episode rows by season index, preserving (season ASC, episode
     * ASC) order inside each block. Used by {@code JutsuAnimeInfoDto.fromCache} to recover the
     * season → episodes nesting that the L1 schema flattens.
     *
     * <p>Season {@code name} is synthesised from the index ({@code "1 сезон"}) because the L1
     * schema doesn't store the rendered name verbatim — only the live SDK parser sees it. This
     * matches what jut.su renders for single-season anime when the season index is collapsed.
     */
    public static List<JutsuSeasonDto> fromCache(String slug, List<JutsuEpisode> episodes) {
        if (episodes == null || episodes.isEmpty()) return List.of();
        List<JutsuEpisode> sorted = new ArrayList<>(episodes);
        sorted.sort(
                Comparator.comparingInt(JutsuEpisode::getSeason)
                        .thenComparingInt(JutsuEpisode::getEpisode));
        Map<Integer, List<JutsuEpisodeListingDto>> byS = new LinkedHashMap<>();
        for (JutsuEpisode ep : sorted) {
            byS.computeIfAbsent(ep.getSeason(), k -> new ArrayList<>())
                    .add(
                            new JutsuEpisodeListingDto(
                                    ep.getSlug() == null ? slug : ep.getSlug(),
                                    ep.getSeason(),
                                    ep.getEpisode(),
                                    ep.getLabel() == null ? "" : ep.getLabel(),
                                    ep.getRelativeUrl()));
        }
        List<JutsuSeasonDto> out = new ArrayList<>(byS.size());
        for (Map.Entry<Integer, List<JutsuEpisodeListingDto>> e : byS.entrySet()) {
            out.add(
                    new JutsuSeasonDto(
                            e.getKey(),
                            e.getKey() + " сезон",
                            e.getValue().size(),
                            List.copyOf(e.getValue())));
        }
        return List.copyOf(out);
    }

    @Schema(description = "One episode anchor from a season block.")
    public record JutsuEpisodeListingDto(
            @Schema(example = "onepuunchman") String slug,
            @Schema(example = "1") int season,
            @Schema(example = "1") int episode,
            @Schema(example = "1 серия") String label,
            @Schema(example = "/onepuunchman/season-1/episode-1.html") String url) {

        public static JutsuEpisodeListingDto from(JutsuEpisodeListing l) {
            return new JutsuEpisodeListingDto(
                    l.slug(), l.season(), l.episode(), l.label(), l.url());
        }
    }
}
