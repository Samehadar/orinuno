--liquibase formatted sql
--changeset orinuno:20260508050000_create_jutsu_film

-- ARCH-0016 P1a — per-source raw cache (L1) for jut.su full-length movies
-- ("Полнометражные фильмы"). Films are a parallel concept to seasons /
-- episodes on jut.su: they share the same {short-btn video the_hildi} CSS
-- selector but live under URLs of the form /{slug}/film-N.html, separate
-- from /{slug}/episode-N.html and /{slug}/season-N/episode-M.html.
--
-- One row per (slug, film_index). Populated by `JutsuCatalogSyncService`
-- when it fetches an anime info page via `JutsuClient.getAnimeInfo(slug)`
-- and the page advertises films in the dedicated "Полнометражные фильмы"
-- block.
--
-- Why a separate table (rather than overloading jutsu_episode with a
-- sentinel season=0): this is the modelling choice we made in the
-- "separate-films" path of the jut.su SDK refactor — films don't have
-- seasons, don't reuse episode numbering, and need to be surfaced to the
-- demo UI as a distinct section. Mixing them into jutsu_episode would
-- force every read consumer to filter on a magic season value forever.
--
-- FK-on-slug rationale matches jutsu_episode: films are bound to
-- jutsu_title exactly as episodes are. This cross-table FK is INSIDE the
-- `jutsu` bounded context (ADR 0016 §"Boundary discipline") so it's
-- allowed; FKs to OTHER contexts (e.g. catalog_content) are forbidden.

CREATE TABLE IF NOT EXISTS jutsu_film (
    slug              VARCHAR(255)  NOT NULL,
    film_index        INT           NOT NULL,
    label             VARCHAR(512)  NULL,
    relative_url      VARCHAR(1024) NOT NULL,
    paywalled         TINYINT(1)    NULL,
    discovered_at     DATETIME(3)   NOT NULL,
    last_seen_at      DATETIME(3)   NOT NULL,
    PRIMARY KEY (slug, film_index),
    KEY idx_jutsu_film_last_seen (last_seen_at),
    CONSTRAINT fk_jutsu_film_title FOREIGN KEY (slug)
        REFERENCES jutsu_title (slug) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
