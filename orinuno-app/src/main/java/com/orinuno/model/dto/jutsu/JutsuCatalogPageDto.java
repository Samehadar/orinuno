package com.orinuno.model.dto.jutsu;

import com.orinuno.jutsu.catalog.JutsuCatalogPage;
import com.orinuno.jutsu.model.JutsuTitle;
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

    /**
     * Build a wire page from an L1 query result. {@code hasMore} is computed against {@code
     * totalElements} so callers don't need a separate "next" flag.
     */
    public static JutsuCatalogPageDto fromTitlePage(
            int page, int pageSize, long totalElements, List<JutsuTitle> rows) {
        boolean hasMore = (long) page * Math.max(1, pageSize) < totalElements;
        return new JutsuCatalogPageDto(
                page, rows.stream().map(JutsuCatalogEntryDto::fromTitle).toList(), hasMore);
    }
}
