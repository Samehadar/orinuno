--liquibase formatted sql
--changeset orinuno:20260425010000_cleanup_invalid_mp4_link

UPDATE kodik_episode_variant
SET mp4_link = NULL,
    mp4_link_decoded_at = NULL
WHERE mp4_link IS NOT NULL
  AND mp4_link NOT LIKE 'http%';
