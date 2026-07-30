-- ============================================
-- Amazon ERP 字段级数据权限控制建表脚本
-- 数据库: amz_user
-- ============================================
-- 设计说明：
--   field_name 必须与实体类的 Java 字段名（反射可见）保持一致，
--   FieldPermissionAspect 通过反射按字段名匹配并置空。
--   下方预置规则已根据当前实体真实字段名调整：
--     * ProfitReport 没有 cogs/fbaFee 字段，分别映射到 productCost / fbaFulfillmentFee
--     * 采购实体名为 PurchaseOrder（无 ProcurementOrder）
--     * 凭证实体名为 AccountingVoucher（无 Voucher），amount 拆为 originalAmount + cnyAmount
-- ============================================

USE amz_user;

-- 字段权限规则表
CREATE TABLE IF NOT EXISTS amz_field_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_code VARCHAR(50) NOT NULL COMMENT '角色代码 ADMIN/OPERATOR/VIEWER',
    service_name VARCHAR(50) NOT NULL COMMENT '微服务名',
    entity_name VARCHAR(100) NOT NULL COMMENT '实体名（Java 类简单名）',
    field_name VARCHAR(100) NOT NULL COMMENT '字段名（Java 反射字段名）',
    visible TINYINT(1) DEFAULT 0 COMMENT '是否可见 0-隐藏 1-可见',
    UNIQUE KEY uk_role_entity_field (role_code, service_name, entity_name, field_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字段级数据权限规则表';

-- 为已有用户表补充角色字段（默认 VIEWER，最小权限原则）
ALTER TABLE amz_user
    ADD COLUMN IF NOT EXISTS role VARCHAR(50) NOT NULL DEFAULT 'VIEWER' COMMENT '角色 ADMIN/OPERATOR/VIEWER';

-- 预置管理员（与 02-init-tables-user.sql 中 id=1 的 testuser 对齐）
UPDATE amz_user SET role = 'ADMIN' WHERE id = 1 AND username = 'testuser';

-- 预置权限规则
-- VIEWER 角色不可见成本/采购价/金额相关字段
INSERT IGNORE INTO amz_field_permission (role_code, service_name, entity_name, field_name, visible) VALUES
-- 订单利润报告（ProfitReport 实体，对应字段已标注 @FieldPermission）
('VIEWER', 'amz-service-order', 'ProfitReport', 'productCost', 0),
('VIEWER', 'amz-service-order', 'ProfitReport', 'grossProfit', 0),
('VIEWER', 'amz-service-order', 'ProfitReport', 'fbaFulfillmentFee', 0),
('VIEWER', 'amz-service-order', 'ProfitReport', 'referralFee', 0),
-- 采购订单（PurchaseOrder 实体）
('VIEWER', 'amz-service-procurement', 'PurchaseOrder', 'unitPrice', 0),
('VIEWER', 'amz-service-procurement', 'PurchaseOrder', 'totalAmount', 0),
-- 会计凭证（AccountingVoucher 实体，amount 拆为 originalAmount + cnyAmount）
('VIEWER', 'amz-service-finance', 'AccountingVoucher', 'originalAmount', 0),
('VIEWER', 'amz-service-finance', 'AccountingVoucher', 'cnyAmount', 0),

-- OPERATOR 角色可见成本相关字段
('OPERATOR', 'amz-service-order', 'ProfitReport', 'productCost', 1),
('OPERATOR', 'amz-service-order', 'ProfitReport', 'grossProfit', 1),
('OPERATOR', 'amz-service-procurement', 'PurchaseOrder', 'unitPrice', 1),
('OPERATOR', 'amz-service-procurement', 'PurchaseOrder', 'totalAmount', 1),

-- ADMIN 角色全部可见
('ADMIN', 'amz-service-order', 'ProfitReport', 'productCost', 1),
('ADMIN', 'amz-service-order', 'ProfitReport', 'grossProfit', 1),
('ADMIN', 'amz-service-procurement', 'PurchaseOrder', 'unitPrice', 1),
('ADMIN', 'amz-service-finance', 'AccountingVoucher', 'originalAmount', 1),
('ADMIN', 'amz-service-finance', 'AccountingVoucher', 'cnyAmount', 1);
