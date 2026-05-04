package com.orinuno.model.dto.jutsu;

import com.orinuno.jutsu.info.JutsuEpisodeListing;
import com.orinuno.jutsu.info.JutsuSeason;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

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
