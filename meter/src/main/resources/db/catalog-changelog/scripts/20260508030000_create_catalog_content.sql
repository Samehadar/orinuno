--liquibase formatted sql
--changeset orinuno:20260508030000_create_catalog_content

-- ARCH-0016 P1b — universal canonical catalog (L3).
--
-- One row per canonical title. Films, series, and anime all live here. Identity
-- columns (kinopoisk_id, imdb_id, …) are denormalised hot-path indexes for the
-- canonical REST surface that ships in P2 — the source of truth for *any*
-- external id binding is `catalog_content_external_id` (created in the next
-- migration), which can attach an unbounded number of source-typed ids to the
-- same canonical content. The identity columns are kept in sync inside the
-- same transaction by `CatalogIdentityResolver` (P1b Step 1.B).
--
-- This is a separate bounded context (`catalog`) per ADR 0016 zoning rules: no
-- FOREIGN KEYs out of this table to anything outside the context, and consumers
-- in other contexts (`kodik`, `jutsu`, …) only ever reach in via
-- `CatalogPublicApi`. Cross-context references (e.g. from `kodik_content` to a
-- canonical row) stay soft — a column with the catalog id, no FK constraint —
-- so a future per-source service split (Layout B) is a refactor instead of a
-- rewrite.

CREATE TABLE IF NOT EXISTS catalog_content (
    id              BIGINT       NOT NULL AUTO_INCREMENT,

    -- Display chrome. NULL until at least one ingestion source provided it.
    title_ru        VARCHAR(512) NULL,
    title_en        VARCHAR(512) NULL,

    -- "movie" / "series" / "anime". Stored as VARCHAR (not MySQL ENUM) so we
    -- can extend the vocabulary without an ALTER. The Java side maps it to a
    -- Java enum `CatalogContentKind`; unknown values surface as
    -- `CatalogContentKind.UNKNOWN`.
    kind            VARCHAR(16)  NOT NULL,
    year            INT          NULL,

    -- Denormalised identity columns. Each is the canonical external id of its
    -- type for this catalog row — the tie-break when the resolver finds
    -- multiple candidates is "first writer wins" (see CatalogIdentityResolver
    -- P1b Step 1.B). The full set of attached ids lives in
    -- `catalog_content_external_id`.
    shikimori_id    VARCHAR(64)  NULL,
    mal_id          VARCHAR(64)  NULL,
    imdb_id         VARCHAR(64)  NULL,
    kinopoisk_id    VARCHAR(64)  NULL,
    mdl_id          VARCHAR(64)  NULL,
    tmdb_id         VARCHAR(64)  NULL,

    created_at      DATETIME(3)  NOT NULL,
    updated_at      DATETIME(3)  NOT NULL,

    PRIMARY KEY (id),
    -- Hot-path lookup indexes for the resolver and the P2 REST surface. They
    -- are NOT unique because legitimate duplicates exist (a movie franchise
    -- can share a kinopoisk_id across reissues with different shikimori_ids);
    -- the resolver enforces uniqueness at the application layer using lookup
    -- order shikimori → mal → imdb → kinopoisk → mdl → tmdb.
    KEY idx_catalog_content_shikimori (shikimori_id),
    KEY idx_catalog_content_mal       (mal_id),
    KEY idx_catalog_content_imdb      (imdb_id),
    KEY idx_catalog_content_kinopoisk (kinopoisk_id),
    KEY idx_catalog_content_mdl       (mdl_id),
    KEY idx_catalog_content_tmdb      (tmdb_id),
    KEY idx_catalog_content_kind_year (kind, year),
    KEY idx_catalog_content_updated   (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
