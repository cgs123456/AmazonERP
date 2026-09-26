-- Flyway Migration V8: SP-API notification inbox（入站事件账本）
-- Service: amz-service-spapi
--
-- 关键顺序：先落 Inbox 再删除 SQS 消息；DB 写失败绝不删消息。
-- 因此 SQS standard queue 的重复投递由 uk_notification_id 吸收，
-- 顺序不保证由业务层按 event_time 比较处理（队列不可能是 FIFO，SP-API 不支持）。
--
-- payload 一律以 AES-256-GCM 密文保存（CryptoUtil，密钥来自 AMZ_CRYPTO_KEY）：
-- 通知正文可能含买家姓名与地址等 PII，明文落库会直接违反数据保护要求。
-- 日志只允许输出 notification_id / notification_type / shop_id / payload_sha256 / 错误码。

CREATE TABLE IF NOT EXISTS `amz_spapi_notification_inbox` (
    `id`                 BIGINT        NOT NULL AUTO_INCREMENT,
    `notification_id`    VARCHAR(128)  NOT NULL                 COMMENT 'NotificationMetadata.NotificationId，入口去重键',
    `notification_type`  VARCHAR(64)   NOT NULL                 COMMENT 'ORDER_CHANGE 等',
    `payload_version`    VARCHAR(32)            DEFAULT NULL,
    `event_time`         DATETIME(3)            DEFAULT NULL    COMMENT '业务事件时间（乱序判断依据）',
    `publish_time`       DATETIME(3)            DEFAULT NULL    COMMENT 'Amazon 发布时间',
    `shop_id`            BIGINT                 DEFAULT NULL    COMMENT '归属店铺；UNRESOLVED_SUBSCRIPTION 时为 NULL',
    `marketplace_id`     VARCHAR(32)            DEFAULT NULL,
    `application_id`     VARCHAR(128)           DEFAULT NULL    COMMENT 'NotificationMetadata.ApplicationId',
    `subscription_id`    VARCHAR(128)           DEFAULT NULL,
    `destination_id`     VARCHAR(128)           DEFAULT NULL,
    `payload_encrypted`  MEDIUMTEXT             DEFAULT NULL    COMMENT 'AES-256-GCM 密文',
    `payload_sha256`     CHAR(64)               DEFAULT NULL    COMMENT '原始载荷 SHA-256，用于重复投递的异哈希告警',
    `payload_bytes`      INT          NOT NULL DEFAULT 0        COMMENT '原始字节数，用于 payload_too_large 判定留档',
    `status`             VARCHAR(32)  NOT NULL DEFAULT 'RECEIVED'
        COMMENT 'RECEIVED/PROCESSING/PROCESSED/FAILED/DLQ/UNSUPPORTED/UNRESOLVED_SUBSCRIPTION/INVALID',
    `attempt_count`      INT          NOT NULL DEFAULT 0,
    `max_attempts`       INT          NOT NULL DEFAULT 5,
    `next_attempt_at`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '到期才可再次领取',
    `lease_owner`        VARCHAR(64)           DEFAULT NULL     COMMENT '领取者标识（实例 / 线程），用于租约回收',
    `lease_until`        DATETIME(3)           DEFAULT NULL     COMMENT '租约到期时间；过期可被回收重领',
    `last_error_code`    VARCHAR(64)           DEFAULT NULL,
    `last_error_message` VARCHAR(512)          DEFAULT NULL     COMMENT '仅结构化错误码/摘要，禁止写入 PII',
    `duplicate_count`    INT          NOT NULL DEFAULT 0        COMMENT '同一 notification_id 的重复投递次数',
    `synthetic`          TINYINT(1)   NOT NULL DEFAULT 0        COMMENT '1=合成演练事件',
    `created_at`         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at`         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    `processed_at`       DATETIME(3)           DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_notification_id` (`notification_id`),
    KEY `idx_inbox_due` (`status`, `next_attempt_at`),
    KEY `idx_inbox_shop_type_time` (`shop_id`, `notification_type`, `event_time`),
    KEY `idx_inbox_lease` (`lease_owner`, `lease_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='SP-API 入站通知账本（密文载荷，去重 / 重试 / DLQ）';