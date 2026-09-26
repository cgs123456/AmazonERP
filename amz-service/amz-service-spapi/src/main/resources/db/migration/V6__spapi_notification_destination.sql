-- Flyway Migration V6: SP-API notification destination 映射
-- Service: amz-service-spapi
--
-- 用途：记录每个（店铺, 站点）在 Amazon 侧创建的 destination，供通知入站反查归属。
-- 没有这张表时，SQS 里收到的通知只有 subscriptionId / applicationId，
-- 无法判断它属于哪个店铺，跨店铺场景会直接串店。
--
-- 注意：destination 的创建要走 SP-API createDestination（NotificationsClient），
-- 本表只保存映射关系与对账需要的字段；sqs queue 的 IAM 消费角色不存本表。

CREATE TABLE IF NOT EXISTS `amz_spapi_notification_destination` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `destination_id`  VARCHAR(128) NOT NULL                      COMMENT 'Amazon 返回的 destinationId',
    `shop_id`         BIGINT       NOT NULL                      COMMENT '本地店铺 ID',
    -- 唯一键包含 marketplace_id：为 NULL 会让 MySQL 唯一约束对该列失效，
    -- 因此这里用 NOT NULL DEFAULT 空串，保证（店铺, 站点, destination）唯一。
    `marketplace_id`  VARCHAR(32)  NOT NULL DEFAULT ''            COMMENT '站点 ID；空串表示店铺级默认',
    `resource_type`   VARCHAR(16)  NOT NULL DEFAULT 'SQS'         COMMENT 'SQS 或 EVENT_BRIDGE',
    `queue_arn`       VARCHAR(512)          DEFAULT NULL          COMMENT 'SQS 队列 ARN',
    `event_bus_arn`   VARCHAR(512)          DEFAULT NULL          COMMENT 'EventBridge event bus ARN（间接投递时）',
    `region`          VARCHAR(32)           DEFAULT NULL          COMMENT 'AWS region',
    `status`          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'      COMMENT 'ACTIVE / DELETED',
    `synthetic`       TINYINT(1)   NOT NULL DEFAULT 0             COMMENT '1=合成演练数据，禁止出现在生产库',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_destination_id` (`destination_id`),
    UNIQUE KEY `uk_destination_shop_market` (`shop_id`, `marketplace_id`, `destination_id`),
    KEY `idx_destination_shop` (`shop_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='SP-API 通知目标（destination）与店铺映射';