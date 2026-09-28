-- Flyway Migration V4: Feeds processing report 逐行错误明细
-- Service: amz-service-spapi
--
-- V2/V3 already use the V2 and V3 versions for credential/outbox migrations,
-- so this additive migration MUST use V4. The table intentionally stores only
-- normalized diagnostic fields; the unencrypted processing report body and raw
-- row JSON are never persisted.

CREATE TABLE IF NOT EXISTS `amz_feed_result_error` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `feed_id`       VARCHAR(128) NOT NULL COMMENT 'SP-API feedId',
    `shop_id`       BIGINT       NOT NULL COMMENT '店铺 ID',
    `issue_index`   INT          NOT NULL COMMENT 'processing report issues 数组内序号',
    `row_index`     INT          NOT NULL COMMENT 'processing report messageId/业务行号',
    `seller_sku`    VARCHAR(255) DEFAULT NULL COMMENT '报告中的 seller SKU',
    `error_code`    VARCHAR(64)  DEFAULT NULL COMMENT 'Amazon 错误码',
    `severity`      VARCHAR(16)  DEFAULT NULL COMMENT 'ERROR/WARNING',
    `error_message` VARCHAR(1024) DEFAULT NULL COMMENT '脱敏后的错误说明',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_feed_result_error_feed_issue` (`feed_id`, `issue_index`),
    KEY `idx_feed_result_error_feed_row` (`feed_id`, `row_index`),
    KEY `idx_feed_result_error_shop_time` (`shop_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Feeds processing report 拒绝/警告行（不含报告原文）';
