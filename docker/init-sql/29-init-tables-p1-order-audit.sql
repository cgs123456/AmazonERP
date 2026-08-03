-- ============================================================
-- P1-2 订单智能审单（order 模块升级）
-- 表：审单规则 / 拆分日志 / 发货路由
-- ============================================================

-- 审单规则
CREATE TABLE IF NOT EXISTS amz_order_audit_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '店铺ID',
    rule_name VARCHAR(128) NOT NULL COMMENT '规则名称',
    rule_type VARCHAR(32) NOT NULL COMMENT '规则类型：ADDRESS_CHECK/DUPLICATE_CHECK/RISK_CHECK/MERGE/SPLIT/CUSTOM',
    condition_field VARCHAR(64) NOT NULL COMMENT '条件字段',
    condition_op VARCHAR(16) NOT NULL DEFAULT 'EQ' COMMENT '操作符：EQ/NEQ/CONTAINS/GT/LT/GTE/LTE/REGEX',
    condition_value VARCHAR(512) NOT NULL COMMENT '条件值',
    action VARCHAR(32) NOT NULL COMMENT '动作：FLAG/BLOCK/MERGE/SPLIT/ROUTE/ALERT',
    action_params TEXT COMMENT '动作参数JSON',
    priority INT DEFAULT 0 COMMENT '优先级（数字越小优先级越高）',
    enabled TINYINT(1) DEFAULT 1 COMMENT '启用',
    description VARCHAR(512) COMMENT '规则说明',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop_enabled (shop_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能审单规则';

-- 预置示例规则
INSERT INTO amz_order_audit_rule (shop_id, rule_name, rule_type, condition_field, condition_op, condition_value, action, priority, description) VALUES
(1, 'PO Box地址检测', 'ADDRESS_CHECK', 'shipping_address', 'CONTAINS', 'PO Box', 'FLAG', 1, '检测PO Box地址标记高风险'),
(1, 'APO/FPO地址检测', 'ADDRESS_CHECK', 'shipping_address', 'CONTAINS', 'APO', 'FLAG', 2, '检测军用地址标记高风险'),
(1, '同地址合并', 'MERGE', 'shipping_address', 'EQ', '__SAME_ADDRESS__', 'MERGE', 10, '同收货地址订单建议合并发货');

-- 订单拆分日志
CREATE TABLE IF NOT EXISTS amz_order_split_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '店铺ID',
    original_order_id VARCHAR(64) NOT NULL COMMENT '原订单号',
    split_order_id VARCHAR(64) NOT NULL COMMENT '拆分子订单号',
    split_reason VARCHAR(128) COMMENT '拆分原因（缺货/多仓/多包裹等）',
    split_items TEXT COMMENT '拆分明细JSON：[{sku,qty,warehouse}]',
    operator VARCHAR(64) COMMENT '操作人',
    split_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_original (original_order_id),
    INDEX idx_shop (shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单拆分日志';

-- 发货路由
CREATE TABLE IF NOT EXISTS amz_shipment_routing (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '店铺ID',
    order_id VARCHAR(64) NOT NULL COMMENT '订单号',
    sku VARCHAR(64) NOT NULL COMMENT 'SKU',
    asin VARCHAR(16) COMMENT 'ASIN',
    quantity INT NOT NULL COMMENT '数量',
    warehouse_id BIGINT COMMENT '发货仓库ID',
    warehouse_name VARCHAR(128) COMMENT '发货仓库名称',
    warehouse_type VARCHAR(20) COMMENT '仓库类型：FBA/OVERSEAS/LOCAL/3PL',
    carrier_name VARCHAR(64) COMMENT '承运商',
    tracking_no VARCHAR(128) COMMENT '物流追踪号',
    shipping_cost DECIMAL(10,2) COMMENT '预估运费',
    selected_reason VARCHAR(128) COMMENT '选择原因',
    route_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_order (order_id),
    INDEX idx_shop (shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='发货路由记录';
