-- ============================================
-- Amazon ERP 物流追踪模块建表脚本
-- 数据库: amz_logistics
-- ============================================

CREATE DATABASE IF NOT EXISTS amz_logistics DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE amz_logistics;

-- 头程物流单 / FBA 货件表
CREATE TABLE IF NOT EXISTS amz_shipment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shipment_no VARCHAR(32) NOT NULL UNIQUE COMMENT '货件编号（业务唯一）',
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

-- 物流轨迹点表
CREATE TABLE IF NOT EXISTS amz_tracking_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shipment_id BIGINT NOT NULL COMMENT '关联货件 ID',
    event_status VARCHAR(25) DEFAULT NULL COMMENT 'CREATED/DEPARTED/IN_TRANSIT/CUSTOMS_CLEARANCE/ARRIVED/OUT_FOR_DELIVERY/DELIVERED/EXCEPTION',
    location VARCHAR(200) DEFAULT NULL COMMENT '事件发生地点',
    description VARCHAR(500) DEFAULT NULL COMMENT '事件描述',
    event_time VARCHAR(25) DEFAULT NULL COMMENT '事件发生时间',
    longitude DOUBLE DEFAULT NULL COMMENT '经度（轨迹可视化）',
    latitude DOUBLE DEFAULT NULL COMMENT '纬度（轨迹可视化）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shipment (shipment_id),
    INDEX idx_event_time (event_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物流轨迹点表';
