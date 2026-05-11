package com.orinuno.source.jutsu.dto;

import com.orinuno.jutsu.catalog.JutsuCatalogPage;
import com.orinuno.source.jutsu.model.JutsuTitle;
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
     * Build a DTO from cached rows plus the total row count of the underlying query. {@code
     * hasMore} is computed from {@code page * pageSize < totalCount} — the L1 schema gives us an
     * exact count via a paired SELECT COUNT(*), so we don't need an over-fetch trick.
     */
    public static JutsuCatalogPageDto fromCache(
            int page, int pageSize, long totalCount, List<JutsuTitle> rows) {
        boolean hasMore = (long) page * pageSize < totalCount;
        return new JutsuCatalogPageDto(
                page, rows.stream().map(JutsuCatalogEntryDto::fromCache).toList(), hasMore);
    }
}
