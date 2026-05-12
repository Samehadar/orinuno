/*
 * EpisodeSource — ADR 0021 Block B3-a (L2 in meter).
 *
 * Provider-agnostic episode row keyed by (content_id, season, episode,
 * translator_id, provider). Mirror of orinuno-app's legacy
 * com.orinuno.model.EpisodeSource, but content_id now references
 * catalog_content.id (L3 canonical) instead of kodik_content.id (L1 per-source).
 */
package com.orinuno.meter.catalog.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EpisodeSource {

    private Long id;
    private Long contentId;
    private Integer season;
    private Integer episode;
    private String translatorId;
    private String translatorName;
    private String provider;
    private String sourceUrl;
    private String sourceType;
    private LocalDateTime discoveredAt;
    private LocalDateTime lastSeenAt;

    /** Provider discriminator. Strings keep the schema flexible across releases. */
    public static final class Provider {
        public static final String KODIK = "KODIK";
        public static final String SIBNET = "SIBNET";
        public static final String ANIBOOM = "ANIBOOM";
        public static final String JUTSU = "JUTSU";

        private Provider() {}
    }
}
