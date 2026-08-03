-- ============================================
-- Amazon ERP 数据报表模块升级 SQL
-- 数据库: amz_report
-- 升级内容：经营看板/利润核算/库存周转/销售趋势
-- ============================================

USE amz_report;

-- ============================================
-- 1. 利润核算明细表
-- ============================================
CREATE TABLE IF NOT EXISTS amz_profit_detail (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    amazon_order_id VARCHAR(32) NOT NULL,
    asin VARCHAR(20) NOT NULL,
    sku VARCHAR(64),
    report_date DATE NOT NULL,
    -- 收入
    product_sales DECIMAL(10,2) DEFAULT 0 COMMENT '商品销售额',
    shipping_credits DECIMAL(10,2) DEFAULT 0 COMMENT '运费收入',
    promotional_rebates DECIMAL(10,2) DEFAULT 0 COMMENT '促销返点',
    -- 成本
    product_cost DECIMAL(10,2) DEFAULT 0 COMMENT '采购成本(FIFO批次)',
    fba_fees DECIMAL(10,2) DEFAULT 0 COMMENT 'FBA履约费',
    referral_fee DECIMAL(10,2) DEFAULT 0 COMMENT '销售佣金',
    variable_closing_fee DECIMAL(10,2) DEFAULT 0 COMMENT '可变结算费',
    inbound_freight DECIMAL(10,2) DEFAULT 0 COMMENT '头程运费分摊',
    inbound_duty DECIMAL(10,2) DEFAULT 0 COMMENT '头程关税分摊',
    storage_fee DECIMAL(10,2) DEFAULT 0 COMMENT '仓储费',
    advertising_cost DECIMAL(10,2) DEFAULT 0 COMMENT '广告花费',
    vat_tax DECIMAL(10,2) DEFAULT 0 COMMENT 'VAT增值税',
    other_fees DECIMAL(10,2) DEFAULT 0 COMMENT '其他费用',
    -- 利润
    gross_profit DECIMAL(10,2) DEFAULT 0 COMMENT '毛利=收入-采购-履约-佣金',
    net_profit DECIMAL(10,2) DEFAULT 0 COMMENT '净利=毛利-广告-VAT-仓储',
    margin DECIMAL(5,2) DEFAULT 0 COMMENT '利润率(%)',
    -- 汇率
    currency VARCHAR(10) DEFAULT 'USD',
    exchange_rate DECIMAL(10,4) DEFAULT 1.0000 COMMENT '折算汇率',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop_date (shop_id, report_date),
    INDEX idx_order (amazon_order_id),
    INDEX idx_asin (asin)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='利润核算明细表';

-- ============================================
-- 2. 库存周转报表表
-- ============================================
CREATE TABLE IF NOT EXISTS amz_inventory_turnover (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    asin VARCHAR(20) NOT NULL,
    sku VARCHAR(64),
    report_date DATE NOT NULL,
    avg_inventory_value DECIMAL(10,2) DEFAULT 0 COMMENT '平均库存价值',
    cogs DECIMAL(10,2) DEFAULT 0 COMMENT '销售成本',
    turnover_rate DECIMAL(10,2) DEFAULT 0 COMMENT '周转率(=COGS/平均库存)',
    days_of_supply INT DEFAULT 0 COMMENT '供货天数',
    stockout_count INT DEFAULT 0 COMMENT '缺货次数',
    overstock_days INT DEFAULT 0 COMMENT '滞销天数',
    dead_stock_value DECIMAL(10,2) DEFAULT 0 COMMENT '呆滞库存价值',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop_date (shop_id, report_date),
    INDEX idx_asin (asin)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存周转报表';

-- ============================================
-- 3. 销售趋势日报表
-- ============================================
CREATE TABLE IF NOT EXISTS amz_sales_daily (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    asin VARCHAR(20),
    sku VARCHAR(64),
    report_date DATE NOT NULL,
    units_ordered INT DEFAULT 0,
    units_refunded INT DEFAULT 0,
    net_units INT DEFAULT 0 COMMENT '净销量=订单-退款',
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

-- ============================================
-- 4. 店铺经营概览表
-- ============================================
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
