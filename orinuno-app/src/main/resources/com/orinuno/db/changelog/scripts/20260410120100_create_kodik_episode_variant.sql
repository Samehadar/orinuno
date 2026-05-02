--liquibase formatted sql

--changeset orinuno:20260410120100
CREATE TABLE IF NOT EXISTS kodik_episode_variant (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    content_id          BIGINT NOT NULL,
    season_number       INT NOT NULL DEFAULT 0,
    episode_number      INT NOT NULL DEFAULT 0,
    translation_id      INT NOT NULL,
    translation_title   VARCHAR(256),
    translation_type    VARCHAR(32),
    quality             VARCHAR(32),
    kodik_link          VARCHAR(1024),
    mp4_link            VARCHAR(2048),
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_content_season_episode_translation (content_id, season_number, episode_number, translation_id),
    INDEX idx_content_id (content_id),
    INDEX idx_mp4_link_null (content_id, mp4_link(255)),

    CONSTRAINT fk_episode_variant_content
        FOREIGN KEY (content_id) REFERENCES kodik_content(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
