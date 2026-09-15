-- ============================================
-- Amazon ERP 业财一体化模块建表脚本
-- 数据库: amz_finance
-- ============================================

CREATE DATABASE IF NOT EXISTS amz_finance DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE amz_finance;

-- 会计凭证表
CREATE TABLE IF NOT EXISTS amz_accounting_voucher (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    voucher_no VARCHAR(32) NOT NULL UNIQUE COMMENT '凭证编号',
    shop_id BIGINT NOT NULL COMMENT '所属店铺 ID',
    biz_date VARCHAR(20) DEFAULT NULL COMMENT '业务日期',
    summary VARCHAR(200) DEFAULT NULL COMMENT '摘要',
    debit_account VARCHAR(20) DEFAULT NULL COMMENT '借方科目代码',
    credit_account VARCHAR(20) DEFAULT NULL COMMENT '贷方科目代码',
    original_amount DECIMAL(12,2) DEFAULT NULL COMMENT '原币金额',
    currency VARCHAR(5) DEFAULT 'USD' COMMENT '原币币种',
    exchange_rate DECIMAL(10,4) DEFAULT 1.0000 COMMENT '汇率（原币→CNY）',
    cny_amount DECIMAL(12,2) DEFAULT NULL COMMENT '本位币金额（CNY）',
    source_type VARCHAR(20) DEFAULT NULL COMMENT 'ORDER/PROCUREMENT/PLATFORM_FEE/REFUND',
    source_no VARCHAR(64) DEFAULT NULL COMMENT '关联业务单号',
    kingdee_sync_status VARCHAR(10) DEFAULT 'PENDING' COMMENT 'PENDING/SYNCED/FAILED',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop (shop_id),
    INDEX idx_source (source_type),
    INDEX idx_biz_date (biz_date),
    INDEX idx_kingdee (kingdee_sync_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会计凭证表';

-- 结算原表明细（T04，对应 com.amz.model.SettlementDetail @TableName("amz_settlement_detail")）
-- row_key 为业务指纹唯一索引：结算报表按批次下发且同步窗口重叠，靠它保证不重复入账。
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

-- 订单级回款台账（T05，对应 com.amz.model.PaymentCollection @TableName("amz_payment_collection")）
-- net_received = receivable - fee_deducted - refunded + reimbursed（恒等式）
-- shortfall 无默认值：NULL = 尚未做费用比对（短款不可知），与「短款为 0」是两件事。
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

-- 费用差异与短收候选（T06，对应 com.amz.model.FeeDiscrepancy @TableName("amz_fee_discrepancy")）
-- difference = actual_amount - expected_amount：正数 = 平台多收；负数 = 应给未给（短收/未赔付）
CREATE TABLE IF NOT EXISTS amz_fee_discrepancy (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '所属店铺 ID',
    sku VARCHAR(100) DEFAULT NULL COMMENT '卖家 SKU',
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

-- 索赔单（T07/T08，对应 com.amz.model.ReimbursementClaim @TableName("amz_reimbursement_claim")）
-- 状态机：CANDIDATE → SUBMITTED → ACCEPTED → REIMBURSED；SUBMITTED/ACCEPTED → REJECTED
CREATE TABLE IF NOT EXISTS amz_reimbursement_claim (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '所属店铺 ID',
    claim_no VARCHAR(40) NOT NULL COMMENT '索赔单号',
    discrepancy_id BIGINT DEFAULT NULL COMMENT '来源差异候选 ID',
    discrepancy_type VARCHAR(40) DEFAULT NULL COMMENT '差异类型',
    sku VARCHAR(100) DEFAULT NULL COMMENT '卖家 SKU',
    shipment_id VARCHAR(64) DEFAULT NULL COMMENT '关联货件编号',
    claim_reason VARCHAR(600) DEFAULT NULL COMMENT '索赔理由 / 举证说明',
    claim_amount DECIMAL(14,2) DEFAULT NULL COMMENT '申请索赔金额',
    reimbursed_amount DECIMAL(14,2) DEFAULT NULL COMMENT '实际赔付金额',
    currency VARCHAR(8) DEFAULT 'USD' COMMENT '币种（ISO 4217）',
    status VARCHAR(16) DEFAULT 'CANDIDATE' COMMENT 'CANDIDATE/SUBMITTED/ACCEPTED/REIMBURSED/REJECTED',
    submitted_at DATETIME DEFAULT NULL COMMENT '提交时间',
    accepted_at DATETIME DEFAULT NULL COMMENT '平台受理时间',
    settled_at DATETIME DEFAULT NULL COMMENT '结案时间',
    reject_reason VARCHAR(300) DEFAULT NULL COMMENT '驳回原因',
    voucher_id BIGINT DEFAULT NULL COMMENT '追回入账凭证 ID',
    operator_id BIGINT DEFAULT NULL COMMENT '操作人用户 ID',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_claim_no (claim_no),
    INDEX idx_shop_status (shop_id, status),
    INDEX idx_discrepancy (shop_id, discrepancy_id),
    INDEX idx_voucher (voucher_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='索赔单（平台赔付追回）';
