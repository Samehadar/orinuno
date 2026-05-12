/*
 * PageRequest — ADR 0021 §C1.1.
 *
 * Pagination parameters for GET /api/v1/content. Defaults mirror the
 * legacy orinuno-app PageRequest.
 */
package com.orinuno.source.kodik.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageRequest {

    @Builder.Default private int page = 0;
    @Builder.Default private int size = 20;
    @Builder.Default private String sortBy = "id";
    @Builder.Default private String order = "ASC";
}
