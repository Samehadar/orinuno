--liquibase formatted sql
--changeset orinuno:20260502030100_create_kodik_calendar_outbox

-- CAL-6 — append-only outbox of detected calendar deltas.
--
-- See the previous changeset for context. The watcher writes one row per
-- observed delta:
--
--   change_type   ∈ { NEW_ANIME, NEXT_EPISODE_ADVANCED, NEXT_EPISODE_RESCHEDULED,
--                     EPISODES_AIRED_INCREASED, STATUS_CHANGED, SCORE_CHANGED,
--                     RELEASED_ON_SET }
--   old_value     prior snapshot of the affected field (NULL for NEW_ANIME)
--   new_value     the value as observed in the fresh fetch
--   detected_at   when the watcher noticed (server clock)
--   consumer_id   nullable per-consumer cursor (parser-kodik / web / …)
--   consumed_at   nullable timestamp when consumer marked it processed
--
-- Two indexes back the two main read patterns:
--   - "Replay all events since a watermark" (`detected_at`)
--   - "All events for one anime" (`shikimori_id`)
--
-- We deliberately do NOT delete or partition — the table is bounded by anime ×
-- changes-per-anime which is sub-million for the foreseeable future. Pruning is
-- a follow-up if it ever matters.

CREATE TABLE IF NOT EXISTS kodik_calendar_outbox (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    shikimori_id VARCHAR(64)  NOT NULL,
    change_type  VARCHAR(48)  NOT NULL,
    old_value    VARCHAR(255) NULL,
    new_value    VARCHAR(255) NULL,
    detected_at  DATETIME(3)  NOT NULL,
    consumer_id  VARCHAR(64)  NULL,
    consumed_at  DATETIME(3)  NULL,
    PRIMARY KEY (id),
    KEY idx_calendar_outbox_detected_at (detected_at),
    KEY idx_calendar_outbox_shikimori_id (shikimori_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
