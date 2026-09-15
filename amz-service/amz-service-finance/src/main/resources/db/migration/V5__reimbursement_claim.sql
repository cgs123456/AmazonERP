-- Flyway Migration V5: 索赔单（T07 / T08）
-- Service: amz-service-finance
-- Source mirror: docker/init-sql/15-init-tables-finance.sql + init_all_tables.sql（三处必须一致）
--
-- 状态机：CANDIDATE → SUBMITTED → ACCEPTED → REIMBURSED（终态）
--          SUBMITTED / ACCEPTED → REJECTED（终态）
--         由服务层白名单强制，DB 层不加 CHECK（避免后续扩展状态时要改表）
-- voucher_id 关联追回入账凭证（amz_accounting_voucher.id，source_type=REIMBURSEMENT）

CREATE TABLE IF NOT EXISTS amz_reimbursement_claim (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '所属店铺 ID',
    claim_no VARCHAR(40) NOT NULL COMMENT '索赔单号（CLM + 时间戳 + 随机数）',
    discrepancy_id BIGINT DEFAULT NULL COMMENT '来源差异候选 ID（amz_fee_discrepancy.id）',
    discrepancy_type VARCHAR(40) DEFAULT NULL COMMENT '差异类型（继承自候选，便于按类型统计成功率）',
    sku VARCHAR(100) DEFAULT NULL COMMENT '卖家 SKU',
    shipment_id VARCHAR(64) DEFAULT NULL COMMENT '关联货件编号',
    claim_reason VARCHAR(600) DEFAULT NULL COMMENT '索赔理由 / 举证说明',
    claim_amount DECIMAL(14,2) DEFAULT NULL COMMENT '申请索赔金额（候选差额绝对值）',
    reimbursed_amount DECIMAL(14,2) DEFAULT NULL COMMENT '实际赔付金额（可少于申请金额）',
    currency VARCHAR(8) DEFAULT 'USD' COMMENT '币种（ISO 4217）',
    status VARCHAR(16) DEFAULT 'CANDIDATE' COMMENT 'CANDIDATE/SUBMITTED/ACCEPTED/REIMBURSED/REJECTED',
    submitted_at DATETIME DEFAULT NULL COMMENT '提交时间',
    accepted_at DATETIME DEFAULT NULL COMMENT '平台受理时间',
    settled_at DATETIME DEFAULT NULL COMMENT '结案时间（赔付或驳回）',
    reject_reason VARCHAR(300) DEFAULT NULL COMMENT '驳回原因',
    voucher_id BIGINT DEFAULT NULL COMMENT '追回入账凭证 ID（T08）',
    operator_id BIGINT DEFAULT NULL COMMENT '操作人用户 ID',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_claim_no (claim_no),
    INDEX idx_shop_status (shop_id, status),
    INDEX idx_discrepancy (shop_id, discrepancy_id),
    INDEX idx_voucher (voucher_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='索赔单（平台赔付追回）';
