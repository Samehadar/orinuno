--liquibase formatted sql
--changeset orinuno:20260507010200_create_jutsu_sync_state

-- Singleton-row table: id is always 1. JutsuCatalogSyncService bootstraps the row on first use.
CREATE TABLE IF NOT EXISTS jutsu_sync_state (
    id                       INT        NOT NULL,
    last_full_crawl_at       DATETIME   NULL,
    last_notice_cursor       INT        NULL,
    notice_walk_in_progress  BOOLEAN    NOT NULL DEFAULT FALSE,
    updated_at               DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP
                                        ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    CONSTRAINT chk_jutsu_sync_state_singleton CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO jutsu_sync_state (id, last_full_crawl_at, last_notice_cursor, notice_walk_in_progress)
VALUES (1, NULL, NULL, FALSE)
ON DUPLICATE KEY UPDATE id = id;
