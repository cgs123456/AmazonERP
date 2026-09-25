-- ============================================
-- Amazon ERP 海外仓 / WMS 模块建表脚本
-- 数据库: amz_logistics
-- 包含：仓库 / 仓库库存 / 入库单 / 出库单
-- ============================================

CREATE DATABASE IF NOT EXISTS amz_logistics DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE amz_logistics;

-- 海外仓表
CREATE TABLE IF NOT EXISTS amz_warehouse (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    warehouse_name VARCHAR(100) NOT NULL,
    warehouse_code VARCHAR(20) NOT NULL,
    warehouse_type VARCHAR(20) DEFAULT 'THIRD_PARTY' COMMENT 'FBA/AWD/THIRD_PARTY',
    country VARCHAR(10) NOT NULL,
    city VARCHAR(50),
    address VARCHAR(500),
    contact_name VARCHAR(50),
    contact_phone VARCHAR(30),
    capacity_cbm DECIMAL(10,2) COMMENT '容量(立方米)',
    used_cbm DECIMAL(10,2) DEFAULT 0,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop_type (shop_id, warehouse_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='海外仓表';

-- 仓库库存表（WMS 核心）
CREATE TABLE IF NOT EXISTS amz_warehouse_inventory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    warehouse_id BIGINT NOT NULL,
    shop_id BIGINT NOT NULL,
    sku VARCHAR(50) NOT NULL,
    asin VARCHAR(20),
    quantity INT DEFAULT 0,
    reserved_quantity INT DEFAULT 0 COMMENT '预留数量(已分配订单)',
    available_quantity INT GENERATED ALWAYS AS (quantity - reserved_quantity) STORED COMMENT '可用数量',
    inbound_quantity INT DEFAULT 0 COMMENT '在途数量',
    location_code VARCHAR(50) COMMENT '库位码',
    batch_no VARCHAR(50) COMMENT '批次号',
    expire_date DATE COMMENT '过期日期',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_warehouse_sku (warehouse_id, sku),
    INDEX idx_shop_sku (shop_id, sku)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库库存表';

-- 入库单
CREATE TABLE IF NOT EXISTS amz_inbound_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    inbound_no VARCHAR(50) NOT NULL UNIQUE,
    source VARCHAR(20) COMMENT 'FBA_TRANSFER/1688_PURCHASE/OTHER',
    reference_no VARCHAR(50) COMMENT '关联单号(采购单/FBA货件)',
    status VARCHAR(20) DEFAULT 'PENDING' COMMENT 'PENDING/IN_TRANSIT/RECEIVED/PARTIAL/CANCELLED',
    total_items INT DEFAULT 0,
    received_items INT DEFAULT 0,
    expected_arrival DATE,
    actual_arrival DATETIME,
    remark TEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop_status (shop_id, status),
    INDEX idx_inbound_no (inbound_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='入库单';

-- 出库单
CREATE TABLE IF NOT EXISTS amz_outbound_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    outbound_no VARCHAR(50) NOT NULL UNIQUE,
    order_type VARCHAR(20) COMMENT 'ORDER/TRANSFER/RETURN/SCRAP',
    reference_no VARCHAR(50),
    status VARCHAR(20) DEFAULT 'PENDING' COMMENT 'PENDING/PICKING/PACKED/SHIPPED/CANCELLED',
    carrier VARCHAR(50),
    tracking_no VARCHAR(100),
    total_items INT DEFAULT 0,
    shipped_items INT DEFAULT 0,
    ship_date DATETIME,
    remark TEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop_status (shop_id, status),
    INDEX idx_outbound_no (outbound_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出库单';
