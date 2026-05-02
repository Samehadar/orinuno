--liquibase formatted sql

--changeset orinuno:20260410120200
CREATE TABLE IF NOT EXISTS kodik_proxy (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    host            VARCHAR(256) NOT NULL,
    port            INT NOT NULL,
    username        VARCHAR(128),
    password        VARCHAR(128),
    proxy_type      VARCHAR(16) NOT NULL DEFAULT 'HTTP',
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    last_used_at    TIMESTAMP,
    fail_count      INT DEFAULT 0,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_host_port (host, port),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
