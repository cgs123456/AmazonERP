-- Flyway Migration V1: amz_order database initialization
-- Service: amz-service-order
-- Source: docker/init-sql/05, 08, 09 (profit portion), 29

-- ============================================
-- Order Attribute (from 05-init-tables-order.sql)
-- ============================================
CREATE TABLE IF NOT EXISTS amz_order_attribute (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    name VARCHAR(100) DEFAULT '',
    value VARCHAR(200) DEFAULT '',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================
-- Order Main Table (from 08-init-tables-amz-order.sql)
-- ============================================
CREATE TABLE IF NOT EXISTS amz_order (
    id BIGINT PRIMARY KEY,
    product_id INT COMMENT '产品ID',
    quantity INT COMMENT '商品数量',
    coupon_id INT COMMENT '优惠券ID',
    final_price DECIMAL(10,2) COMMENT '最终价格',
    user_id INT COMMENT '订单归属人ID',
    status INT DEFAULT 0 COMMENT '原状态字段',
    shop_id BIGINT COMMENT '所属店铺',
    amazon_order_id VARCHAR(30) COMMENT 'Amazon 订单号',
    marketplace_id VARCHAR(20) COMMENT '站点 ID',
    order_status VARCHAR(20) COMMENT 'Amazon 订单状态',
    buyer_name VARCHAR(100) COMMENT '买家姓名',
    purchase_date DATETIME COMMENT '购买时间',
    last_update_date DATETIME COMMENT '最后更新时间',
    fulfillment_channel VARCHAR(10) COMMENT 'AFN（FBA）或 MFN（自发货）',
    ship_service_level VARCHAR(30) COMMENT 'Standard/Expedited/Priority',
    tracking_number VARCHAR(100) COMMENT '物流跟踪号',
    sync_status INT DEFAULT 0 COMMENT '0=未同步 1=已同步 2=已上传跟踪号 3=已完成',
    UNIQUE INDEX uk_amazon_order (amazon_order_id),
    INDEX idx_shop (shop_id),
    INDEX idx_status (order_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Amazon 订单表';

-- ============================================
-- Profit & Fee Tables (from 09-init-tables-p0-modules.sql, amz_order section)
-- ============================================
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
    referral_fee_rate DECIMAL(5,4) NOT NULL COMMENT '类目佣金率',
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
    size_tier VARCHAR(30) NOT NULL COMMENT '尺寸等级',
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
    data_complete TINYINT(1) DEFAULT 1 COMMENT '数据是否完整',
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

-- ============================================
-- P1-2 Order Audit (from 29-init-tables-p1-order-audit.sql)
-- ============================================
CREATE TABLE IF NOT EXISTS amz_order_audit_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '店铺ID',
    rule_name VARCHAR(128) NOT NULL COMMENT '规则名称',
    rule_type VARCHAR(32) NOT NULL COMMENT '规则类型',
    condition_field VARCHAR(64) NOT NULL COMMENT '条件字段',
    condition_op VARCHAR(16) NOT NULL DEFAULT 'EQ' COMMENT '操作符',
    condition_value VARCHAR(512) NOT NULL COMMENT '条件值',
    action VARCHAR(32) NOT NULL COMMENT '动作',
    action_params TEXT COMMENT '动作参数JSON',
    priority INT DEFAULT 0 COMMENT '优先级',
    enabled TINYINT(1) DEFAULT 1 COMMENT '启用',
    description VARCHAR(512) COMMENT '规则说明',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop_enabled (shop_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能审单规则';

INSERT INTO amz_order_audit_rule (shop_id, rule_name, rule_type, condition_field, condition_op, condition_value, action, priority, description) VALUES
(1, 'PO Box地址检测', 'ADDRESS_CHECK', 'shipping_address', 'CONTAINS', 'PO Box', 'FLAG', 1, '检测PO Box地址标记高风险'),
(1, 'APO/FPO地址检测', 'ADDRESS_CHECK', 'shipping_address', 'CONTAINS', 'APO', 'FLAG', 2, '检测军用地址标记高风险'),
(1, '同地址合并', 'MERGE', 'shipping_address', 'EQ', '__SAME_ADDRESS__', 'MERGE', 10, '同收货地址订单建议合并发货');

CREATE TABLE IF NOT EXISTS amz_order_split_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '店铺ID',
    original_order_id VARCHAR(64) NOT NULL COMMENT '原订单号',
    split_order_id VARCHAR(64) NOT NULL COMMENT '拆分子订单号',
    split_reason VARCHAR(128) COMMENT '拆分原因',
    split_items TEXT COMMENT '拆分明细JSON',
    operator VARCHAR(64) COMMENT '操作人',
    split_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_original (original_order_id),
    INDEX idx_shop (shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单拆分日志';

CREATE TABLE IF NOT EXISTS amz_shipment_routing (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '店铺ID',
    order_id VARCHAR(64) NOT NULL COMMENT '订单号',
    sku VARCHAR(64) NOT NULL COMMENT 'SKU',
    asin VARCHAR(16) COMMENT 'ASIN',
    quantity INT NOT NULL COMMENT '数量',
    warehouse_id BIGINT COMMENT '发货仓库ID',
    warehouse_name VARCHAR(128) COMMENT '发货仓库名称',
    warehouse_type VARCHAR(20) COMMENT '仓库类型',
    carrier_name VARCHAR(64) COMMENT '承运商',
    tracking_no VARCHAR(128) COMMENT '物流追踪号',
    shipping_cost DECIMAL(10,2) COMMENT '预估运费',
    selected_reason VARCHAR(128) COMMENT '选择原因',
    route_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_order (order_id),
    INDEX idx_shop (shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='发货路由记录';
