/*
 * PageResponse — ADR 0021 §C1.1.
 *
 * Paginated response envelope for GET /api/v1/content. Generic over T so
 * future paginated endpoints in source-kodik can reuse the same shape.
 */
package com.orinuno.source.kodik.model.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> {

    private List<T> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
}
