--liquibase formatted sql
--changeset orinuno:20260508030200_create_catalog_episode

-- ARCH-0016 P1b — canonical episode tree for L3.
--
-- One row per (content_id, season, episode) tuple. The actual per-source
-- episodes (kodik_episode_variant, jutsu_episode, future sibnet rows) attach
-- to this canonical row via `catalog_episode_source_link` (next migration).
--
-- Why a canonical episode table on top of the per-source ones:
-- - the P2 `/api/v1/catalog/content/{id}/episodes` surface needs a single
--   ordered episode tree even when the same physical episode is mirrored
--   across kodik + jut.su + sibnet;
-- - MultiSourceRanker (in the canonical context after this PR) operates at
--   the canonical-episode level, so it needs an authoritative key to dedupe
--   on; (content_id, season, episode) is that key;
-- - per-source rows can lack a season ("season-less" listings on jut.su map
--   to season=1 by convention); the canonical row is where we collapse all
--   those source quirks to one number.
--
-- season=0 is reserved for "specials / OVA / movies attached to a series".
-- The L3 schema doesn't enforce a constraint on it because Kodik and jut.su
-- both occasionally use 0 for legitimately-numbered prequel arcs.
--
-- No FK to catalog_content per the same zoning rule as
-- catalog_content_external_id. Application layer enforces it.

CREATE TABLE IF NOT EXISTS catalog_episode (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    content_id      BIGINT       NOT NULL,
    season          INT          NOT NULL,
    episode         INT          NOT NULL,
    title           VARCHAR(512) NULL,
    air_date        DATE         NULL,
    created_at      DATETIME(3)  NOT NULL,
    updated_at      DATETIME(3)  NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uq_catalog_episode_content_season_episode (content_id, season, episode),
    KEY idx_catalog_episode_air_date (air_date),
    KEY idx_catalog_episode_updated  (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
