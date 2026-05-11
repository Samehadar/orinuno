--liquibase formatted sql
--changeset orinuno:20260508030100_create_catalog_content_external_id

-- ARCH-0016 P1b — normalised external-id attachments for catalog_content.
--
-- Source of truth for "which canonical row owns external id X of type T".
-- Unique on (source_type, external_id) gives the resolver an O(1) reverse
-- lookup ("does shikimori:54321 already point at a canonical row?") and the
-- P2 REST `?external_id=...` query a single index hit.
--
-- The same canonical content can hold many external-id rows of the same
-- source_type: a Kodik title can have multiple kodik raw ids (different
-- translations or series-level aliases), all attached to one canonical row.
-- That's why the (content_id, source_type) tuple is NOT unique.
--
-- source_type vocabulary (managed by CatalogSourceType in Java):
--   KODIK        — Kodik raw id (kodik_content.id passthrough)
--   JUTSU        — jut.su slug (jutsu_title.slug passthrough)
--   SHIKIMORI    — shikimori.one numeric id
--   MAL          — myanimelist.net numeric id
--   KINOPOISK    — kinopoisk.ru numeric id
--   IMDB         — imdb.com tt-prefixed id
--   MDL          — mydramalist.com slug
--   TMDB         — themoviedb.org numeric id
--
-- No FOREIGN KEY back to catalog_content even though the relationship is
-- mandatory — ADR 0016 zoning rule "no cross-context FK" applies even within
-- the same context for symmetry with how cross-context tables (`kodik_*`,
-- `jutsu_*`) reference canonical rows. Application layer enforces referential
-- integrity by always inserting / deleting through CatalogIdentityResolver in
-- the same transaction as the catalog_content upsert.

CREATE TABLE IF NOT EXISTS catalog_content_external_id (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    content_id      BIGINT       NOT NULL,
    source_type     VARCHAR(32)  NOT NULL,
    external_id     VARCHAR(255) NOT NULL,
    created_at      DATETIME(3)  NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uq_catalog_external_source_id (source_type, external_id),
    KEY idx_catalog_external_content (content_id),
    KEY idx_catalog_external_content_source (content_id, source_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
