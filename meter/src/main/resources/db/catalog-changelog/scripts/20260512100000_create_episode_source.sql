--liquibase formatted sql
--changeset orinuno:20260512100000_create_episode_source

-- ADR 0021 Block B3-a — L2 episode_source in meter's catalog DB.
--
-- Parallel installation alongside the legacy `orinuno.episode_source` table
-- that `KodikEpisodeDualWriteService` writes today. Once B1 lands and orinuno-app
-- stops dual-writing, the legacy table can be dropped (B3-b).
--
-- Diff from orinuno-app's 20260502050000_create_episode_source.sql:
--   - FK retargeted from kodik_content (L1, in orinuno_source_kodik) to
--     catalog_content (L3, same schema). The L2 → L3 link is the architectural
--     contract going forward; the L2 → L1 link was a holdover from when
--     orinuno-app owned all three layers in one schema.
--   - All other columns identical; downstream consumers (MultiSourceRanker,
--     StreamController) treat episode_source rows by `provider` discriminator
--     anyway, not by the FK target.

CREATE TABLE IF NOT EXISTS episode_source (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    content_id        BIGINT       NOT NULL,
    season            INT          NOT NULL,
    episode           INT          NOT NULL,
    translator_id     VARCHAR(64)  NULL,
    translator_name   VARCHAR(255) NULL,
    provider          VARCHAR(32)  NOT NULL,
    source_url        VARCHAR(1024) NOT NULL,
    source_type       VARCHAR(32)  NULL,
    discovered_at     DATETIME     NOT NULL,
    last_seen_at      DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_episode_source (content_id, season, episode, translator_id, provider),
    KEY idx_episode_source_provider (provider),
    KEY idx_episode_source_content (content_id),
    CONSTRAINT fk_episode_source_catalog_content FOREIGN KEY (content_id) REFERENCES catalog_content (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
