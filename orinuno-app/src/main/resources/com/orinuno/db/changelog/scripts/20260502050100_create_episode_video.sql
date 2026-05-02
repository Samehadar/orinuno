--liquibase formatted sql
--changeset orinuno:20260502050100_create_episode_video

-- PLAYER-1 (ADR 0005) Phase A — provider-specific decoded video URL(s).
--
-- One row per (source_id, quality). A single episode_source typically has
-- multiple episode_video rows (Kodik returns 240/360/480/720; Aniboom may
-- return a master + variants; Sibnet returns a single direct .mp4 — quality
-- 'auto' or '720').
--
-- decode_method preserves the DECODE-8 discriminator (REGEX vs SNIFF, plus
-- new providers will use PROVIDER_API).
--
-- ttl_seconds is provider-specific (Aniboom CDN tokens expire ~6h; Sibnet
-- direct URLs do not expire => NULL).

CREATE TABLE IF NOT EXISTS episode_video (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    source_id           BIGINT       NOT NULL,
    quality             VARCHAR(16)  NOT NULL,
    video_url           VARCHAR(2048) NULL,
    video_format        VARCHAR(64)  NULL,
    decoded_at          DATETIME     NULL,
    decode_method       VARCHAR(16)  NULL,
    decode_failed_count INT          NOT NULL DEFAULT 0,
    decode_last_error   VARCHAR(512) NULL,
    ttl_seconds         INT          NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_episode_video (source_id, quality),
    KEY idx_episode_video_decoded_at (decoded_at),
    CONSTRAINT fk_episode_video_source FOREIGN KEY (source_id) REFERENCES episode_source (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
