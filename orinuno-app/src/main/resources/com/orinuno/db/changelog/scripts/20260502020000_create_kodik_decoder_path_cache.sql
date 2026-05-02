--liquibase formatted sql
--changeset orinuno:20260502020000_create_kodik_decoder_path_cache

-- DECODE-2 — persistent per-netloc decoder path cache.
--
-- The KodikVideoDecoderService brute-force-discovers the video-info POST path
-- (one of /ftor, /kor, /gvi, /seria, or whatever Kodik renames them to) by
-- parsing the player JS on first contact with a netloc, then memoises the
-- successful path so subsequent decodes for the same netloc skip the discovery
-- step. Until DECODE-2 the cache lived only in a JVM-local ConcurrentMap, so
-- every restart paid the discovery + brute-force cost again across all known
-- netlocs (kodikplayer.com, kodik.cc, kodikv.cc, plus future ones from CAL-6).
--
-- This table makes the cache survive restarts. One row per host, lower-cased
-- and trimmed of port. Updated on every successful POST so we can also see
-- per-netloc hit counts and freshness from the database side without scraping
-- Prometheus.

CREATE TABLE IF NOT EXISTS kodik_decoder_path_cache (
    netloc           VARCHAR(255) NOT NULL,
    video_info_path  VARCHAR(64)  NOT NULL,
    hit_count        BIGINT       NOT NULL DEFAULT 0,
    first_seen_at    DATETIME     NOT NULL,
    last_seen_at     DATETIME     NOT NULL,
    PRIMARY KEY (netloc)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
