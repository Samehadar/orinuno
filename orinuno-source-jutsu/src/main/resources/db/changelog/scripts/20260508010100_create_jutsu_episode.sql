--liquibase formatted sql
--changeset orinuno:20260508010100_create_jutsu_episode

-- ARCH-0016 P1a — per-source raw cache (L1) for jut.su episode listings.
--
-- One row per (slug, season, episode). Populated by `JutsuCatalogSyncService`
-- when it fetches an anime info page via `JutsuClient.getAnimeInfo(slug)` —
-- single-season anime collapse into `season=1` to match the SDK's
-- `JutsuAnimeInfo` / `JutsuSeason` model (single-season pages have no `<h2>`,
-- so the parser synthesises season-1).
--
-- Why FK-on-slug: `jutsu_episode` is bound to `jutsu_title` exactly as
-- `kodik_episode_variant` is bound to `kodik_content` — they are the
-- per-source children of the same context (jut.su). This cross-table FK is
-- INSIDE the `jutsu` bounded context (ADR 0016 §"Boundary discipline (zoning
-- rules)"), so it's allowed; FKs to OTHER contexts (e.g. `catalog_content`)
-- are explicitly forbidden by the same rules.

CREATE TABLE IF NOT EXISTS jutsu_episode (
    slug              VARCHAR(255)  NOT NULL,
    season            INT           NOT NULL,
    episode           INT           NOT NULL,
    label             VARCHAR(512)  NULL,
    relative_url      VARCHAR(1024) NOT NULL,
    paywalled         TINYINT(1)    NULL,
    discovered_at     DATETIME(3)   NOT NULL,
    last_seen_at      DATETIME(3)   NOT NULL,
    PRIMARY KEY (slug, season, episode),
    KEY idx_jutsu_episode_last_seen (last_seen_at),
    CONSTRAINT fk_jutsu_episode_title FOREIGN KEY (slug)
        REFERENCES jutsu_title (slug) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
