-- Flyway Migration V1: amz_procurement database initialization
-- Service: amz-service-procurement
-- Source: docker/init-sql/11, 23

-- ============================================
-- Purchase Order & Quality Check (from 11-init-tables-procurement.sql)
-- ============================================
CREATE TABLE IF NOT EXISTS amz_purchase_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(32) NOT NULL UNIQUE COMMENT '采购单号',
    shop_id BIGINT NOT NULL COMMENT '所属店铺 ID',
    supplier_offer_id VARCHAR(64) DEFAULT NULL COMMENT '1688 供应商 offerId',
    supplier_name VARCHAR(200) DEFAULT NULL COMMENT '供应商名称',
    sku VARCHAR(64) NOT NULL COMMENT '采购商品 SKU',
    quantity INT NOT NULL COMMENT '采购数量',
    unit_price DECIMAL(10,2) NOT NULL COMMENT '采购单价（含税 CNY）',
    total_amount DECIMAL(12,2) DEFAULT NULL COMMENT '总金额（CNY）',
    status VARCHAR(20) DEFAULT 'DRAFT' COMMENT 'DRAFT/SUBMITTED/PAID/PRODUCING/SHIPPED/QC_PENDING/QC_PASSED/QC_FAILED/RECEIVED/COMPLETED/CANCELED',
    alibaba_order_no VARCHAR(64) DEFAULT NULL COMMENT '1688 平台订单号',
    expected_delivery_date VARCHAR(20) DEFAULT NULL COMMENT '预计交期',
    tracking_no VARCHAR(64) DEFAULT NULL COMMENT '物流单号',
    remark VARCHAR(500) DEFAULT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop (shop_id),
    INDEX idx_status (status),
    INDEX idx_alibaba (alibaba_order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购订单表';

CREATE TABLE IF NOT EXISTS amz_quality_check (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    purchase_order_id BIGINT NOT NULL COMMENT '关联采购单 ID',
    sample_count INT NOT NULL COMMENT '抽检总数',
    passed_count INT DEFAULT NULL COMMENT '合格数',
    failed_count INT DEFAULT 0 COMMENT '不合格数',
    pass_rate DECIMAL(5,2) DEFAULT NULL COMMENT '合格率（%）',
    defect_description VARCHAR(500) DEFAULT NULL COMMENT '缺陷类型描述',
    result VARCHAR(15) DEFAULT NULL COMMENT 'PASS/FAIL/CONDITIONAL',
    inspector VARCHAR(50) DEFAULT NULL COMMENT '质检员',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_purchase (purchase_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质检单表';

-- ============================================
-- Procurement Upgrade (from 23-init-tables-procurement-upgrade.sql)
-- ============================================
CREATE TABLE IF NOT EXISTS amz_supplier (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '所属店铺 ID',
    supplier_name VARCHAR(200) NOT NULL COMMENT '供应商名称',
    supplier_code VARCHAR(32) NOT NULL COMMENT '供应商编码',
    contact_name VARCHAR(50) COMMENT '联系人',
    contact_phone VARCHAR(30) COMMENT '联系电话',
    contact_email VARCHAR(100) COMMENT '联系邮箱',
    address VARCHAR(500) COMMENT '供应商地址',
    alibaba_shop_url VARCHAR(500) COMMENT '1688 店铺 URL',
    alibaba_member_id VARCHAR(64) COMMENT '1688 会员 ID',
    payment_terms VARCHAR(50) COMMENT '付款方式',
    rating DECIMAL(3,1) DEFAULT 0 COMMENT '综合评分(0-5)',
    on_time_delivery_rate DECIMAL(5,2) DEFAULT 0 COMMENT '准时交货率(%)',
    quality_pass_rate DECIMAL(5,2) DEFAULT 0 COMMENT '质量合格率(%)',
    price_competitiveness DECIMAL(3,1) DEFAULT 0 COMMENT '价格竞争力评分(0-5)',
    response_speed DECIMAL(3,1) DEFAULT 0 COMMENT '响应速度评分(0-5)',
    total_orders INT DEFAULT 0 COMMENT '历史采购单总数',
    total_amount DECIMAL(12,2) DEFAULT 0 COMMENT '历史采购总额(CNY)',
    status VARCHAR(20) DEFAULT 'ACTIVE' COMMENT 'ACTIVE/BLACKLISTED/DISABLED',
    remark VARCHAR(500),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_supplier_code (shop_id, supplier_code),
    INDEX idx_shop (shop_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商档案表';

CREATE TABLE IF NOT EXISTS amz_supplier_product (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    supplier_id BIGINT NOT NULL COMMENT '供应商 ID',
    shop_id BIGINT NOT NULL,
    sku VARCHAR(64) NOT NULL COMMENT '商品 SKU',
    asin VARCHAR(20) COMMENT '关联 ASIN',
    supplier_offer_id VARCHAR(64) COMMENT '1688 offerId',
    supplier_sku_code VARCHAR(64) COMMENT '供应商侧 SKU 编码',
    supply_price DECIMAL(10,2) NOT NULL COMMENT '供货价(CNY)',
    moq INT DEFAULT 1 COMMENT '最小起订量',
    lead_time_days INT DEFAULT 7 COMMENT '交货周期(天)',
    packaging_spec VARCHAR(200) COMMENT '包装规格',
    unit_weight DECIMAL(10,3) COMMENT '单件重量(kg)',
    unit_volume DECIMAL(10,3) COMMENT '单件体积(m³)',
    is_preferred TINYINT DEFAULT 0 COMMENT '是否首选供应商',
    status VARCHAR(20) DEFAULT 'ACTIVE',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_supplier (supplier_id),
    INDEX idx_shop_sku (shop_id, sku),
    INDEX idx_preferred (shop_id, sku, is_preferred)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商-SKU关联表';

CREATE TABLE IF NOT EXISTS amz_purchase_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_no VARCHAR(32) NOT NULL UNIQUE COMMENT '计划编号',
    shop_id BIGINT NOT NULL,
    sku VARCHAR(64) NOT NULL,
    asin VARCHAR(20),
    suggested_qty INT NOT NULL COMMENT '建议采购数量',
    planned_qty INT NOT NULL COMMENT '实际计划数量',
    unit_price DECIMAL(10,2) COMMENT '预计采购单价(CNY)',
    total_amount DECIMAL(12,2) COMMENT '预计总金额(CNY)',
    supplier_id BIGINT COMMENT '指定供应商',
    urgency VARCHAR(15) DEFAULT 'NORMAL' COMMENT 'URGENT/HIGH/NORMAL/LOW',
    source VARCHAR(30) DEFAULT 'AUTO' COMMENT 'AUTO/MANUAL',
    replenishment_data JSON COMMENT '补货引擎原始数据(JSON)',
    status VARCHAR(20) DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING_APPROVAL/APPROVED/REJECTED/CONVERTED/CANCELED',
    approved_by VARCHAR(50) COMMENT '审批人',
    approved_time DATETIME,
    remark VARCHAR(500),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop_status (shop_id, status),
    INDEX idx_urgency (urgency),
    INDEX idx_plan_no (plan_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购计划表';

CREATE TABLE IF NOT EXISTS amz_purchase_order_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    purchase_order_id BIGINT NOT NULL COMMENT '关联采购单 ID',
    sku VARCHAR(64) NOT NULL COMMENT '商品 SKU',
    asin VARCHAR(20),
    supplier_id BIGINT COMMENT '供应商 ID',
    supplier_offer_id VARCHAR(64) COMMENT '1688 offerId',
    quantity INT NOT NULL COMMENT '采购数量',
    received_quantity INT DEFAULT 0 COMMENT '已收货数量',
    unit_price DECIMAL(10,2) NOT NULL COMMENT '采购单价(CNY)',
    total_amount DECIMAL(12,2) COMMENT '明细总金额',
    remark VARCHAR(500),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_order (purchase_order_id),
    INDEX idx_sku (sku)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购订单明细表';

-- Alter existing amz_purchase_order to add upgrade columns
ALTER TABLE amz_purchase_order
    ADD COLUMN IF NOT EXISTS supplier_id BIGINT DEFAULT NULL COMMENT '供应商 ID',
    ADD COLUMN IF NOT EXISTS plan_id BIGINT DEFAULT NULL COMMENT '关联采购计划 ID',
    ADD COLUMN IF NOT EXISTS approver VARCHAR(50) DEFAULT NULL COMMENT '审批人',
    ADD COLUMN IF NOT EXISTS approved_time DATETIME DEFAULT NULL COMMENT '审批时间',
    ADD COLUMN IF NOT EXISTS total_quantity INT DEFAULT NULL COMMENT '总数量',
    ADD COLUMN IF NOT EXISTS currency VARCHAR(10) DEFAULT 'CNY' COMMENT '币种';

CREATE TABLE IF NOT EXISTS amz_inventory_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    batch_no VARCHAR(32) NOT NULL UNIQUE COMMENT '批次号',
    purchase_order_id BIGINT COMMENT '关联采购单',
    inbound_order_id BIGINT COMMENT '关联入库单',
    sku VARCHAR(64) NOT NULL,
    asin VARCHAR(20),
    warehouse_id BIGINT COMMENT '仓库 ID',
    quantity INT NOT NULL COMMENT '批次总数量',
    available_quantity INT NOT NULL COMMENT '可用数量',
    unit_cost DECIMAL(10,2) NOT NULL COMMENT '批次单位成本',
    freight_cost DECIMAL(10,2) DEFAULT 0 COMMENT '头程运费分摊',
    customs_cost DECIMAL(10,2) DEFAULT 0 COMMENT '关税分摊',
    other_cost DECIMAL(10,2) DEFAULT 0 COMMENT '其他费用分摊',
    total_cost DECIMAL(12,2) COMMENT '批次总成本',
    inbound_date DATE NOT NULL COMMENT '入库日期',
    expire_date DATE COMMENT '过期日期',
    status VARCHAR(20) DEFAULT 'ACTIVE' COMMENT 'ACTIVE/EXPIRED/DEPLETED',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop_sku (shop_id, sku),
    INDEX idx_batch_no (batch_no),
    INDEX idx_inbound_date (inbound_date),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存批次表(FIFO)';

CREATE TABLE IF NOT EXISTS amz_fba_shipment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    shipment_no VARCHAR(32) NOT NULL UNIQUE COMMENT '货件编号',
    fba_shipment_id VARCHAR(32) COMMENT 'Amazon FBA shipmentId',
    warehouse_id BIGINT COMMENT '源仓库 ID',
    destination_fba_code VARCHAR(20) COMMENT '目的 FBA 仓库编码',
    destination_address VARCHAR(500) COMMENT 'FBA 仓库地址',
    shipping_method VARCHAR(10) COMMENT 'SEA/AIR/EXPRESS/TRUCK',
    carrier VARCHAR(50) COMMENT '承运商',
    master_tracking_no VARCHAR(64) COMMENT '主运单号',
    box_count INT DEFAULT 0 COMMENT '箱数',
    total_weight DECIMAL(10,2) DEFAULT 0 COMMENT '总重量(kg)',
    total_volume DECIMAL(10,3) DEFAULT 0 COMMENT '总体积(m³)',
    freight_cost DECIMAL(10,2) DEFAULT 0 COMMENT '头程运费',
    customs_cost DECIMAL(10,2) DEFAULT 0 COMMENT '报关费用',
    tax_cost DECIMAL(10,2) DEFAULT 0 COMMENT '税费',
    other_cost DECIMAL(10,2) DEFAULT 0 COMMENT '其他费用',
    total_cost DECIMAL(12,2) DEFAULT 0 COMMENT '头程总成本',
    status VARCHAR(20) DEFAULT 'CREATED',
    eta DATE COMMENT '预计到港日期',
    actual_arrival DATE COMMENT '实际到港日期',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop (shop_id),
    INDEX idx_status (status),
    INDEX idx_fba (fba_shipment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='FBA货件表';

CREATE TABLE IF NOT EXISTS amz_fba_shipment_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    fba_shipment_id BIGINT NOT NULL COMMENT '关联 FBA 货件 ID',
    sku VARCHAR(64) NOT NULL,
    asin VARCHAR(20),
    quantity INT NOT NULL COMMENT '发货数量',
    received_quantity INT DEFAULT 0 COMMENT 'FBA 签收数量',
    batch_no VARCHAR(32) COMMENT '关联批次号',
    unit_cost DECIMAL(10,2) COMMENT '单位成本',
    freight_allocation DECIMAL(10,2) DEFAULT 0 COMMENT '运费分摊',
    customs_allocation DECIMAL(10,2) DEFAULT 0 COMMENT '关税分摊',
    total_cost DECIMAL(12,2) COMMENT '明细总成本',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shipment (fba_shipment_id),
    INDEX idx_sku (sku)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='FBA货件明细表';

CREATE TABLE IF NOT EXISTS amz_purchase_approval (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    ref_type VARCHAR(20) NOT NULL COMMENT 'PLAN/ORDER',
    ref_id BIGINT NOT NULL COMMENT '关联单据 ID',
    action VARCHAR(20) NOT NULL COMMENT 'APPROVE/REJECT',
    operator VARCHAR(50) NOT NULL COMMENT '操作人',
    comment VARCHAR(500) COMMENT '审批意见',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ref (ref_type, ref_id),
    INDEX idx_shop (shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购审批记录表';

-- Seed data
INSERT IGNORE INTO amz_supplier (shop_id, supplier_name, supplier_code, contact_name, contact_phone, rating, on_time_delivery_rate, quality_pass_rate, price_competitiveness, response_speed, total_orders, total_amount, status) VALUES
(1, '深圳市华强北电子科技有限公司', 'SUP-001', '张经理', '13800138001', 4.5, 95.50, 97.00, 4.0, 4.5, 15, 125000.00, 'ACTIVE'),
(1, '义乌市小商品批发城', 'SUP-002', '李老板', '13900139002', 4.0, 90.00, 93.50, 3.5, 4.0, 8, 56000.00, 'ACTIVE'),
(1, '广州白马服装市场', 'SUP-003', '王总', '13700137003', 4.8, 98.00, 99.00, 4.5, 5.0, 22, 280000.00, 'ACTIVE');

INSERT IGNORE INTO amz_supplier_product (supplier_id, shop_id, sku, asin, supplier_offer_id, supply_price, moq, lead_time_days, is_preferred, status) VALUES
(1, 1, 'SKU-001', 'B0TEST001', 'offer_001', 15.50, 100, 7, 1, 'ACTIVE'),
(1, 1, 'SKU-002', 'B0TEST002', 'offer_002', 28.00, 50, 10, 1, 'ACTIVE'),
(2, 1, 'SKU-001', 'B0TEST001', 'offer_003', 16.00, 100, 5, 0, 'ACTIVE'),
(3, 1, 'SKU-003', 'B0TEST003', 'offer_004', 45.00, 30, 14, 1, 'ACTIVE');
