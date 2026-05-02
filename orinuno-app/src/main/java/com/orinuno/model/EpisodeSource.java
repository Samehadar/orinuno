package com.orinuno.model;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PLAYER-1 (ADR 0005) — provider-agnostic episode source row. One row per (content, season,
 * episode, translator, provider) tuple. Replaces the Kodik-only {@code kodik_episode_variant}
 * during the dual-write phase.
 */
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
