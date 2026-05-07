--liquibase formatted sql
--changeset orinuno:20260507010100_create_jutsu_episode

-- No FOREIGN KEY to jutsu_title — ADR 0016 zoning rule:
-- "No cross-context FOREIGN KEY constraints" applies inside the jutsu context too;
-- soft references keep an eventual per-source service split a refactor, not a rewrite.
-- The PRIMARY KEY (title_slug, season, episode) prefix already satisfies any
-- WHERE title_slug = ? lookup, so we do NOT add a redundant idx_jutsu_episode_slug.
CREATE TABLE IF NOT EXISTS jutsu_episode (
    title_slug       VARCHAR(255)   NOT NULL,
    season           INT            NOT NULL,
    episode          INT            NOT NULL,
    embed_url        VARCHAR(1024)  NULL,
    video_qualities  JSON           NULL,
    last_synced_at   DATETIME       NULL,

    PRIMARY KEY (title_slug, season, episode),
    INDEX idx_jutsu_episode_last_synced_at (last_synced_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
