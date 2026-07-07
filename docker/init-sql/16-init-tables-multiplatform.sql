-- ============================================
-- Amazon ERP 多平台对接模块建表脚本
-- 数据库: amz_multiplatform
-- ============================================

CREATE DATABASE IF NOT EXISTS amz_multiplatform DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE amz_multiplatform;

-- 多平台统一订单表
CREATE TABLE IF NOT EXISTS amz_unified_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    unified_order_no VARCHAR(32) NOT NULL UNIQUE COMMENT '统一订单号（UO 前缀）',
    platform VARCHAR(10) NOT NULL COMMENT '来源平台：TEMU/TIKTOK/SHEIN',
    platform_order_no VARCHAR(64) NOT NULL COMMENT '平台原始订单号',
    shop_id BIGINT NOT NULL COMMENT '所属店铺 ID',
    buyer_nickname VARCHAR(64) DEFAULT NULL COMMENT '买家昵称',
    ship_country VARCHAR(5) DEFAULT NULL COMMENT '收件国家（ISO 2 位）',
    sku VARCHAR(64) DEFAULT NULL COMMENT '商品 SKU',
    product_name VARCHAR(200) DEFAULT NULL COMMENT '商品名称',
    quantity INT DEFAULT NULL COMMENT '购买数量',
    original_amount DECIMAL(12,2) DEFAULT NULL COMMENT '订单金额（原币种）',
    currency VARCHAR(5) DEFAULT 'USD' COMMENT '币种代码',
    cny_amount DECIMAL(12,2) DEFAULT NULL COMMENT '折算人民币金额',
    status VARCHAR(15) DEFAULT 'UNPAID' COMMENT 'UNPAID/PAID/SHIPPED/DELIVERED/COMPLETED/CANCELED/REFUNDED',
    tracking_no VARCHAR(64) DEFAULT NULL COMMENT '平台物流单号',
    order_create_time VARCHAR(30) DEFAULT NULL COMMENT '平台下单时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_platform_order (platform, platform_order_no),
    INDEX idx_shop (shop_id),
    INDEX idx_platform (platform),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='多平台统一订单表';
