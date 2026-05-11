--liquibase formatted sql

--changeset orinuno:20260410120300
ALTER TABLE kodik_episode_variant
    ADD COLUMN mp4_link_decoded_at DATETIME NULL DEFAULT NULL AFTER mp4_link;

CREATE INDEX idx_mp4_link_expired ON kodik_episode_variant (mp4_link_decoded_at)
    COMMENT 'For finding expired decoded links';
