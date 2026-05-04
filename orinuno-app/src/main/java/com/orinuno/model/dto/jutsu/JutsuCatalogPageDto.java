package com.orinuno.model.dto.jutsu;

import com.orinuno.jutsu.catalog.JutsuCatalogPage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** REST projection of {@link JutsuCatalogPage}. */
@Schema(description = "One page of the jut.su catalog browse / search response.")
public record JutsuCatalogPageDto(
        @Schema(description = "1-based page number that produced this response", example = "1")
                int page,
        @Schema(description = "Cards on this page") List<JutsuCatalogEntryDto> entries,
        @Schema(
                        description =
                                "true when the underlying anime_page_next flag in the JS partial"
                                        + " says further pages exist")
                boolean hasMore) {

    public static JutsuCatalogPageDto from(JutsuCatalogPage p) {
        return new JutsuCatalogPageDto(
                p.page(),
                p.entries().stream().map(JutsuCatalogEntryDto::from).toList(),
                p.hasMore());
    }
}
