-- Flyway Migration V7: 退货单（买家退货入仓）
-- Service: amz-service-logistics
--
-- Why（可核证据：全仓退货相关代码扫描）
--   1. 全仓没有任何退货单表/实体/端点：`amz_inbound_order.source` 只有
--      FBA_TRANSFER / 1688_PURCHASE / OTHER（V1 注释），没有 RETURN；
--      `amz_outbound_order.order_type` 虽有 RETURN，但那是「退货出库」（退回供应商/报废），
--      方向相反。买家退货入仓这条链路在数据模型上完全不存在。
--   2. 财务侧的退款只走「钱」：结算报表 Refund 行 → amz_settlement_detail → REFUND 凭证。
--      「货有没有退回来、退回多少件、是良品还是残次」没有任何落点，于是
--      COGS 不会因退货而冲减（售出成本永远虚高），库存也不会回补。
--   3. 亚马逊存在「退款不退货」（retention）场景，退款金额与实物退回是两件事，
--      必须能分别记录——本表只记实物，退款金额仅作对账参考。
--
-- 状态机（由服务层白名单强制，DB 层不加 CHECK）：
--   PENDING(待收货) → RECEIVING(收货中) → RECEIVED(已收货)
--   RECEIVED → CLOSED(已结案)
--   PENDING → CANCELLED(已取消)
--   disposition 在收货时确定：GOOD(良品回补可用) / DEFECTIVE(残次待处理) / SCRAP(报废)
--
-- 设计约束：
--   - 跨库不建物理外键（与 logistics 既有表一致）。
--   - refund_amount 允许为空：退款金额在结算报表里，未必与本单同时到达。
--   - 唯一键 return_no 防重复建单（同一退货单号重复入仓会让库存虚增）。
--
-- 兼容性：MySQL 8.0；仅新增表。
-- 回滚：DROP TABLE amz_return_order;（有数据后不可回退）

CREATE TABLE IF NOT EXISTS amz_return_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '所属店铺 ID',
    return_no VARCHAR(40) NOT NULL COMMENT '退货单号（RET + 时间戳 + 随机数）',
    amazon_order_id VARCHAR(30) DEFAULT NULL COMMENT 'Amazon 订单号（退款不退货时也可能为空）',
    sku VARCHAR(100) DEFAULT NULL COMMENT '卖家 SKU',
    asin VARCHAR(20) DEFAULT NULL COMMENT 'ASIN',
    warehouse_id BIGINT DEFAULT NULL COMMENT '收货仓库 ID（收货时确定）',
    quantity INT NOT NULL DEFAULT 0 COMMENT '退货数量',
    disposition VARCHAR(20) DEFAULT NULL COMMENT '处置：GOOD/DEFECTIVE/SCRAP（收货时确定）',
    source VARCHAR(30) NOT NULL DEFAULT 'MANUAL'
        COMMENT '来源：AMAZON_FBA/AMAZON_FBM/MANUAL',
    return_reason VARCHAR(300) DEFAULT NULL COMMENT '退货原因',
    refund_amount DECIMAL(14,2) DEFAULT NULL COMMENT '关联退款金额（对账参考，可空）',
    currency VARCHAR(8) DEFAULT 'USD' COMMENT '币种',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        COMMENT '状态：PENDING/RECEIVING/RECEIVED/CLOSED/CANCELLED',
    settlement_row_key VARCHAR(64) DEFAULT NULL COMMENT '关联结算行指纹（amz_settlement_detail.row_key）',
    operator_id BIGINT DEFAULT NULL COMMENT '操作人用户 ID',
    received_at DATETIME DEFAULT NULL COMMENT '收货时间',
    closed_at DATETIME DEFAULT NULL COMMENT '结案时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_return_no (return_no),
    INDEX idx_shop_status (shop_id, status),
    INDEX idx_shop_order (shop_id, amazon_order_id),
    INDEX idx_shop_sku (shop_id, sku)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退货单（买家退货入仓）';
