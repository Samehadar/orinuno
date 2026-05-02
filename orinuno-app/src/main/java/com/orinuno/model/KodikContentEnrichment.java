package com.orinuno.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * META-1 (ADR 0010) — one row per (content_id, source) tuple. Lossless: {@link #rawPayload}
 * preserves the upstream JSON in case we need to re-derive structured fields later without
 * re-fetching.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KodikContentEnrichment {

    private Long id;
    private Long contentId;
    private String source;
    private String externalId;
    private String titleNative;
    private String titleEnglish;
    private String titleRussian;
    private String description;
    private BigDecimal score;
    private String rawPayload;
    private LocalDateTime fetchedAt;
    private LocalDateTime lastRefreshedAt;

    public static final class Source {
        public static final String SHIKIMORI = "SHIKIMORI";
        public static final String KINOPOISK = "KINOPOISK";
        public static final String MAL = "MAL";

        private Source() {}
    }
}
