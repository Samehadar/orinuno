--liquibase formatted sql
--changeset orinuno:20260502040000_add_decode_method_to_episode_variant

-- DECODE-8 — track which decoder produced each variant's mp4_link.
--
-- Until DECODE-8 every variant was decoded via the regex/JS-based
-- KodikVideoDecoderService. When Kodik changes the player JS structure
-- the regex breaks and ALL decodes fail until we ship a hot-fix patch
-- (see docs/quirks-and-hacks.md, "Player JS file naming is now type-dependent").
--
-- DECODE-8 introduces a Playwright network-sniff fallback that opens the
-- player in a headless browser and intercepts the CDN video URL directly,
-- bypassing the regex layer entirely. This column records which method
-- produced the current mp4_link so we can:
--
--   - Prometheus-track REGEX vs SNIFF ratio (high SNIFF ratio = regex broken).
--   - Re-decode SNIFF rows during a backfill once the regex is fixed,
--     because regex paths support multi-quality maps and SNIFF only
--     captures the master playlist Kodik feeds the browser.

ALTER TABLE kodik_episode_variant
    ADD COLUMN decode_method VARCHAR(16) NULL AFTER mp4_link;
