--liquibase formatted sql
--changeset orinuno:20260502060000_create_kodik_content_enrichment

-- META-1 (ADR 0010) — metadata enrichment from external sources (Shikimori, Kinopoisk, MAL).
--
-- One row per (content_id, source) tuple. Lossless: raw payload is stored as a JSON column so we
-- can re-derive structured fields after a schema iteration without re-fetching upstream.
--
-- Refresh cadence is daily by default; the EnrichmentScheduler skips rows whose
-- last_refreshed_at is younger than the configured TTL.

CREATE TABLE IF NOT EXISTS kodik_content_enrichment (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    content_id         BIGINT       NOT NULL,
    source             VARCHAR(32)  NOT NULL,
    external_id        VARCHAR(64)  NULL,
    title_native       VARCHAR(512) NULL,
    title_english      VARCHAR(512) NULL,
    title_russian      VARCHAR(512) NULL,
    description        TEXT         NULL,
    score              DECIMAL(5,2) NULL,
    raw_payload        JSON         NULL,
    fetched_at         DATETIME     NOT NULL,
    last_refreshed_at  DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_kodik_content_enrichment (content_id, source),
    KEY idx_enrichment_source (source),
    CONSTRAINT fk_enrichment_content FOREIGN KEY (content_id) REFERENCES kodik_content (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
