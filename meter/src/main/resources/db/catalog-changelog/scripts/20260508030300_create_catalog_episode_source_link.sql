--liquibase formatted sql
--changeset orinuno:20260508030300_create_catalog_episode_source_link

-- ARCH-0016 P1b — M:N link between L3 canonical episodes and L2 per-source
-- episode pointers (`episode_source` from ADR 0005).
--
-- One row per (catalog_episode_id, episode_source_id) pair. This is the table
-- MultiSourceRanker queries when answering "show me every source available
-- for this canonical episode" — the join goes:
--
--   catalog_episode  ←  catalog_episode_source_link  →  episode_source
--                                                         (ADR 0005, in `core`)
--
-- The `episode_source` row already carries provider-agnostic decode metadata
-- (source_type, source_id, last_decoded_at, etc); we don't duplicate it here.
-- This table is purely the join.
--
-- Cross-context references: episode_source_id points into the `core` context's
-- table. Per ADR 0016 zoning rules, no FOREIGN KEY constraint — the link is
-- soft. CatalogIngestionService (P1b Step 1.C) is responsible for keeping the
-- two sides consistent inside the same transaction as the canonical episode
-- upsert.
--
-- created_at is enough — links are append-only at the application level. If
-- a per-source episode goes away (Kodik drops a translation), the link row
-- stays until the next garbage-collection pass; reads tolerate dangling links
-- by left-joining episode_source and skipping NULL rows.

CREATE TABLE IF NOT EXISTS catalog_episode_source_link (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    catalog_episode_id  BIGINT      NOT NULL,
    episode_source_id   BIGINT      NOT NULL,
    created_at          DATETIME(3) NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uq_catalog_link_episode_source (catalog_episode_id, episode_source_id),
    KEY idx_catalog_link_source (episode_source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
