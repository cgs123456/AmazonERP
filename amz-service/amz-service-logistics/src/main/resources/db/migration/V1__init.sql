-- Flyway Migration V1: amz_logistics database initialization
-- Service: amz-service-logistics
-- Source: docker/init-sql/13, 21, 27, 31

-- ============================================
-- Shipment & Tracking (from 13-init-tables-logistics.sql)
-- ============================================
CREATE TABLE IF NOT EXISTS amz_shipment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shipment_no VARCHAR(32) NOT NULL UNIQUE COMMENT '货件编号',
    fba_shipment_id VARCHAR(32) DEFAULT NULL COMMENT 'Amazon FBA shipmentId',
    shop_id BIGINT NOT NULL COMMENT '所属店铺 ID',
    shipping_method VARCHAR(10) DEFAULT NULL COMMENT 'SEA/AIR/EXPRESS/TRUCK',
    carrier VARCHAR(50) DEFAULT NULL COMMENT '承运商',
    master_tracking_no VARCHAR(64) DEFAULT NULL COMMENT '主运单号',
    origin_port VARCHAR(100) DEFAULT NULL COMMENT '起运港口/城市',
    destination_port VARCHAR(100) DEFAULT NULL COMMENT '目的港口/城市',
    fba_warehouse_address VARCHAR(300) DEFAULT NULL COMMENT 'FBA 仓库地址',
    box_count INT DEFAULT 0 COMMENT '货物箱数',
    weight DECIMAL(10,2) DEFAULT NULL COMMENT '货物重量（kg）',
    freight_cost DECIMAL(10,2) DEFAULT NULL COMMENT '运费（USD）',
    status VARCHAR(15) DEFAULT 'CREATED' COMMENT 'CREATED/IN_TRANSIT/CUSTOMS/DELIVERED/RECEIVED/CLOSED/DELAYED/EXCEPTION',
    eta VARCHAR(20) DEFAULT NULL COMMENT '预计到港日期',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop (shop_id),
    INDEX idx_status (status),
    INDEX idx_fba (fba_shipment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='头程物流单 / FBA 货件表';

CREATE TABLE IF NOT EXISTS amz_tracking_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shipment_id BIGINT NOT NULL COMMENT '关联货件 ID',
    event_status VARCHAR(25) DEFAULT NULL,
    location VARCHAR(200) DEFAULT NULL COMMENT '事件发生地点',
    description VARCHAR(500) DEFAULT NULL COMMENT '事件描述',
    event_time VARCHAR(25) DEFAULT NULL COMMENT '事件发生时间',
    longitude DOUBLE DEFAULT NULL COMMENT '经度',
    latitude DOUBLE DEFAULT NULL COMMENT '纬度',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shipment (shipment_id),
    INDEX idx_event_time (event_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物流轨迹点表';

-- ============================================
-- Warehouse / WMS (from 21-init-tables-warehouse.sql)
-- ============================================
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

CREATE TABLE IF NOT EXISTS amz_warehouse_inventory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    warehouse_id BIGINT NOT NULL,
    shop_id BIGINT NOT NULL,
    sku VARCHAR(50) NOT NULL,
    asin VARCHAR(20),
    quantity INT DEFAULT 0,
    reserved_quantity INT DEFAULT 0 COMMENT '预留数量',
    available_quantity INT AS (quantity - reserved_quantity) STORED COMMENT '可用数量',
    inbound_quantity INT DEFAULT 0 COMMENT '在途数量',
    location_code VARCHAR(50) COMMENT '库位码',
    batch_no VARCHAR(50) COMMENT '批次号',
    expire_date DATE COMMENT '过期日期',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_warehouse_sku (warehouse_id, sku),
    INDEX idx_shop_sku (shop_id, sku)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库库存表';

CREATE TABLE IF NOT EXISTS amz_inbound_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    inbound_no VARCHAR(50) NOT NULL UNIQUE,
    source VARCHAR(20) COMMENT 'FBA_TRANSFER/1688_PURCHASE/OTHER',
    reference_no VARCHAR(50) COMMENT '关联单号',
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

-- ============================================
-- Logistics Upgrade (from 27-init-tables-logistics-upgrade.sql)
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
    allocation_method VARCHAR(20) DEFAULT 'WEIGHT' COMMENT 'WEIGHT/VOLUME/QUANTITY',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop (shop_id),
    INDEX idx_shipment (shipment_id),
    INDEX idx_asin (asin)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='头程费用分摊明细表';

CREATE TABLE IF NOT EXISTS amz_fba_receipt_discrepancy (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    shipment_id BIGINT NOT NULL,
    asin VARCHAR(20) NOT NULL,
    sku VARCHAR(64) NOT NULL,
    expected_quantity INT NOT NULL COMMENT '应收数量',
    received_quantity INT NOT NULL COMMENT '实收数量',
    difference INT NOT NULL COMMENT '差异',
    discrepancy_type VARCHAR(20) COMMENT 'OVERRECEIVED/UNDERRECEIVED/DAMAGED/NEGATIVE',
    status VARCHAR(20) DEFAULT 'PENDING' COMMENT 'PENDING/INVESTIGATING/RESOLVED',
    resolution VARCHAR(500) COMMENT '处理结果',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop (shop_id),
    INDEX idx_shipment (shipment_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='FBA货件签收差异表';

-- Seed carrier quotes
INSERT IGNORE INTO amz_carrier_quote (shop_id, carrier_name, service_type, origin_port, destination_port, transit_days, price_per_kg, price_per_cbm, min_charge, fuel_surcharge_rate, status) VALUES
(1, 'COSCO', 'SEA', 'Shenzhen', 'Los Angeles', 25, 3.50, 850.00, 500.00, 15.00, 'ACTIVE'),
(1, 'Maersk', 'SEA', 'Shenzhen', 'Los Angeles', 22, 4.20, 950.00, 500.00, 12.00, 'ACTIVE'),
(1, 'DHL', 'EXPRESS', 'Shenzhen', 'Los Angeles', 5, 8.50, 0, 50.00, 20.00, 'ACTIVE'),
(1, 'FedEx', 'EXPRESS', 'Shenzhen', 'Los Angeles', 4, 9.00, 0, 50.00, 18.00, 'ACTIVE'),
(1, 'SF Express', 'EXPRESS', 'Shenzhen', 'Los Angeles', 7, 6.50, 0, 30.00, 15.00, 'ACTIVE');

-- ============================================
-- P1-4 Multi-Warehouse (from 31-init-tables-p1-multi-warehouse.sql)
-- ============================================
CREATE TABLE IF NOT EXISTS amz_warehouse_stock (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '店铺ID',
    warehouse_id BIGINT NOT NULL COMMENT '仓库ID',
    warehouse_name VARCHAR(128) COMMENT '仓库名称',
    warehouse_type VARCHAR(20) COMMENT '仓库类型',
    sku VARCHAR(64) NOT NULL COMMENT 'SKU',
    asin VARCHAR(16) COMMENT 'ASIN',
    available_qty INT DEFAULT 0 COMMENT '可用库存',
    reserved_qty INT DEFAULT 0 COMMENT '锁定库存',
    inbound_qty INT DEFAULT 0 COMMENT '在途入库数量',
    transfer_out_qty INT DEFAULT 0 COMMENT '调拨出库在途',
    total_qty INT GENERATED ALWAYS AS (available_qty + reserved_qty + inbound_qty) STORED COMMENT '总库存',
    unit_cost DECIMAL(10,4) COMMENT '单位成本',
    total_value DECIMAL(12,2) COMMENT '库存总值',
    last_inbound_date DATE COMMENT '最后入库日期',
    days_in_stock INT DEFAULT 0 COMMENT '在库天数',
    snapshot_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_shop_warehouse_sku (shop_id, warehouse_id, sku),
    INDEX idx_sku (sku),
    INDEX idx_aging (days_in_stock)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='多仓库存快照';

CREATE TABLE IF NOT EXISTS amz_inventory_alert (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '店铺ID',
    sku VARCHAR(64) NOT NULL COMMENT 'SKU',
    warehouse_id BIGINT COMMENT '仓库ID',
    alert_type VARCHAR(32) NOT NULL COMMENT '预警类型',
    threshold_value DECIMAL(12,2) NOT NULL COMMENT '阈值',
    threshold_unit VARCHAR(10) DEFAULT 'DAYS' COMMENT '阈值单位',
    alert_level VARCHAR(10) DEFAULT 'WARNING' COMMENT '预警级别',
    notify_channels VARCHAR(128) COMMENT '通知渠道',
    enabled TINYINT(1) DEFAULT 1 COMMENT '启用',
    description VARCHAR(256) COMMENT '规则说明',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop (shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库存预警规则';

INSERT INTO amz_inventory_alert (shop_id, sku, alert_type, threshold_value, threshold_unit, alert_level, description) VALUES
(1, NULL, 'STOCKOUT', 5, 'QTY', 'CRITICAL', '库存低于5件紧急预警'),
(1, NULL, 'LOW_STOCK', 14, 'DAYS', 'WARNING', '可售天数低于14天预警'),
(1, NULL, 'OVERSTOCK', 120, 'DAYS', 'INFO', '库龄超过120天滞销预警'),
(1, NULL, 'AGING', 180, 'DAYS', 'WARNING', '库龄超过180天严重滞销'),
(1, NULL, 'NO_MOVEMENT', 30, 'DAYS', 'INFO', '30天无销售提醒');
