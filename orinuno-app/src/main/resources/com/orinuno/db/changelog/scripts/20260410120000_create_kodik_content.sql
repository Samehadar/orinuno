--liquibase formatted sql

--changeset orinuno:20260410120000
CREATE TABLE IF NOT EXISTS kodik_content (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    kodik_id        VARCHAR(64),
    type            VARCHAR(32) NOT NULL,
    title           VARCHAR(512) NOT NULL,
    title_orig      VARCHAR(512),
    other_title     VARCHAR(512),
    year            INT,
    kinopoisk_id    VARCHAR(32),
    imdb_id         VARCHAR(32),
    shikimori_id    VARCHAR(32),
    worldart_link   VARCHAR(512),
    screenshots     JSON,
    camrip          BOOLEAN DEFAULT FALSE,
    lgbt            BOOLEAN DEFAULT FALSE,
    last_season     INT,
    last_episode    INT,
    episodes_count  INT,
    quality          VARCHAR(32),
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_kinopoisk_id (kinopoisk_id),
    INDEX idx_title_year (title(255), year),
    INDEX idx_imdb_id (imdb_id),
    INDEX idx_shikimori_id (shikimori_id),
    INDEX idx_kodik_id (kodik_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
