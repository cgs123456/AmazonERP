-- Flyway Migration V9: 采购发票（三单匹配的「票」）
-- Service: amz-service-procurement
--
-- Why：
--   三单匹配（PO-收货-发票）此前只做了两单半：SupplierPaymentService 校验
--   「货已收 + 金额容差」，但全仓没有任何发票表/模型/字段——供应商开票金额
--   无法登记，也就无法在付款前核对「PO 金额 vs 收货金额 vs 发票金额」。
--   本迁移补上第三单，让付款前能看到完整的三方对差。
--
-- 语义：
--   - invoice_no 是供应商开出的发票号，同店铺内唯一（uk_shop_invoice）——
--     重复登记同一张发票会被拒，避免一张票对多个 PO 的重复入账。
--   - purchase_order_id 可空：发票常先于 PO 匹配到手（供应商先开票、后对账），
--     所以允许「先登记、后关联」。未关联的发票在核对报告里单列，
--     不当作「已匹配」混进三方核对。
--   - status 状态机：UNMATCHED（已登记未关联 PO）→ MATCHED（已关联且三方一致）
--     / CANCELLED（作废）。没有 PAID——付款是 SupplierPayment 的职责，
--     发票只管「票本身登记与匹配」，两个域不交叉写状态。
--
-- 兼容性：仅新增表。
-- 回滚：DROP TABLE amz_purchase_invoice;

CREATE TABLE IF NOT EXISTS amz_purchase_invoice (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '所属店铺 ID',
    invoice_no VARCHAR(64) NOT NULL COMMENT '供应商发票号（同店铺唯一）',
    purchase_order_id BIGINT DEFAULT NULL COMMENT '关联采购单 ID（可空：先登记后关联）',
    supplier_name VARCHAR(200) DEFAULT NULL COMMENT '开票方（冗余，便于按供应商查）',
    invoice_amount DECIMAL(12,2) NOT NULL COMMENT '发票金额（不含税额）',
    tax_amount DECIMAL(12,2) DEFAULT 0 COMMENT '税额',
    invoice_date DATE DEFAULT NULL COMMENT '开票日期',
    status VARCHAR(15) NOT NULL DEFAULT 'UNMATCHED' COMMENT 'UNMATCHED/MATCHED/CANCELLED',
    remark VARCHAR(300) DEFAULT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_shop_invoice (shop_id, invoice_no),
    INDEX idx_shop_po (shop_id, purchase_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购发票（三单匹配第三单）';
