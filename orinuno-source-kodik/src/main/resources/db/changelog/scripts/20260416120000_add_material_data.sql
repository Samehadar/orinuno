--liquibase formatted sql

--changeset orinuno:20260416120000
ALTER TABLE kodik_content
    ADD COLUMN material_data JSON DEFAULT NULL AFTER screenshots,
    ADD COLUMN kinopoisk_rating DECIMAL(3,1) DEFAULT NULL AFTER material_data,
    ADD COLUMN imdb_rating DECIMAL(3,1) DEFAULT NULL AFTER kinopoisk_rating,
    ADD COLUMN shikimori_rating DECIMAL(3,1) DEFAULT NULL AFTER imdb_rating,
    ADD COLUMN genres VARCHAR(1024) DEFAULT NULL AFTER shikimori_rating,
    ADD COLUMN blocked_countries VARCHAR(512) DEFAULT NULL AFTER genres;

CREATE INDEX idx_kinopoisk_rating ON kodik_content (kinopoisk_rating);
CREATE INDEX idx_imdb_rating ON kodik_content (imdb_rating);
