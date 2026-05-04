package com.orinuno.jutsu.catalog;

import com.orinuno.jutsu.filter.JutsuGenre;
import com.orinuno.jutsu.filter.JutsuType;
import com.orinuno.jutsu.filter.JutsuYear;
import jakarta.annotation.Nullable;
import java.util.Optional;
import java.util.Set;

/**
 * One entry on a catalog page: the data we extract from a single {@code <div
 * class="all_anime_global">} card. All fields except {@link #slug} and {@link #title} are nullable
 * because individual cards routinely omit them (older entries lack original-title tooltips, pre-DLE
 * cards lack the year class, etc.).
 *
 * @param siteId numeric site id from {@code id="anime_fs_29"}; {@code -1} when absent
 * @param slug URL slug from the {@code <a href="/{slug}/">} wrapper; never null/blank
 * @param title Russian title from {@code .aaname}; never null/blank
 * @param originalTitle romanised / English title pulled out of the tooltip; may be null
 * @param thumbnailUrl absolute URL extracted from {@code style="background: url(...)"}; may be null
 * @param episodeCount episode count parsed from {@code .aailines} text; may be null when the entry
 *     advertises only films
 * @param movieCount film count from the same {@code .aailines} text; may be null
 * @param genres class-derived genre set; never null but may be empty
 * @param types class-derived type set; never null but may be empty
 * @param year class-derived year bucket, may be empty
 */
public record JutsuCatalogEntry(
        int siteId,
        String slug,
        String title,
        @Nullable String originalTitle,
        @Nullable String thumbnailUrl,
        @Nullable Integer episodeCount,
        @Nullable Integer movieCount,
        Set<JutsuGenre> genres,
        Set<JutsuType> types,
        Optional<JutsuYear> year) {

    public JutsuCatalogEntry {
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("slug must not be blank");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        genres = genres == null ? Set.of() : Set.copyOf(genres);
        types = types == null ? Set.of() : Set.copyOf(types);
        year = year == null ? Optional.empty() : year;
    }

    /** Absolute URL to the anime info page. */
    public String detailUrl() {
        return "https://jut.su/" + slug + "/";
    }
}
