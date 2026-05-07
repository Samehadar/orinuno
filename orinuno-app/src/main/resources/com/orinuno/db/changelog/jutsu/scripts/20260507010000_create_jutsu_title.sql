--liquibase formatted sql
--changeset orinuno:20260507010000_create_jutsu_title

CREATE TABLE IF NOT EXISTS jutsu_title (
    slug             VARCHAR(255)                       NOT NULL,
    title_ru         VARCHAR(512)                       NOT NULL,
    title_en         VARCHAR(512)                       NULL,
    status           ENUM('ongoing','released')         NULL,
    year             INT                                NULL,
    episodes_total   INT                                NULL,
    shikimori_id     BIGINT                             NULL,
    mal_id           BIGINT                             NULL,
    description      TEXT                               NULL,
    poster_url       VARCHAR(1024)                      NULL,
    last_synced_at   DATETIME                           NULL,
    source_etag      VARCHAR(255)                       NULL,

    PRIMARY KEY (slug),
    INDEX idx_jutsu_title_shikimori_id (shikimori_id),
    INDEX idx_jutsu_title_mal_id (mal_id),
    INDEX idx_jutsu_title_last_synced_at (last_synced_at),
    INDEX idx_jutsu_title_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
