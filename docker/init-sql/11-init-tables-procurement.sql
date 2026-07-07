-- ============================================
-- Amazon ERP 采购供应链模块建表脚本
-- 数据库: amz_procurement
-- ============================================

CREATE DATABASE IF NOT EXISTS amz_procurement DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE amz_procurement;

-- 采购订单表
CREATE TABLE IF NOT EXISTS amz_purchase_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(32) NOT NULL UNIQUE COMMENT '采购单号（业务唯一）',
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

-- 质检单表
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
