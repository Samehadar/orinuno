--liquibase formatted sql
--changeset orinuno:20260508010200_create_jutsu_sync_state

-- ARCH-0016 P1a — singleton cursor row for `JutsuCatalogSyncService`.
--
-- One row, `id = 1`. Tracks where the full-catalog crawl is (so we can resume
-- after a crash) and where the notice-feed walker is (so the incremental
-- delta loop doesn't re-process the same backlog every tick). Also surfaces
-- a denormalised `total_titles_synced` counter for the health endpoint /
-- Prometheus gauge so dashboards don't have to `COUNT(*)` the L1 table.
--
-- Singleton enforced via the `chk_jutsu_sync_state_singleton` CHECK
-- constraint; rejecting `id != 1` at insert keeps the row unique without an
-- artificial UNIQUE on a one-row table. MySQL 8 enforces CHECK constraints
-- (8.0.16+) so we don't need a trigger.
--
-- All cursors are nullable so a fresh deployment has no `NOT NULL` rows to
-- bootstrap before the worker runs for the first time.

CREATE TABLE IF NOT EXISTS jutsu_sync_state (
    id                          INT          NOT NULL DEFAULT 1,
    full_crawl_started_at       DATETIME(3)  NULL,
    full_crawl_completed_at     DATETIME(3)  NULL,
    full_crawl_last_page        INT          NULL,
    full_crawl_total_pages      INT          NULL,
    notice_cursor               INT          NULL,
    notice_cursor_updated_at    DATETIME(3)  NULL,
    notice_last_walked_at       DATETIME(3)  NULL,
    total_titles_synced         BIGINT       NOT NULL DEFAULT 0,
    last_error                  VARCHAR(1024) NULL,
    last_error_at               DATETIME(3)  NULL,
    updated_at                  DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_jutsu_sync_state_singleton CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
