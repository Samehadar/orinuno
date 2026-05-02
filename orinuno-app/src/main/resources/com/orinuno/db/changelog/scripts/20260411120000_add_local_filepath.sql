--liquibase formatted sql
--changeset orinuno:20260411120000_add_local_filepath

ALTER TABLE kodik_episode_variant
    ADD COLUMN local_filepath VARCHAR(1024) DEFAULT NULL COMMENT 'Path to locally downloaded video file';

CREATE INDEX idx_variant_local_filepath
    ON kodik_episode_variant (local_filepath(255));
