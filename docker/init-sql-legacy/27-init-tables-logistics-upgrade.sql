-- ============================================
-- Amazon ERP 物流管理模块升级 SQL
-- 数据库: amz_logistics
-- 升级内容：物流商比价/库存调拨/头程费用分摊明细
-- ============================================

USE amz_logistics;

-- ============================================
-- 1. 物流商报价表
-- ============================================
CREATE TABLE IF NOT EXISTS amz_carrier_quote (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    carrier_name VARCHAR(50) NOT NULL COMMENT '承运商名称',
    service_type VARCHAR(30) NOT NULL COMMENT 'SEA/AIR/EXPRESS/TRUCK',
    origin_port VARCHAR(100) COMMENT '起运港',
    destination_port VARCHAR(100) COMMENT '目的港',
    transit_days INT COMMENT '预计运输天数',
    price_per_kg DECIMAL(10,2) COMMENT '每公斤运费($)',
    price_per_cbm DECIMAL(10,2) COMMENT '每立方米运费($)',
    min_charge DECIMAL(10,2) COMMENT '最低收费($)',
    fuel_surcharge_rate DECIMAL(5,2) DEFAULT 0 COMMENT '燃油附加费率(%)',
    currency VARCHAR(10) DEFAULT 'USD',
    effective_date DATE COMMENT '报价生效日期',
    expiry_date DATE COMMENT '报价失效日期',
    status VARCHAR(20) DEFAULT 'ACTIVE' COMMENT 'ACTIVE/EXPIRED/DISABLED',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop_carrier (shop_id, carrier_name),
    INDEX idx_route (origin_port, destination_port)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物流商报价表';

-- ============================================
-- 2. 库存调拨单表
-- ============================================
CREATE TABLE IF NOT EXISTS amz_inventory_transfer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    transfer_no VARCHAR(32) NOT NULL UNIQUE COMMENT '调拨单号',
    from_warehouse_id BIGINT NOT NULL COMMENT '源仓库ID',
    to_warehouse_id BIGINT NOT NULL COMMENT '目标仓库ID',
    asin VARCHAR(20) NOT NULL,
    sku VARCHAR(64) NOT NULL,
    quantity INT NOT NULL COMMENT '调拨数量',
    carrier VARCHAR(50) COMMENT '承运商',
    tracking_no VARCHAR(100) COMMENT '物流单号',
    shipping_cost DECIMAL(10,2) DEFAULT 0 COMMENT '调拨运费',
    status VARCHAR(20) DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING_APPROVAL/APPROVED/IN_TRANSIT/RECEIVED/CANCELLED',
    remark TEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop (shop_id),
    INDEX idx_status (status),
    INDEX idx_transfer_no (transfer_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存调拨单表';

-- ============================================
-- 3. 头程费用分摊明细表
-- ============================================
CREATE TABLE IF NOT EXISTS amz_freight_allocation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    shipment_id BIGINT NOT NULL COMMENT '关联货件ID',
    asin VARCHAR(20) NOT NULL,
    sku VARCHAR(64) NOT NULL,
    quantity INT NOT NULL COMMENT '分摊数量',
    weight_kg DECIMAL(10,2) COMMENT '重量(kg)',
    volume_cbm DECIMAL(10,4) COMMENT '体积(m³)',
    freight_cost DECIMAL(10,2) DEFAULT 0 COMMENT '运费分摊',
    duty_cost DECIMAL(10,2) DEFAULT 0 COMMENT '关税分摊',
    insurance_cost DECIMAL(10,2) DEFAULT 0 COMMENT '保险分摊',
    other_cost DECIMAL(10,2) DEFAULT 0 COMMENT '其他费用分摊',
    total_cost DECIMAL(10,2) DEFAULT 0 COMMENT '总头程成本',
    unit_cost DECIMAL(10,2) DEFAULT 0 COMMENT '单位头程成本',
    allocation_method VARCHAR(20) DEFAULT 'WEIGHT' COMMENT 'WEIGHT(按重量)/VOLUME(按体积)/QUANTITY(按数量)',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop (shop_id),
    INDEX idx_shipment (shipment_id),
    INDEX idx_asin (asin)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='头程费用分摊明细表';

-- ============================================
-- 4. FBA 货件签收差异表
-- ============================================
CREATE TABLE IF NOT EXISTS amz_fba_receipt_discrepancy (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    shipment_id BIGINT NOT NULL,
    asin VARCHAR(20) NOT NULL,
    sku VARCHAR(64) NOT NULL,
    expected_quantity INT NOT NULL COMMENT '应收数量',
    received_quantity INT NOT NULL COMMENT '实收数量',
    difference INT NOT NULL COMMENT '差异(实收-应收)',
    discrepancy_type VARCHAR(20) COMMENT 'OVERRECEIVED(多收)/UNDERRECEIVED(少收)/DAMAGED(破损)/NEGATIVE(负数签收)',
    status VARCHAR(20) DEFAULT 'PENDING' COMMENT 'PENDING/INVESTIGATING/RESOLVED',
    resolution VARCHAR(500) COMMENT '处理结果',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop (shop_id),
    INDEX idx_shipment (shipment_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='FBA货件签收差异表';

-- ============================================
-- 示例数据
-- ============================================
INSERT IGNORE INTO amz_carrier_quote (shop_id, carrier_name, service_type, origin_port, destination_port, transit_days, price_per_kg, price_per_cbm, min_charge, fuel_surcharge_rate, status)
VALUES
(1, 'COSCO', 'SEA', 'Shenzhen', 'Los Angeles', 25, 3.50, 850.00, 500.00, 15.00, 'ACTIVE'),
(1, 'Maersk', 'SEA', 'Shenzhen', 'Los Angeles', 22, 4.20, 950.00, 500.00, 12.00, 'ACTIVE'),
(1, 'DHL', 'EXPRESS', 'Shenzhen', 'Los Angeles', 5, 8.50, 0, 50.00, 20.00, 'ACTIVE'),
(1, 'FedEx', 'EXPRESS', 'Shenzhen', 'Los Angeles', 4, 9.00, 0, 50.00, 18.00, 'ACTIVE'),
(1, 'SF Express', 'EXPRESS', 'Shenzhen', 'Los Angeles', 7, 6.50, 0, 30.00, 15.00, 'ACTIVE');
