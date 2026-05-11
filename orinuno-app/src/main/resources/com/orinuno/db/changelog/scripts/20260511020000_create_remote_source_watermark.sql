--liquibase formatted sql

--changeset orinuno:20260511020000_create_remote_source_watermark splitStatements:false
-- ADR 0018 Phase 2.11 — watermark table for *RemoteEventPoller beans.
--
-- One row per per-source service we poll. Stores the high-water mark on
-- Provenance.fetchedAt so the next /api/v1/source-events/ready call
-- requests only newer events. Source identifier is the open string from
-- SourceIdentifier.sourceType ("kodik", later "jutsu", "aniboom", …),
-- pinned to that low-cardinality wire form rather than a synthetic id so
-- ops can SELECT/UPDATE by hand without a lookup.
--
-- Single-row updates with PK lookups, no concurrent writers — InnoDB
-- defaults are fine, no extra indexes needed.

CREATE TABLE IF NOT EXISTS `orinuno_remote_source_watermark` (
    `source_type`         VARCHAR(32)   NOT NULL,
    `last_fetched_at`     DATETIME(6)   NULL,
    `last_polled_at`      DATETIME(6)   NULL,
    `last_event_count`    INT           NOT NULL DEFAULT 0,
    `last_error`          VARCHAR(512)  NULL,
    PRIMARY KEY (`source_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
--rollback DROP TABLE IF EXISTS `orinuno_remote_source_watermark`;
