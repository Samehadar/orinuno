--liquibase formatted sql
--changeset orinuno:20260502030000_create_kodik_calendar_state

-- CAL-6 — per-anime snapshot of the last seen calendar entry.
--
-- The Kodik public calendar (https://dumps.kodikres.com/calendar.json) refreshes
-- every few minutes. Each entry tells us "when is the next episode of this anime
-- due / how many episodes have aired / what's the airing status / latest score".
--
-- Until CAL-6 the calendar service was query-only: each /api/v1/calendar call
-- returned a snapshot; nobody noticed when an anime's status flipped from
-- "ongoing" to "released", or when the next-episode timestamp moved. CAL-6
-- closes that gap by:
--
--   1. Storing the last-known state for every anime here, keyed by Shikimori id
--      (which is what Kodik uses to identify rows in the calendar). Updated on
--      every successful watch cycle.
--   2. Diffing the new fetch against this table and writing a row to
--      `kodik_calendar_outbox` for every observed change (see the next
--      changeset).
--
-- Downstream consumers (parser-kodik, frontend, notifications, etc.) read the
-- outbox to react to changes without having to poll the full calendar dump.

CREATE TABLE IF NOT EXISTS kodik_calendar_state (
    shikimori_id      VARCHAR(64)  NOT NULL,
    next_episode      INT          NULL,
    next_episode_at   DATETIME(3)  NULL,
    episodes_aired    INT          NULL,
    episodes_total    INT          NULL,
    status            VARCHAR(32)  NULL,
    score             DECIMAL(5,2) NULL,
    kind              VARCHAR(32)  NULL,
    aired_on          DATE         NULL,
    released_on       DATE         NULL,
    first_seen_at     DATETIME(3)  NOT NULL,
    last_seen_at      DATETIME(3)  NOT NULL,
    PRIMARY KEY (shikimori_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
