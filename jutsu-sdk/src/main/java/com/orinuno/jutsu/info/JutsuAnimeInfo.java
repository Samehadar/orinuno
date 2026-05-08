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
 * @param year coarse year bucket from the catalog filter form (one of jut.su's eight buckets, e.g.
 *     {@code 2015-2023}); empty when the page declares none. Use {@link #years} for the
 *     finer-grained per-season air years.
 * @param years individual numeric air years (e.g. {@code [2015, 2019, 2025]} for an anime whose
 *     three seasons aired in those years); never null, ordered by appearance, deduplicated. Empty
 *     for anime where the page didn't surface them
 * @param ageRating Russian age-rating classifier ({@code 0+} / {@code 6+} / {@code 12+} / {@code
 *     16+} / {@code 18+}); empty when the page didn't render the badge
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
        List<Integer> years,
        Optional<JutsuAgeRating> ageRating,
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
        years = years == null ? List.of() : List.copyOf(years);
        ageRating = ageRating == null ? Optional.empty() : ageRating;
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
