-- ============================================================
-- P1-3 实时利润核算升级（report 模块升级）
-- 表：利润快照 / 费用分摊
-- ============================================================

-- 利润快照（按小时更新，SKU维度）
CREATE TABLE IF NOT EXISTS amz_profit_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '店铺ID',
    sku VARCHAR(64) NOT NULL COMMENT 'SKU',
    asin VARCHAR(16) COMMENT 'ASIN',
    stat_time DATETIME NOT NULL COMMENT '统计时间（小时粒度）',
    sales_amount DECIMAL(12,2) DEFAULT 0 COMMENT '销售额',
    sales_quantity INT DEFAULT 0 COMMENT '销售数量',
    product_cost DECIMAL(12,2) DEFAULT 0 COMMENT '采购成本(FIFO)',
    fba_fees DECIMAL(12,2) DEFAULT 0 COMMENT 'FBA费用',
    referral_fee DECIMAL(12,2) DEFAULT 0 COMMENT '佣金',
    advertising_cost DECIMAL(12,2) DEFAULT 0 COMMENT '广告花费',
    storage_fee DECIMAL(12,2) DEFAULT 0 COMMENT '仓储费',
    headhaul_cost DECIMAL(12,2) DEFAULT 0 COMMENT '头程分摊',
    vat_cost DECIMAL(12,2) DEFAULT 0 COMMENT 'VAT',
    refund_cost DECIMAL(12,2) DEFAULT 0 COMMENT '退款损失',
    other_cost DECIMAL(12,2) DEFAULT 0 COMMENT '其他费用',
    gross_profit DECIMAL(12,2) DEFAULT 0 COMMENT '毛利（销售额-采购-履约-佣金）',
    net_profit DECIMAL(12,2) DEFAULT 0 COMMENT '净利（毛利-广告-VAT-仓储-头程-退款-其他）',
    margin DECIMAL(5,2) COMMENT '利润率(%)',
    data_source VARCHAR(20) DEFAULT 'SYNC' COMMENT '数据来源：SYNC/CALC/MANUAL',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_shop_sku_time (shop_id, sku, stat_time),
    INDEX idx_stat_time (stat_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='利润快照（小时级）';

-- 费用分摊记录
CREATE TABLE IF NOT EXISTS amz_cost_allocation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '店铺ID',
    cost_type VARCHAR(32) NOT NULL COMMENT '费用类型：HEADHAUL/AD/STORAGE/PLATFORM_FEE/OVERHEAD/OTHER',
    source_ref VARCHAR(64) COMMENT '费用来源编号（货件号/广告活动ID/等）',
    source_desc VARCHAR(256) COMMENT '费用来源描述',
    total_amount DECIMAL(12,2) NOT NULL COMMENT '总费用金额',
    currency VARCHAR(5) DEFAULT 'USD' COMMENT '币种',
    alloc_method VARCHAR(20) NOT NULL COMMENT '分摊方法：QUANTITY/WEIGHT/VOLUME/VALUE/EVEN',
    alloc_details TEXT COMMENT '分摊明细JSON：[{sku,share,amount}]',
    alloc_date DATE NOT NULL COMMENT '分摊日期',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop_date (shop_id, alloc_date),
    INDEX idx_cost_type (cost_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='费用分摊记录';
