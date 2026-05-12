--liquibase formatted sql
--changeset orinuno:20260512100100_create_episode_video

-- ADR 0021 Block B3-a — L2 episode_video in meter's catalog DB.
--
-- Parallel installation alongside `orinuno.episode_video`. Schema identical
-- to orinuno-app's 20260502050100_create_episode_video.sql — FK target
-- (episode_source in same schema) doesn't cross context boundaries, so no
-- adjustment needed.
--
-- One row per (episode_source.id, quality). Decoder writes append-only;
-- consumers query by source_id + quality.

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
