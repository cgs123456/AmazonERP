-- Flyway Migration V3: 订单级回款台账（T05）
-- Service: amz-service-finance
-- Source mirror: docker/init-sql/15-init-tables-finance.sql + init_all_tables.sql（三处必须一致）
--
-- 口径说明：
--   net_received = receivable - fee_deducted - refunded + reimbursed（恒等式，改口径必破坏它）
--   shortfall 允许 NULL 且不设默认值 —— NULL 表示「尚未做费用比对，短款不可知」，
--   与「短款为 0」是两件事，用 0 代替会读成「没有短款」这个结论。

CREATE TABLE IF NOT EXISTS amz_payment_collection (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '所属店铺 ID',
    order_id VARCHAR(64) NOT NULL COMMENT '订单号',
    currency VARCHAR(8) DEFAULT NULL COMMENT '币种（ISO 4217）',
    receivable DECIMAL(14,2) DEFAULT 0.00 COMMENT '应收（Σ Order + Principal）',
    fee_deducted DECIMAL(14,2) DEFAULT 0.00 COMMENT '平台费用（正数量级）',
    refunded DECIMAL(14,2) DEFAULT 0.00 COMMENT '退款（正数量级）',
    reimbursed DECIMAL(14,2) DEFAULT 0.00 COMMENT '平台赔付净额（保留符号）',
    net_received DECIMAL(14,2) DEFAULT 0.00 COMMENT '实收 = 应收 - 费用 - 退款 + 赔付',
    shortfall DECIMAL(14,2) DEFAULT NULL COMMENT '短款（预估净回 - 实收）；未做费用比对时为 NULL',
    deposit_date VARCHAR(40) DEFAULT NULL COMMENT '结算存款日（原样保存平台字符串）',
    status VARCHAR(16) DEFAULT 'IN_TRANSIT' COMMENT 'PENDING/IN_TRANSIT/SETTLED/REFUNDED/SHORTFALL',
    last_calculated_at DATETIME DEFAULT NULL COMMENT '最近一次重算时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_shop_order (shop_id, order_id),
    INDEX idx_shop_status (shop_id, status),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单级回款台账';
