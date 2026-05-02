--liquibase formatted sql
--changeset orinuno:20260502050000_create_episode_source

-- PLAYER-1 (ADR 0005) Phase A — provider-agnostic episode source row.
--
-- Until PLAYER-1, every "I have a URL for episode X" claim lived inside
-- kodik_episode_variant, hard-coded to provider=Kodik. Once we add Sibnet,
-- Aniboom, JutSu we need a place to express "this URL is from provider P
-- for the same (content, season, episode) tuple".
--
-- One row per (content, season, episode, translator_id, provider). Idempotent
-- on re-fetch: provider parsers upsert rather than insert.

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
    CONSTRAINT fk_episode_source_content FOREIGN KEY (content_id) REFERENCES kodik_content (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
