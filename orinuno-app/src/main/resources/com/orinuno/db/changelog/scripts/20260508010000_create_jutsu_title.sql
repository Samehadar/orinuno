--liquibase formatted sql
--changeset orinuno:20260508010000_create_jutsu_title

-- ARCH-0016 P1a — per-source raw cache (L1) for jut.su catalog titles.
--
-- Mirrors what `JutsuCatalogEntry` (catalog page card) and `JutsuAnimeInfo`
-- (info page chrome) expose; one row per `slug`. The `JutsuCatalogSyncService`
-- (added in Step 2) upserts here on every full-crawl page and every notice-feed
-- delta. The REST surface in `JutsuApiController` (cut over in Step 3) reads
-- from this table by default and only falls back to a live SDK call when the
-- row is missing / stale, behind rate-limit + negative-cache + kill-switch
-- guards (ADR 0016 §"REST cutover for jut.su: hybrid-fallback with mandatory
-- guards").
--
-- Identity / nullability rules:
--   * `slug` is the natural key (jut.su path segment, e.g. `naruto`,
--     `onepuunchman`). Treated as canonical because catalog cards always carry
--     it but routinely omit `site_id`.
--   * `site_id` (numeric `id="anime_fs_29"` value) is best-effort: catalog
--     cards may render without it (`-1` in the SDK) and we want to allow that
--     without forcing a sentinel into the unique key.
--   * Genre / type / year columns mirror the SDK's slug enums as comma-joined
--     strings — matches the parser output exactly so a re-fetch is a clean
--     overwrite (no JSON parsing required when reading).
--   * `original_title`, `synopsis`, `thumbnail_url` are nullable; empty page
--     chrome is normal for older entries.
--   * `total_seasons` / `total_episodes` are derived from `JutsuAnimeInfo` and
--     are NULL until the slug has been visited via the info-page client (full
--     crawl only fetches catalog pages; per-slug info is fetched on demand or
--     by a follow-up worker).
--
-- Idempotent upsert: callers pass the full row; `first_seen_at` is protected
-- via `COALESCE` in the SQL so re-fetches don't shift the discovery timestamp.

CREATE TABLE IF NOT EXISTS jutsu_title (
    slug              VARCHAR(255)  NOT NULL,
    site_id           INT           NULL,
    title             VARCHAR(512)  NOT NULL,
    original_title    VARCHAR(512)  NULL,
    synopsis          TEXT          NULL,
    thumbnail_url     VARCHAR(1024) NULL,
    year_bucket       VARCHAR(32)   NULL,
    genres_csv        VARCHAR(1024) NULL,
    types_csv         VARCHAR(1024) NULL,
    catalog_episode_count INT       NULL,
    catalog_movie_count   INT       NULL,
    info_total_seasons    INT       NULL,
    info_total_episodes   INT       NULL,
    info_fetched_at   DATETIME(3)   NULL,
    catalog_fetched_at DATETIME(3)  NULL,
    first_seen_at     DATETIME(3)   NOT NULL,
    last_seen_at      DATETIME(3)   NOT NULL,
    PRIMARY KEY (slug),
    KEY idx_jutsu_title_site_id (site_id),
    KEY idx_jutsu_title_last_seen (last_seen_at),
    KEY idx_jutsu_title_info_fetched (info_fetched_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
