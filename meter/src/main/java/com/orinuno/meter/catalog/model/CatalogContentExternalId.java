package com.orinuno.meter.catalog.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Normalised external id binding for {@link CatalogContent} (ARCH-0016 P1b). Backs {@code
 * catalog_content_external_id}.
 *
 * <p>The {@code (sourceType, externalId)} tuple is unique across the table — that's what gives the
 * resolver an O(1) reverse lookup and the P2 REST {@code ?external_id=...} query a single index
 * hit. The same {@link #contentId} can hold many rows of the same {@link #sourceType} (one Kodik
 * canonical row may carry multiple Kodik raw ids when upstream returns alias entries).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CatalogContentExternalId {

    private Long id;
    private Long contentId;
    private CatalogSourceType sourceType;
    private String externalId;
    private LocalDateTime createdAt;
}
