package com.orinuno.model.dto.jutsu;

import com.orinuno.jutsu.episode.JutsuEpisodeMeta;
import com.orinuno.jutsu.model.JutsuEpisode;
import com.orinuno.jutsu.model.JutsuTitle;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

/** REST projection of {@link JutsuEpisodeMeta}. */
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
                boolean premiumGated) {

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

    /**
     * Project a cached {@code jutsu_episode} row onto the live SDK wire shape. The L1 mirror only
     * stores coordinates + canonical URL, so display chrome (titles, thumbnail, prev/next) is
     * synthesised best-effort. {@code title} is optional and only used to build the display title.
     */
    public static JutsuEpisodeMetaDto fromStored(JutsuEpisode e, @Nullable JutsuTitle title) {
        String canonical =
                e.getEmbedUrl() != null
                        ? e.getEmbedUrl()
                        : "https://jut.su/"
                                + e.getTitleSlug()
                                + "/season-"
                                + e.getSeason()
                                + "/episode-"
                                + e.getEpisode()
                                + ".html";
        String prefix = title != null ? title.getTitleRu() : e.getTitleSlug();
        String displayTitle = prefix + " " + e.getSeason() + " сезон " + e.getEpisode() + " серия";
        return new JutsuEpisodeMetaDto(
                e.getTitleSlug(),
                e.getSeason(),
                e.getEpisode(),
                displayTitle,
                displayTitle,
                canonical,
                null,
                null,
                null,
                "/" + e.getTitleSlug() + "/",
                false);
    }
}
