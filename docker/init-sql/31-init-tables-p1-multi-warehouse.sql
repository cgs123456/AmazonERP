-- ============================================================
-- P1-4 多仓库存管理（logistics 模块升级）
-- 表：多仓库存快照 / 库存预警 / 调拨记录复用 P0-5 的 amz_inventory_transfer
-- ============================================================

-- 多仓库存快照（按仓 + SKU 统一视图）
CREATE TABLE IF NOT EXISTS amz_warehouse_stock (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '店铺ID',
    warehouse_id BIGINT NOT NULL COMMENT '仓库ID',
    warehouse_name VARCHAR(128) COMMENT '仓库名称',
    warehouse_type VARCHAR(20) COMMENT '仓库类型：LOCAL/OVERSEAS/FBA/3PL',
    sku VARCHAR(64) NOT NULL COMMENT 'SKU',
    asin VARCHAR(16) COMMENT 'ASIN',
    available_qty INT DEFAULT 0 COMMENT '可用库存',
    reserved_qty INT DEFAULT 0 COMMENT '锁定库存（已分配订单但未发货）',
    inbound_qty INT DEFAULT 0 COMMENT '在途入库数量',
    transfer_out_qty INT DEFAULT 0 COMMENT '调拨出库在途',
    total_qty INT AS (available_qty + reserved_qty + inbound_qty) COMMENT '总库存（计算列）',
    unit_cost DECIMAL(10,4) COMMENT '单位成本',
    total_value DECIMAL(12,2) COMMENT '库存总值（总库存×单位成本）',
    last_inbound_date DATE COMMENT '最后入库日期',
    days_in_stock INT DEFAULT 0 COMMENT '在库天数',
    snapshot_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_shop_warehouse_sku (shop_id, warehouse_id, sku),
    INDEX idx_sku (sku),
    INDEX idx_aging (days_in_stock)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='多仓库存快照';

-- 库存预警规则
CREATE TABLE IF NOT EXISTS amz_inventory_alert (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '店铺ID',
    sku VARCHAR(64) NOT NULL COMMENT 'SKU（NULL=全局）',
    warehouse_id BIGINT COMMENT '仓库ID（NULL=全局）',
    alert_type VARCHAR(32) NOT NULL COMMENT '预警类型：STOCKOUT/LOW_STOCK/OVERSTOCK/AGING/NO_MOVEMENT',
    threshold_value DECIMAL(12,2) NOT NULL COMMENT '阈值',
    threshold_unit VARCHAR(10) DEFAULT 'DAYS' COMMENT '阈值单位：QTY/DAYS',
    alert_level VARCHAR(10) DEFAULT 'WARNING' COMMENT '预警级别：INFO/WARNING/CRITICAL',
    notify_channels VARCHAR(128) COMMENT '通知渠道逗号分隔：EMAIL/WEBHOOK/IN_APP',
    enabled TINYINT(1) DEFAULT 1 COMMENT '启用',
    description VARCHAR(256) COMMENT '规则说明',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop (shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库存预警规则';

-- 预置示例预警规则
INSERT INTO amz_inventory_alert (shop_id, sku, alert_type, threshold_value, threshold_unit, alert_level, description) VALUES
(1, NULL, 'STOCKOUT', 5, 'QTY', 'CRITICAL', '库存低于5件紧急预警'),
(1, NULL, 'LOW_STOCK', 14, 'DAYS', 'WARNING', '可售天数低于14天预警'),
(1, NULL, 'OVERSTOCK', 120, 'DAYS', 'INFO', '库龄超过120天滞销预警'),
(1, NULL, 'AGING', 180, 'DAYS', 'WARNING', '库龄超过180天严重滞销'),
(1, NULL, 'NO_MOVEMENT', 30, 'DAYS', 'INFO', '30天无销售提醒');

-- 库存调拨记录（P0-5 已创建 amz_inventory_transfer，此处补充索引）
-- ALTER TABLE amz_inventory_transfer ADD INDEX idx_status (status);
