-- Flyway Migration V1: amz_search database initialization
-- Service: amz-service-search
-- Source: docker/init-sql/06

CREATE TABLE IF NOT EXISTS amz_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    keyword VARCHAR(200) NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
