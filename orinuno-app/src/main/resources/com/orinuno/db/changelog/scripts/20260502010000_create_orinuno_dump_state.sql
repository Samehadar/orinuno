--liquibase formatted sql
--changeset orinuno:20260502010000_create_orinuno_dump_state

-- DUMP-1 — persistent state for the public Kodik dump endpoints
-- (https://dumps.kodikres.com/{calendar,serials,films}.json).
-- See orinuno-radar/tracking/dumps.yml for the source list.
--
-- One row per logical dump; updated on every poll regardless of whether
-- the upstream content changed (so we can answer "when did we last
-- successfully reach the dump?" via the health endpoint).

CREATE TABLE IF NOT EXISTS orinuno_dump_state (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    dump_name               VARCHAR(64) NOT NULL,
    dump_url                VARCHAR(512) NOT NULL,
    last_checked_at         DATETIME NULL,
    last_changed_at         DATETIME NULL,
    last_status             INT NULL,
    last_error_message      TEXT NULL,
    etag                    VARCHAR(255) NULL,
    last_modified_header    VARCHAR(64) NULL,
    content_length          BIGINT NULL,
    consecutive_failures    INT NOT NULL DEFAULT 0,

    UNIQUE KEY uk_dump_name (dump_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
