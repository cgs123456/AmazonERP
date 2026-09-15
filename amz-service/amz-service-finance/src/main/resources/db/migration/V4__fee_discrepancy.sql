-- Flyway Migration V4: 费用差异与短收候选（T06）
-- Service: amz-service-finance
-- Source mirror: docker/init-sql/15-init-tables-finance.sql + init_all_tables.sql（三处必须一致）
--
-- 金额口径：difference = actual_amount - expected_amount
--   正数 = 平台多收多扣；负数 = 应给未给（短收 / 未赔付）。索赔金额取绝对值。
-- 去重：供应商同 SKU 同类型已有未结案候选时不再重复生成（靠 idx_shop_sku_type 支撑）。

CREATE TABLE IF NOT EXISTS amz_fee_discrepancy (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '所属店铺 ID',
    sku VARCHAR(100) DEFAULT NULL COMMENT '卖家 SKU（入库短收以外的规则均按 SKU 归集）',
    shipment_id VARCHAR(64) DEFAULT NULL COMMENT '关联货件编号（入库短收场景）',
    discrepancy_type VARCHAR(40) NOT NULL COMMENT 'FULFILLMENT_FEE_OVERCHARGE/COMMISSION_OVERCHARGE/SIZE_TIER_JUMP/INBOUND_SHORTAGE',
    expected_amount DECIMAL(14,2) DEFAULT NULL COMMENT '应有金额（预估费 / 应赔金额）',
    actual_amount DECIMAL(14,2) DEFAULT NULL COMMENT '实际发生金额（实际扣费 / 已赔付金额）',
    difference DECIMAL(14,2) DEFAULT NULL COMMENT '差额 = 实际 - 应有（正=平台多收；负=应给未给）',
    currency VARCHAR(8) DEFAULT 'USD' COMMENT '币种（ISO 4217）',
    evidence VARCHAR(500) DEFAULT NULL COMMENT '举证说明（两边数字与来源）',
    status VARCHAR(16) DEFAULT 'CANDIDATE' COMMENT 'CANDIDATE/CLAIMED/REIMBURSED/DISMISSED',
    detected_at DATETIME DEFAULT NULL COMMENT '识别时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop_status (shop_id, status),
    INDEX idx_shop_sku_type (shop_id, sku, discrepancy_type),
    INDEX idx_type (discrepancy_type),
    INDEX idx_shipment (shipment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='费用差异与短收候选（索赔输入）';
