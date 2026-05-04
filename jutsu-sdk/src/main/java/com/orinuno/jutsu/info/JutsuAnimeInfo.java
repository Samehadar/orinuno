package com.orinuno.jutsu.info;

import com.orinuno.jutsu.filter.JutsuGenre;
import com.orinuno.jutsu.filter.JutsuType;
import com.orinuno.jutsu.filter.JutsuYear;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Top-level metadata for an anime, as parsed from {@code GET /{slug}/}.
 *
 * @param slug URL slug ({@code onepuunchman})
 * @param title Russian title; never blank
 * @param originalTitle the parenthesised romaji/English title pulled out of the meta description
 *     ({@code "Ванпанчмен (One Punch Man)"} → {@code "One Punch Man"}); may be null
 * @param synopsis short synopsis from the page body; may be null on entries without descriptions
 * @param year extracted year bucket; empty when the page declares none
 * @param genres genre set from the page chrome; may be empty
 * @param types type set from the page chrome; may be empty
 * @param thumbnailUrl absolute thumbnail URL from {@code og:image}; may be null
 * @param seasons ordered season list; never empty for valid responses (single-season anime collapse
 *     into a single entry)
 */
public record JutsuAnimeInfo(
        String slug,
        String title,
        @Nullable String originalTitle,
        @Nullable String synopsis,
        Optional<JutsuYear> year,
        Set<JutsuGenre> genres,
        Set<JutsuType> types,
        @Nullable String thumbnailUrl,
        List<JutsuSeason> seasons) {

    public JutsuAnimeInfo {
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("slug must not be blank");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        year = year == null ? Optional.empty() : year;
        genres = genres == null ? Set.of() : Set.copyOf(genres);
        types = types == null ? Set.of() : Set.copyOf(types);
        seasons = seasons == null ? List.of() : List.copyOf(seasons);
    }

    /** Sum of episode counts across every season. */
    public int totalEpisodeCount() {
        return seasons.stream().mapToInt(JutsuSeason::episodeCount).sum();
    }

    public boolean hasMultipleSeasons() {
        return seasons.size() > 1;
    }

    /** Absolute URL to this anime's info page. */
    public String detailUrl() {
        return "https://jut.su/" + slug + "/";
    }
}
