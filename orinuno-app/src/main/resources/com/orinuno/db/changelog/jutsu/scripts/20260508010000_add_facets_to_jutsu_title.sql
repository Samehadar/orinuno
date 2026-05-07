--liquibase formatted sql
--changeset orinuno:20260508010000_add_facets_to_jutsu_title

ALTER TABLE jutsu_title
    ADD COLUMN genres      VARCHAR(500) NULL AFTER episodes_total,
    ADD COLUMN types       VARCHAR(200) NULL AFTER genres,
    ADD COLUMN movie_count INT          NULL AFTER types;
