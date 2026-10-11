-- Flyway Migration V7: 供应商付款单（三单匹配闭环）
-- Service: amz-service-procurement
--
-- Why（可核证据：状态写入点扫描）
--   1. amz_purchase_order.status 枚举里有 PAID（V1 注释），但全仓**零写入点**——
--      「已付款」这个状态在数据结构上存在、行为上不存在，采购单永远到不了 PAID。
--   2. 付款此前没有任何单据：谁付的、何时付、付了多少、对哪张 PO，全部无留痕。
--
-- 三单匹配口径（服务层白名单强制）：
--   PO 单（存在且归属正确）+ 收货证据（PO 状态 ∈ RECEIVED/COMPLETED/QC_PASSED）
--   + 金额容差（付款金额 vs PO totalAmount，默认 1%，可配）→ 三者齐备才允许付款，
--   付款成功把 PO 状态推进为 PAID。
--   偏差拒绝时点名「PO 金额 / 付款金额 / 容差」，不静默放过（付错钱是资金事故）。
--
-- 兼容性：MySQL 8.0；仅新增表。
-- 回滚：DROP TABLE amz_supplier_payment;（PO 状态由服务层同步回退，不自动处理）

CREATE TABLE IF NOT EXISTS amz_supplier_payment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '所属店铺 ID',
    payment_no VARCHAR(40) NOT NULL COMMENT '付款单号（PAY + 时间戳 + 随机数）',
    purchase_order_id BIGINT NOT NULL COMMENT '采购单 ID',
    purchase_no VARCHAR(40) DEFAULT NULL COMMENT '采购单号（冗余，便于对账）',
    amount DECIMAL(14,2) NOT NULL COMMENT '付款金额',
    currency VARCHAR(8) DEFAULT 'CNY' COMMENT '币种',
    method VARCHAR(20) DEFAULT 'OFFLINE' COMMENT '付款方式：OFFLINE/ALIBABA/BANK',
    status VARCHAR(20) NOT NULL DEFAULT 'PAID' COMMENT '状态：PAID/CANCELLED',
    po_amount_snapshot DECIMAL(14,2) DEFAULT NULL COMMENT '付款时 PO 金额快照（容差核对证据）',
    operator_id BIGINT DEFAULT NULL COMMENT '操作人用户 ID',
    remark VARCHAR(300) DEFAULT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_payment_no (payment_no),
    INDEX idx_shop_status (shop_id, status),
    INDEX idx_po (purchase_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商付款单';
