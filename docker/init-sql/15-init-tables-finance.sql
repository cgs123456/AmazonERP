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
