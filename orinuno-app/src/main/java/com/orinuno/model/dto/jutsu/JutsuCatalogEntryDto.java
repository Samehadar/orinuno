package com.orinuno.model.dto.jutsu;

import com.orinuno.jutsu.catalog.JutsuCatalogEntry;
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
}
