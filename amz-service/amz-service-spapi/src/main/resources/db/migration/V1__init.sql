-- Flyway Migration V1: amz_spapi database initialization
-- Service: amz-service-spapi
-- Source: docker/init-sql/09 (all tables under amz_spapi)

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

CREATE TABLE IF NOT EXISTS amz_replenishment_suggestion (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shop_id BIGINT NOT NULL,
    sku VARCHAR(50) NOT NULL,
    asin VARCHAR(20),
    stat_date DATE NOT NULL COMMENT '建议生成日期',
    current_total_stock INT NOT NULL COMMENT '当前总库存',
    baseline_demand DECIMAL(10,2) COMMENT '基线需求',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售历史';

CREATE TABLE IF NOT EXISTS amz_seasonal_index (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    category VARCHAR(50) NOT NULL COMMENT '类目',
    month INT NOT NULL COMMENT '月份 1-12',
    seasonal_index DECIMAL(4,2) NOT NULL COMMENT '季节性指数',
    UNIQUE KEY uk_category_month (category, month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='季节性指数表';

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
    multiplier DECIMAL(4,2) NOT NULL COMMENT '促销乘数',
    region VARCHAR(20) COMMENT 'NA/EU/FE/ALL',
    UNIQUE KEY uk_name (promotion_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='促销日历';

INSERT INTO amz_promotion_calendar (promotion_name, start_date, end_date, multiplier, region) VALUES
('Prime Day', '2026-07-15', '2026-07-16', 2.80, 'ALL'),
('Black Friday', '2026-11-24', '2026-11-30', 3.00, 'ALL'),
('Cyber Monday', '2026-11-30', '2026-12-01', 2.50, 'ALL'),
('Christmas Sale', '2026-12-15', '2026-12-25', 2.20, 'ALL')
ON DUPLICATE KEY UPDATE multiplier=VALUES(multiplier);
