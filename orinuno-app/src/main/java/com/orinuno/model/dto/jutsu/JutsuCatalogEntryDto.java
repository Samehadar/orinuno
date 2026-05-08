package com.orinuno.model.dto.jutsu;

import com.orinuno.jutsu.catalog.JutsuCatalogEntry;
import com.orinuno.jutsu.model.JutsuTitle;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import java.util.Arrays;
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
     * Build a DTO from an L1 cache row. Genres / types are split from the COALESCE-protected CSVs
     * the sync worker writes; the {@code detailUrl} is reconstructed from the slug to keep the
     * shape identical to {@link #from(JutsuCatalogEntry)} (consumers can hit either endpoint and
     * get the same fields).
     */
    public static JutsuCatalogEntryDto fromCache(JutsuTitle row) {
        return new JutsuCatalogEntryDto(
                row.getSlug(),
                row.getTitle() == null ? row.getSlug() : row.getTitle(),
                row.getOriginalTitle(),
                row.getThumbnailUrl(),
                row.getCatalogEpisodeCount(),
                row.getCatalogMovieCount(),
                splitCsv(row.getGenresCsv()),
                splitCsv(row.getTypesCsv()),
                row.getYearBucket(),
                "https://jut.su/" + row.getSlug() + "/");
    }

    private static List<String> splitCsv(@Nullable String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
