-- ============================================================
-- Amazon ERP 蓝图 4 大 P0 模块建表 SQL
-- 数据库: amz_spapi / amz_order / amz_product
-- ============================================================

-- ============ 模块 1：FBA 库存同步（3 张表，amz_spapi 库）============

CREATE TABLE IF NOT EXISTS amz_fba_inventory (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shop_id BIGINT NOT NULL COMMENT '店铺ID',
    marketplace_id VARCHAR(20) NOT NULL COMMENT 'Marketplace ID',
    sku VARCHAR(50) NOT NULL COMMENT '卖家 SKU',
    asin VARCHAR(20) COMMENT 'ASIN',
    fn_sku VARCHAR(50) COMMENT 'Fulfillment Network SKU',
    product_name VARCHAR(500) COMMENT '商品名',
    available_quantity INT DEFAULT 0 COMMENT '可售库存',
    unfulfillable_quantity INT DEFAULT 0 COMMENT '不可售库存',
    inbound_working INT DEFAULT 0 COMMENT '在途入库',
    inbound_shipped INT DEFAULT 0 COMMENT '已发货入库',
    last_updated_time DATETIME COMMENT 'Amazon 最后更新时间',
    sync_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '本地同步时间',
    -- 健康度分析字段
    days_of_supply DECIMAL(8,2) COMMENT '库存可供天数 DOS',
    avg_7_days DECIMAL(10,2) COMMENT '7 天日均销量',
    avg_30_days DECIMAL(10,2) COMMENT '30 天日均销量',
    health_status VARCHAR(20) DEFAULT 'HEALTHY' COMMENT 'URGENT/AT_RISK/HEALTHY/OVERSTOCK/STOCKOUT',
    UNIQUE KEY uk_shop_market_sku (shop_id, marketplace_id, sku),
    INDEX idx_health (health_status),
    INDEX idx_shop (shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='FBA 库存主表';

CREATE TABLE IF NOT EXISTS amz_product_sales_stats (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shop_id BIGINT NOT NULL,
    sku VARCHAR(50) NOT NULL,
    stat_date DATE NOT NULL COMMENT '统计日期',
    qty_1_day INT DEFAULT 0 COMMENT '当日销量',
    qty_7_days INT DEFAULT 0 COMMENT '7 天累计',
    qty_30_days INT DEFAULT 0 COMMENT '30 天累计',
    qty_90_days INT DEFAULT 0 COMMENT '90 天累计',
    UNIQUE KEY uk_shop_sku_date (shop_id, sku, stat_date),
    INDEX idx_date (stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品销量统计';

CREATE TABLE IF NOT EXISTS amz_inventory_sync_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shop_id BIGINT NOT NULL,
    sync_type VARCHAR(20) NOT NULL COMMENT 'INVENTORY/ORDERS',
    status VARCHAR(20) NOT NULL COMMENT 'SUCCESS/FAILED',
    records_synced INT DEFAULT 0,
    error_message TEXT,
    start_time DATETIME,
    end_time DATETIME,
    INDEX idx_shop_time (shop_id, start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步日志';


-- ============ 模块 2：智能补货引擎（4 张表，amz_spapi 库）============

CREATE TABLE IF NOT EXISTS amz_replenishment_suggestion (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shop_id BIGINT NOT NULL,
    sku VARCHAR(50) NOT NULL,
    asin VARCHAR(20),
    stat_date DATE NOT NULL COMMENT '建议生成日期',
    current_total_stock INT NOT NULL COMMENT '当前总库存',
    baseline_demand DECIMAL(10,2) COMMENT '基线需求（加权日均）',
    safety_factor DECIMAL(4,2) COMMENT '安全系数',
    seasonal_index DECIMAL(4,2) COMMENT '季节性指数',
    promotion_multiplier DECIMAL(4,2) COMMENT '促销乘数',
    suggested_replenish_qty INT COMMENT '建议补货量',
    estimated_stockout_date DATE COMMENT '预计断货日期',
    urgency_level VARCHAR(20) COMMENT 'URGENT/NORMAL/LOW',
    UNIQUE KEY uk_shop_sku_date (shop_id, sku, stat_date),
    INDEX idx_urgency (urgency_level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='补货建议';

CREATE TABLE IF NOT EXISTS amz_sales_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shop_id BIGINT NOT NULL,
    sku VARCHAR(50) NOT NULL,
    sale_date DATE NOT NULL,
    quantity INT DEFAULT 0,
    revenue DECIMAL(12,2) DEFAULT 0,
    UNIQUE KEY uk_shop_sku_date (shop_id, sku, sale_date),
    INDEX idx_date (sale_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售历史（按日）';

CREATE TABLE IF NOT EXISTS amz_seasonal_index (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    category VARCHAR(50) NOT NULL COMMENT '类目',
    month INT NOT NULL COMMENT '月份 1-12',
    seasonal_index DECIMAL(4,2) NOT NULL COMMENT '季节性指数 1.0=正常',
    UNIQUE KEY uk_category_month (category, month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='季节性指数表';

-- 预置数据：玩具、户外、电子、服装、家居 5 类目 12 月份
INSERT INTO amz_seasonal_index (category, month, seasonal_index) VALUES
('TOYS', 11, 1.50), ('TOYS', 12, 1.80), ('TOYS', 1, 0.60),
('OUTDOOR', 4, 1.30), ('OUTDOOR', 7, 1.50), ('OUTDOOR', 12, 0.40),
('ELECTRONICS', 11, 1.40), ('ELECTRONICS', 12, 1.60),
('APPAREL', 3, 1.20), ('APPAREL', 9, 1.30),
('HOME', 10, 1.10), ('HOME', 12, 1.20)
ON DUPLICATE KEY UPDATE seasonal_index=VALUES(seasonal_index);

CREATE TABLE IF NOT EXISTS amz_promotion_calendar (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    promotion_name VARCHAR(100) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    multiplier DECIMAL(4,2) NOT NULL COMMENT '促销乘数 2.5-3.0',
    region VARCHAR(20) COMMENT 'NA/EU/FE/ALL',
    UNIQUE KEY uk_name (promotion_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='促销日历';

INSERT INTO amz_promotion_calendar (promotion_name, start_date, end_date, multiplier, region) VALUES
('Prime Day', '2026-07-15', '2026-07-16', 2.80, 'ALL'),
('Black Friday', '2026-11-24', '2026-11-30', 3.00, 'ALL'),
('Cyber Monday', '2026-11-30', '2026-12-01', 2.50, 'ALL'),
('Christmas Sale', '2026-12-15', '2026-12-25', 2.20, 'ALL')
ON DUPLICATE KEY UPDATE multiplier=VALUES(multiplier);


-- ============ 模块 3：财务利润核算（5 张表，amz_order 库）============

CREATE TABLE IF NOT EXISTS amz_product_cost (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shop_id BIGINT NOT NULL,
    sku VARCHAR(50) NOT NULL,
    unit_cost DECIMAL(10,2) NOT NULL COMMENT '采购单价',
    shipping_cost DECIMAL(10,2) DEFAULT 0 COMMENT '头程运费',
    customs_cost DECIMAL(10,2) DEFAULT 0 COMMENT '关税',
    lead_time_days INT DEFAULT 30 COMMENT '采购周期',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_shop_sku (shop_id, sku)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购成本表';

CREATE TABLE IF NOT EXISTS amz_category_fee_rate (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    category_name VARCHAR(50) NOT NULL,
    referral_fee_rate DECIMAL(5,4) NOT NULL COMMENT '类目佣金率 0.15=15%',
    UNIQUE KEY uk_category (category_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='类目佣金率';

INSERT INTO amz_category_fee_rate (category_name, referral_fee_rate) VALUES
('Electronics', 0.08), ('Computers', 0.06), ('Camera', 0.08),
('Home', 0.15), ('Kitchen', 0.15), ('Toys', 0.15),
('Apparel', 0.17), ('Beauty', 0.15), ('Health', 0.15),
('Sports', 0.15), ('Books', 0.15), ('Jewelry', 0.20)
ON DUPLICATE KEY UPDATE referral_fee_rate=VALUES(referral_fee_rate);

CREATE TABLE IF NOT EXISTS amz_fba_fee_table (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    size_tier VARCHAR(30) NOT NULL COMMENT 'small-standard/large-standard/...',
    weight_g INT NOT NULL COMMENT '重量（克）',
    region VARCHAR(20) NOT NULL COMMENT 'NA/EU/FE',
    fulfillment_fee DECIMAL(10,2) NOT NULL COMMENT 'FBA 履约费',
    storage_fee_per_month DECIMAL(10,2) NOT NULL COMMENT '月仓储费',
    UNIQUE KEY uk_size_weight_region (size_tier, weight_g, region)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='FBA 费率表';

INSERT INTO amz_fba_fee_table (size_tier, weight_g, region, fulfillment_fee, storage_fee_per_month) VALUES
('small-standard', 250, 'NA', 3.22, 0.83),
('small-standard', 250, 'EU', 2.50, 0.65),
('large-standard', 500, 'NA', 4.95, 0.83),
('large-standard', 500, 'EU', 3.80, 0.65),
('large-standard', 1000, 'NA', 5.78, 1.20),
('large-standard', 1000, 'EU', 4.45, 0.95),
('small-oversize', 2000, 'NA', 8.26, 1.50),
('large-oversize', 5000, 'NA', 11.37, 2.40),
('special-oversize', 20000, 'NA', 137.32, 6.50)
ON DUPLICATE KEY UPDATE fulfillment_fee=VALUES(fulfillment_fee);

CREATE TABLE IF NOT EXISTS amz_profit_report (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shop_id BIGINT NOT NULL,
    amazon_order_id VARCHAR(30) NOT NULL,
    sku VARCHAR(50) NOT NULL,
    stat_date DATE NOT NULL,
    revenue DECIMAL(12,2) COMMENT '收入',
    product_cost DECIMAL(12,2) COMMENT '采购成本',
    fba_fulfillment_fee DECIMAL(12,2) COMMENT 'FBA 履约费',
    fba_storage_fee DECIMAL(12,2) COMMENT 'FBA 仓储费',
    referral_fee DECIMAL(12,2) COMMENT '平台佣金',
    ad_cost DECIMAL(12,2) COMMENT '广告费',
    vat DECIMAL(12,2) COMMENT 'VAT',
    gross_profit DECIMAL(12,2) COMMENT '毛利',
    net_profit DECIMAL(12,2) COMMENT '净利',
    net_margin DECIMAL(6,4) COMMENT '净利率',
    UNIQUE KEY uk_shop_order_sku (shop_id, amazon_order_id, sku),
    INDEX idx_date (stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单级利润报告';

CREATE OR REPLACE VIEW v_profit_summary_by_sku AS
SELECT
    shop_id,
    sku,
    DATE_FORMAT(stat_date, '%Y-%m') AS month,
    SUM(revenue) AS total_revenue,
    SUM(product_cost) AS total_cost,
    SUM(net_profit) AS total_profit,
    ROUND(SUM(net_profit) / NULLIF(SUM(revenue), 0), 4) AS margin
FROM amz_profit_report
GROUP BY shop_id, sku, DATE_FORMAT(stat_date, '%Y-%m');


-- ============ 模块 4：跨站点 Listing 复制（3 张表，amz_product 库）============

CREATE TABLE IF NOT EXISTS amz_product (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shop_id BIGINT NOT NULL,
    sku VARCHAR(50) NOT NULL,
    asin VARCHAR(20),
    marketplace_id VARCHAR(20) NOT NULL,
    title VARCHAR(500) NOT NULL,
    description TEXT,
    brand VARCHAR(100),
    price DECIMAL(10,2),
    currency VARCHAR(10) DEFAULT 'USD',
    category VARCHAR(50),
    size_tier VARCHAR(30),
    weight_g INT,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_shop_sku_market (shop_id, sku, marketplace_id),
    INDEX idx_asin (asin)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Amazon 商品主数据';

CREATE TABLE IF NOT EXISTS amz_listing_copy_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shop_id BIGINT NOT NULL,
    source_marketplace_id VARCHAR(20) NOT NULL,
    target_marketplace_id VARCHAR(20) NOT NULL,
    sku VARCHAR(50) NOT NULL,
    source_title VARCHAR(500),
    source_price DECIMAL(10,2),
    target_title VARCHAR(500),
    target_price DECIMAL(10,2),
    target_language VARCHAR(10) COMMENT 'de/it/es/fr/ja',
    exchange_rate DECIMAL(10,4),
    price_markup DECIMAL(4,2) DEFAULT 0.20 COMMENT '加价比例 0.20=20%',
    status VARCHAR(20) DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/SUBMITTED/SUCCESS/FAILED',
    feed_submission_id VARCHAR(50) COMMENT 'Amazon Feed ID',
    error_message TEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop_status (shop_id, status),
    INDEX idx_sku (sku)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Listing 复制任务';

CREATE TABLE IF NOT EXISTS amz_translation_cache (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_text_hash VARCHAR(64) NOT NULL COMMENT 'SHA-256 原文哈希',
    source_lang VARCHAR(10) NOT NULL,
    target_lang VARCHAR(10) NOT NULL,
    source_text TEXT,
    translated_text TEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_hash_langs (source_text_hash, source_lang, target_lang)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='翻译缓存';
