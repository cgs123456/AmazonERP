-- Flyway Migration V1: amz_report database initialization
-- Service: amz-service-report
-- Source: docker/init-sql/26, 30

-- ============================================
-- Report Upgrade (from 26-init-tables-report-upgrade.sql)
-- ============================================
CREATE TABLE IF NOT EXISTS amz_profit_detail (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    amazon_order_id VARCHAR(32) NOT NULL,
    asin VARCHAR(20) NOT NULL,
    sku VARCHAR(64),
    report_date DATE NOT NULL,
    product_sales DECIMAL(10,2) DEFAULT 0 COMMENT '商品销售额',
    shipping_credits DECIMAL(10,2) DEFAULT 0 COMMENT '运费收入',
    promotional_rebates DECIMAL(10,2) DEFAULT 0 COMMENT '促销返点',
    product_cost DECIMAL(10,2) DEFAULT 0 COMMENT '采购成本(FIFO)',
    fba_fees DECIMAL(10,2) DEFAULT 0 COMMENT 'FBA履约费',
    referral_fee DECIMAL(10,2) DEFAULT 0 COMMENT '销售佣金',
    variable_closing_fee DECIMAL(10,2) DEFAULT 0 COMMENT '可变结算费',
    inbound_freight DECIMAL(10,2) DEFAULT 0 COMMENT '头程运费分摊',
    inbound_duty DECIMAL(10,2) DEFAULT 0 COMMENT '头程关税分摊',
    storage_fee DECIMAL(10,2) DEFAULT 0 COMMENT '仓储费',
    advertising_cost DECIMAL(10,2) DEFAULT 0 COMMENT '广告花费',
    vat_tax DECIMAL(10,2) DEFAULT 0 COMMENT 'VAT增值税',
    other_fees DECIMAL(10,2) DEFAULT 0 COMMENT '其他费用',
    gross_profit DECIMAL(10,2) DEFAULT 0 COMMENT '毛利',
    net_profit DECIMAL(10,2) DEFAULT 0 COMMENT '净利',
    margin DECIMAL(5,2) DEFAULT 0 COMMENT '利润率(%)',
    currency VARCHAR(10) DEFAULT 'USD',
    exchange_rate DECIMAL(10,4) DEFAULT 1.0000 COMMENT '折算汇率',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop_date (shop_id, report_date),
    INDEX idx_order (amazon_order_id),
    INDEX idx_asin (asin)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='利润核算明细表';

CREATE TABLE IF NOT EXISTS amz_inventory_turnover (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    asin VARCHAR(20) NOT NULL,
    sku VARCHAR(64),
    report_date DATE NOT NULL,
    avg_inventory_value DECIMAL(10,2) DEFAULT 0 COMMENT '平均库存价值',
    cogs DECIMAL(10,2) DEFAULT 0 COMMENT '销售成本',
    turnover_rate DECIMAL(10,2) DEFAULT 0 COMMENT '周转率',
    days_of_supply INT DEFAULT 0 COMMENT '供货天数',
    stockout_count INT DEFAULT 0 COMMENT '缺货次数',
    overstock_days INT DEFAULT 0 COMMENT '滞销天数',
    dead_stock_value DECIMAL(10,2) DEFAULT 0 COMMENT '呆滞库存价值',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop_date (shop_id, report_date),
    INDEX idx_asin (asin)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存周转报表';

CREATE TABLE IF NOT EXISTS amz_sales_daily (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    asin VARCHAR(20),
    sku VARCHAR(64),
    report_date DATE NOT NULL,
    units_ordered INT DEFAULT 0,
    units_refunded INT DEFAULT 0,
    net_units INT DEFAULT 0 COMMENT '净销量',
    gross_sales DECIMAL(10,2) DEFAULT 0,
    refund_amount DECIMAL(10,2) DEFAULT 0,
    net_sales DECIMAL(10,2) DEFAULT 0 COMMENT '净销售额',
    sessions INT DEFAULT 0 COMMENT '访问数',
    page_views INT DEFAULT 0 COMMENT '页面浏览',
    conversion_rate DECIMAL(5,2) DEFAULT 0 COMMENT '转化率(%)',
    buy_box_percentage DECIMAL(5,2) DEFAULT 0 COMMENT '购物车赢得率(%)',
    units_per_session DECIMAL(5,2) DEFAULT 0 COMMENT '每会话销量',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop_date (shop_id, report_date),
    INDEX idx_asin (asin)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售趋势日报表';

CREATE TABLE IF NOT EXISTS amz_business_overview (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    report_date DATE NOT NULL,
    total_sales DECIMAL(10,2) DEFAULT 0 COMMENT '总销售额',
    total_orders INT DEFAULT 0 COMMENT '订单总数',
    total_units INT DEFAULT 0 COMMENT '销售件数',
    avg_order_value DECIMAL(10,2) DEFAULT 0 COMMENT '客单价',
    total_cost DECIMAL(10,2) DEFAULT 0 COMMENT '总成本',
    total_ad_spend DECIMAL(10,2) DEFAULT 0 COMMENT '广告总花费',
    total_fees DECIMAL(10,2) DEFAULT 0 COMMENT '平台费用',
    net_profit DECIMAL(10,2) DEFAULT 0 COMMENT '净利润',
    profit_margin DECIMAL(5,2) DEFAULT 0 COMMENT '利润率(%)',
    total_refunds INT DEFAULT 0 COMMENT '退款订单数',
    refund_rate DECIMAL(5,2) DEFAULT 0 COMMENT '退款率(%)',
    new_reviews INT DEFAULT 0 COMMENT '新增评价数',
    avg_rating DECIMAL(3,1) DEFAULT 0 COMMENT '平均评分',
    negative_reviews INT DEFAULT 0 COMMENT '差评数',
    customer_messages INT DEFAULT 0 COMMENT '客服消息数',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_shop_date (shop_id, report_date),
    INDEX idx_shop (shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='店铺经营概览表';

-- ============================================
-- P1-3 Realtime Profit (from 30-init-tables-p1-realtime-profit.sql)
-- ============================================
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
    gross_profit DECIMAL(12,2) DEFAULT 0 COMMENT '毛利',
    net_profit DECIMAL(12,2) DEFAULT 0 COMMENT '净利',
    margin DECIMAL(5,2) COMMENT '利润率(%)',
    data_source VARCHAR(20) DEFAULT 'SYNC' COMMENT '数据来源',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_shop_sku_time (shop_id, sku, stat_time),
    INDEX idx_stat_time (stat_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='利润快照（小时级）';

CREATE TABLE IF NOT EXISTS amz_cost_allocation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '店铺ID',
    cost_type VARCHAR(32) NOT NULL COMMENT '费用类型',
    source_ref VARCHAR(64) COMMENT '费用来源编号',
    source_desc VARCHAR(256) COMMENT '费用来源描述',
    total_amount DECIMAL(12,2) NOT NULL COMMENT '总费用金额',
    currency VARCHAR(5) DEFAULT 'USD' COMMENT '币种',
    alloc_method VARCHAR(20) NOT NULL COMMENT '分摊方法',
    alloc_details TEXT COMMENT '分摊明细JSON',
    alloc_date DATE NOT NULL COMMENT '分摊日期',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop_date (shop_id, alloc_date),
    INDEX idx_cost_type (cost_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='费用分摊记录';
