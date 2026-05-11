--liquibase formatted sql
--changeset orinuno:20260511000000_backfill_episode_video_from_kodik_variant

-- ADR 0018 Phase 0.4a — backfill legacy Kodik decode rows into the
-- provider-agnostic episode_source + episode_video schema.
--
-- Context: KodikEpisodeDualWriteService.mirrorDecode() has been mirroring every
-- new Kodik decode into episode_source + episode_video since ADR 0005 Phase A
-- landed. Any kodik_episode_variant row with a populated mp4_link that pre-dates
-- the dual-write deployment, or whose mirror failed silently (the dual-write
-- catches and logs errors instead of failing the primary write), still lives
-- only in kodik_episode_variant.
--
-- This migration is the closing step of the expand-contract: every populated
-- legacy mp4_link is mirrored into episode_video so subsequent code can switch
-- its read-path off kodik_episode_variant.mp4_link safely (Phase 0.4b). The
-- column drop happens in Phase 0.4c.
--
-- Idempotent: both INSERT statements use ON DUPLICATE KEY UPDATE so a re-run
-- (e.g. Testcontainers fresh DB plus this changeset already executed) is a
-- no-op on already-mirrored rows.

-- Step 1: ensure an episode_source row exists for every Kodik variant with a
-- decoded mp4_link. translator_id is the stringified translation_id to match
-- the convention from KodikEpisodeDualWriteService.buildSource.
INSERT INTO episode_source
    (content_id, season, episode, translator_id, translator_name,
     provider, source_url, discovered_at, last_seen_at)
SELECT
    v.content_id,
    v.season_number,
    v.episode_number,
    CAST(v.translation_id AS CHAR) COLLATE utf8mb4_unicode_ci,
    v.translation_title,
    'KODIK',
    COALESCE(v.kodik_link, v.mp4_link),
    COALESCE(v.created_at, NOW()),
    COALESCE(v.updated_at, NOW())
FROM kodik_episode_variant v
WHERE v.mp4_link IS NOT NULL
ON DUPLICATE KEY UPDATE
    last_seen_at = GREATEST(episode_source.last_seen_at, COALESCE(VALUES(last_seen_at), episode_source.last_seen_at));

-- Step 2: ensure an episode_video row exists for every (source_id, quality)
-- carrying a decoded mp4_link. Quality fallback 'unknown' mirrors the bucket
-- KodikVideoDecoderService used pre-PLAYER-1 when no numeric ladder was
-- returned. decode_failed_count defaults to 0 — historical failure counts
-- never lived in kodik_episode_variant so there is nothing to preserve.
INSERT INTO episode_video
    (source_id, quality, video_url, decoded_at, decode_method, decode_failed_count)
SELECT
    es.id,
    COALESCE(v.quality, 'unknown'),
    v.mp4_link,
    v.mp4_link_decoded_at,
    v.decode_method,
    0
FROM kodik_episode_variant v
INNER JOIN episode_source es
    ON es.content_id = v.content_id
    AND es.season = v.season_number
    AND es.episode = v.episode_number
    AND es.translator_id = CAST(v.translation_id AS CHAR) COLLATE utf8mb4_unicode_ci
    AND es.provider = 'KODIK'
WHERE v.mp4_link IS NOT NULL
ON DUPLICATE KEY UPDATE
    video_url = COALESCE(VALUES(video_url), episode_video.video_url),
    decoded_at = COALESCE(VALUES(decoded_at), episode_video.decoded_at),
    decode_method = COALESCE(VALUES(decode_method), episode_video.decode_method);
