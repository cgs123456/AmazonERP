-- Flyway Migration V2: 结算原表明细（T04）
-- Service: amz-service-finance
-- Source mirror: docker/init-sql/15-init-tables-finance.sql + init_all_tables.sql（三处必须一致）
--
-- 幂等说明：row_key 为结算行的业务指纹（settlementId|orderId|sku|amountType|amount|depositDate 的 MD5）。
-- 结算报表按批次下发、同步窗口会重叠，唯一索引是「不重复入账」的唯一保障。

CREATE TABLE IF NOT EXISTS amz_settlement_detail (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '所属店铺 ID',
    settlement_id VARCHAR(64) DEFAULT NULL COMMENT '结算批次号 settlement-id',
    order_id VARCHAR(64) DEFAULT NULL COMMENT '订单号（平台调整行可能为空）',
    sku VARCHAR(100) DEFAULT NULL COMMENT '卖家 SKU',
    transaction_type VARCHAR(32) NOT NULL COMMENT 'Order/Refund/Adjustment/ServiceFee',
    amount_type VARCHAR(64) DEFAULT NULL COMMENT 'Principal/Commission/FBAPerUnitFulfillmentFee 等',
    amount DECIMAL(14,2) NOT NULL COMMENT '有符号金额（保留平台原始方向）',
    currency VARCHAR(8) DEFAULT NULL COMMENT '币种（ISO 4217）',
    deposit_date VARCHAR(40) DEFAULT NULL COMMENT '结算存款日（原样保存平台字符串）',
    row_key CHAR(32) NOT NULL COMMENT '幂等指纹（MD5），唯一索引',
    source VARCHAR(16) DEFAULT 'REPORT' COMMENT '数据来源：REPORT/MANUAL',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_row_key (row_key),
    INDEX idx_shop (shop_id),
    INDEX idx_shop_order (shop_id, order_id),
    INDEX idx_settlement (settlement_id),
    INDEX idx_deposit_date (deposit_date),
    INDEX idx_transaction_type (transaction_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='结算原表明细（订单级资金流水）';
