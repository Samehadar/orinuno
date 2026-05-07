package com.orinuno.model.dto.jutsu;

import com.orinuno.jutsu.catalog.JutsuCatalogEntry;
import com.orinuno.jutsu.model.JutsuTitle;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import java.util.List;

/**
 * REST projection of {@link JutsuCatalogEntry}. Slightly flattens the SDK record so external
 * consumers don't need to depend on jut.su enum types — genres/types are emitted as their slug
 * strings.
 */
@Schema(description = "One anime card from a jut.su catalog page.")
public record JutsuCatalogEntryDto(
        @Schema(example = "naruto") String slug,
        @Schema(description = "Russian display title", example = "Наруто") String title,
        @Schema(
                        description = "Original / romaji title when present on the card",
                        example = "Naruto",
                        nullable = true)
                @Nullable
                String originalTitle,
        @Schema(example = "https://gen.jut.su/uploads/animethumbs/anime_naruto.jpg") @Nullable
                String thumbnailUrl,
        @Schema(description = "Total episode count rendered on the card", example = "220") @Nullable
                Integer episodeCount,
        @Schema(description = "Movie / OVA count rendered on the card", example = "0") @Nullable
                Integer movieCount,
        @Schema(description = "Genre slugs (from JutsuGenre)") List<String> genres,
        @Schema(description = "Type slugs (from JutsuType)") List<String> types,
        @Schema(description = "Year bucket slug from JutsuYear", nullable = true) @Nullable
                String year,
        @Schema(example = "https://jut.su/naruto/") String detailUrl) {

    public static JutsuCatalogEntryDto from(JutsuCatalogEntry e) {
        return new JutsuCatalogEntryDto(
                e.slug(),
                e.title(),
                e.originalTitle(),
                e.thumbnailUrl(),
                e.episodeCount(),
                e.movieCount(),
                e.genres().stream().map(g -> g.slug()).toList(),
                e.types().stream().map(t -> t.slug()).toList(),
                e.year().map(y -> y.slug()).orElse(null),
                e.detailUrl());
    }

    /**
     * Project a {@code jutsu_title} L1 row onto the same wire shape the live SDK returns. ADR 0016
     * P1a relies on this so the demo UI and downstream consumers see one uniform contract
     * regardless of whether the response was served from MySQL or via a hybrid fallback to jut.su.
     *
     * <p>The L1 mirror does not currently store genres / types / movie counts, so those are emitted
     * as empty / null. {@code year} is rendered as a string to match the live SDK shape.
     */
    public static JutsuCatalogEntryDto fromTitle(JutsuTitle t) {
        String detailUrl = "https://jut.su/" + t.getSlug() + "/";
        String year = t.getYear() == null ? null : Integer.toString(t.getYear());
        return new JutsuCatalogEntryDto(
                t.getSlug(),
                t.getTitleRu(),
                t.getTitleEn(),
                t.getPosterUrl(),
                t.getEpisodesTotal(),
                t.getMovieCount(),
                splitCsv(t.getGenres()),
                splitCsv(t.getTypes()),
                year,
                detailUrl);
    }

    /**
     * Split a CSV slug list (as stored in {@code jutsu_title.genres / types}) into a list of
     * trimmed slugs. Returns an empty list for null / blank input — never null, so the wire shape
     * stays stable.
     */
    static List<String> splitCsv(@Nullable String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        String[] parts = csv.split(",");
        java.util.ArrayList<String> out = new java.util.ArrayList<>(parts.length);
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return java.util.Collections.unmodifiableList(out);
    }
}
