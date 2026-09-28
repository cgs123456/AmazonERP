-- Flyway Migration V2: SP-API credential persistence + durable call outbox
-- Service: amz-service-spapi
--
-- V1 was already shipped with the inventory/replenishment tables.  The credential
-- table previously lived only in db/schema.sql, while production explicitly sets
-- spring.flyway.locations=classpath:db/migration.  That made a fresh deployment
-- unable to persist credentials.  V2 is additive and idempotent so it also repairs
-- databases that were created from schema.sql.

CREATE TABLE IF NOT EXISTS `amz_shop_credential` (
    `shop_id`                   BIGINT       NOT NULL                            COMMENT '店铺主键 ID（业务提供）',
    `client_id`                 VARCHAR(128)         DEFAULT NULL                 COMMENT 'LWA Client ID（非敏感）',
    `client_secret_encrypted`   VARCHAR(2048)        DEFAULT NULL                 COMMENT 'LWA Client Secret（AES-256-GCM 密文）',
    `refresh_token_encrypted`   VARCHAR(2048)        DEFAULT NULL                 COMMENT 'SP-API 刷新令牌（AES-256-GCM 密文）',
    `access_key_encrypted`      VARCHAR(2048)        DEFAULT NULL                 COMMENT 'AWS Access Key ID（AES-256-GCM 密文）',
    `secret_key_encrypted`      VARCHAR(2048)        DEFAULT NULL                 COMMENT 'AWS Secret Access Key（AES-256-GCM 密文）',
    `region`                    VARCHAR(16)          DEFAULT NULL                 COMMENT 'SP-API 区域：NA / EU / FE',
    `marketplace_id`            VARCHAR(32)          DEFAULT NULL                 COMMENT 'Amazon Marketplace ID（如 ATVPDKIKX0DER）',
    `seller_id`                 VARCHAR(64)          DEFAULT NULL                 COMMENT 'Amazon Seller ID',
    `create_time`               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP     COMMENT '创建时间',
    `update_time`               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`shop_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='店铺 SP-API 凭证（密文存储）';

-- Upgrade databases that already created the table from db/schema.sql with the
-- old 512-byte columns.  LWA refresh tokens can be longer than 512 characters,
-- and AES-GCM base64 expansion makes the previous bound unsafe.
ALTER TABLE `amz_shop_credential`
    MODIFY COLUMN `client_secret_encrypted` VARCHAR(2048) DEFAULT NULL COMMENT 'LWA Client Secret（AES-256-GCM 密文）',
    MODIFY COLUMN `refresh_token_encrypted` VARCHAR(2048) DEFAULT NULL COMMENT 'SP-API 刷新令牌（AES-256-GCM 密文）',
    MODIFY COLUMN `access_key_encrypted` VARCHAR(2048) DEFAULT NULL COMMENT 'AWS Access Key ID（AES-256-GCM 密文）',
    MODIFY COLUMN `secret_key_encrypted` VARCHAR(2048) DEFAULT NULL COMMENT 'AWS Secret Access Key（AES-256-GCM 密文）',
    MODIFY COLUMN `seller_id` VARCHAR(64) DEFAULT NULL COMMENT 'Amazon Seller ID';

CREATE TABLE IF NOT EXISTS `amz_spapi_call_outbox` (
    `id`                         BIGINT       NOT NULL AUTO_INCREMENT,
    `shop_id`                    BIGINT       NOT NULL COMMENT '店铺 ID',
    `marketplace_id`             VARCHAR(32)  DEFAULT NULL COMMENT '调用时的 Marketplace ID',
    `operation_id`               VARCHAR(160) NOT NULL COMMENT 'SP-API operationId',
    `http_method`                VARCHAR(12)  NOT NULL COMMENT 'GET/POST/PUT/PATCH/DELETE',
    `request_path`               VARCHAR(1024) NOT NULL COMMENT '不含 query 的请求路径',
    `request_query`              TEXT         DEFAULT NULL COMMENT '规范查询串',
    `request_body_encrypted`     MEDIUMTEXT   DEFAULT NULL COMMENT '请求体 AES-256-GCM 密文',
    `expected_status`            INT          NOT NULL DEFAULT 200 COMMENT '业务定义的唯一成功状态码',
    `idempotency_key`            VARCHAR(128) DEFAULT NULL COMMENT '调用方幂等键；未提供为空',
    `replay_of_id`               BIGINT       DEFAULT NULL COMMENT '由哪条记录重放而来',
    `status`                     VARCHAR(16)  NOT NULL COMMENT 'PENDING/SUCCEEDED/FAILED/REPLAYING/REPLAYED/DLQ',
    `attempt_count`              INT          NOT NULL DEFAULT 0 COMMENT '已执行的调度尝试次数',
    `max_attempts`               INT          NOT NULL DEFAULT 4 COMMENT '最大调度尝试次数',
    `next_attempt_at`            DATETIME     DEFAULT NULL COMMENT '下一次可重放时间',
    `last_error_code`            VARCHAR(128) DEFAULT NULL COMMENT '最近失败的结构化错误码',
    `last_error_message`         TEXT         DEFAULT NULL COMMENT '最近失败的脱敏错误信息',
    `response_status`            INT          DEFAULT NULL COMMENT 'Amazon HTTP 状态码',
    `response_body_encrypted`    MEDIUMTEXT   DEFAULT NULL COMMENT '响应体 AES-256-GCM 密文',
    `response_request_id`        VARCHAR(256) DEFAULT NULL COMMENT 'Amazon request id（脱敏后）',
    `created_at`                 DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`                 DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `completed_at`               DATETIME     DEFAULT NULL COMMENT '成功、DLQ 或重放完成时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_spapi_outbox_idempotency` (`idempotency_key`),
    KEY `idx_spapi_outbox_due` (`status`, `next_attempt_at`),
    KEY `idx_spapi_outbox_shop_operation` (`shop_id`, `operation_id`, `created_at`),
    KEY `idx_spapi_outbox_replay_of` (`replay_of_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SP-API 持久化调用与重放队列';
