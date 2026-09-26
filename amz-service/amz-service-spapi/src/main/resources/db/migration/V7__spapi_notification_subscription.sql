-- Flyway Migration V7: SP-API notification subscription 映射
-- Service: amz-service-spapi
--
-- 用途：subscriptionId -> (shop_id, marketplace_id, notification_type) 的反查表。
-- 通知入站时只能从 NotificationMetadata 拿到 subscriptionId，没有映射就无法归属店铺，
-- 这类事件必须进入 UNRESOLVED_SUBSCRIPTION 隔离（不删 SQS 消息、不进 DLQ），
-- 由订阅对账调度或人工补映射后重放，不允许猜归属，也不允许丢弃。

CREATE TABLE IF NOT EXISTS `amz_spapi_notification_subscription` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `subscription_id`   VARCHAR(128) NOT NULL                    COMMENT 'Amazon 返回的 subscriptionId',
    `destination_id`    VARCHAR(128) NOT NULL                    COMMENT '所属 destinationId',
    `shop_id`           BIGINT       NOT NULL                    COMMENT '本地店铺 ID',
    `marketplace_id`    VARCHAR(32)  NOT NULL DEFAULT ''          COMMENT '站点 ID',
    `notification_type` VARCHAR(64)  NOT NULL                    COMMENT '如 ORDER_CHANGE / FEED_PROCESSING_FINISHED',
    `payload_version`   VARCHAR(32)           DEFAULT NULL        COMMENT '订阅时登记的 payloadVersion',
    `filter_expression` VARCHAR(512)          DEFAULT NULL        COMMENT '本地留档的过滤表达式（如有）',
    -- 官方 eventFilter 当前仅支持 ANY_OFFER_CHANGED / ORDER_CHANGE / SHIPMENT_TRACKING_MILESTONE_CHANGED；
    -- 其他类型留空，避免按不存在的过滤器建订阅。
    `event_filter`      VARCHAR(64)           DEFAULT NULL        COMMENT '官方 eventFilter 名称，仅上述三类可用',
    `status`            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'    COMMENT 'ACTIVE / DELETED',
    `synthetic`         TINYINT(1)   NOT NULL DEFAULT 0           COMMENT '1=合成演练数据',
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_subscription_id` (`subscription_id`),
    KEY `idx_subscription_type` (`notification_type`, `status`),
    KEY `idx_subscription_shop` (`shop_id`, `notification_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='SP-API 通知订阅与店铺映射';