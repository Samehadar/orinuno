--liquibase formatted sql
--changeset orinuno:20260426010000_create_orinuno_parse_request

CREATE TABLE IF NOT EXISTS orinuno_parse_request (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_hash        CHAR(64) NOT NULL,
    request_json        JSON NOT NULL,
    status              ENUM('PENDING','RUNNING','DONE','FAILED') NOT NULL DEFAULT 'PENDING',
    phase               ENUM('QUEUED','SEARCHING','DECODING','DONE','FAILED') NOT NULL DEFAULT 'QUEUED',
    progress_decoded    INT NOT NULL DEFAULT 0,
    progress_total      INT NOT NULL DEFAULT 0,
    result_content_ids  JSON NULL,
    error_message       TEXT NULL,
    retry_count         INT NOT NULL DEFAULT 0,
    created_by          VARCHAR(64) NOT NULL DEFAULT 'default',
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at          DATETIME NULL,
    finished_at         DATETIME NULL,
    last_heartbeat_at   DATETIME NULL,

    INDEX idx_status_created_at (status, created_at),
    INDEX idx_request_hash_status (request_hash, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
