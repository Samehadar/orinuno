--liquibase formatted sql
--changeset orinuno:20260511010000_drop_kodik_variant_l2_columns

-- ADR 0018 Phase 0.4c — finish the kodik_episode_variant L1+L2 expand-contract.
--
-- Phase 0.4a backfilled every decoded mp4 into episode_source + episode_video.
-- Phase 0.4b switched the three read predicates onto episode_video. With both
-- write- and read-paths now anchored on the provider-agnostic schema, the
-- legacy L2 columns on kodik_episode_variant are dead weight and a tripping
-- hazard for the per-source service extraction (Phase 2): they make the
-- "variant table is L1 only" boundary easy to break by accident.
--
-- Drops the two L2 indexes first (MySQL requires it before DROP COLUMN can
-- proceed on indexed columns), then drops the three columns. decode_method
-- has no index. local_filepath stays — Phase 0.4 scope was only mp4_link
-- and its bookkeeping; local_filepath is a separate L2 hybrid (downloaded
-- file path) tracked in ADR 0016 §"Known tech debt" for a later phase.
--
-- After this migration the variant table is L1-only: kodik_link (iframe URL)
-- + identity columns. Decoded URLs live in episode_video, TTL-managed by
-- the decoder pipeline + the future JIT-decode ADR.

ALTER TABLE kodik_episode_variant
    DROP INDEX idx_mp4_link_null,
    DROP INDEX idx_mp4_link_expired,
    DROP COLUMN mp4_link,
    DROP COLUMN mp4_link_decoded_at,
    DROP COLUMN decode_method;
