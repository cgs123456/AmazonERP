-- Flyway Migration V8: 库存流水账（inventory movement ledger）
-- Service: amz-service-logistics
--
-- Why（可核证据：全仓流水表扫描）
--   1. 全仓没有库存移动账：`stock_movement` / `inventory_movement` / `inventory_ledger`
--      关键词命中 0。现有库存只有「快照 + 单头」——`amz_warehouse_inventory` 存当前量，
--      出入库单只有 total_items / received_items 计数，没有行式明细。
--   2. 后果是库存变更**不可追溯、不可重放、不可对账**：一次回补之后没人能回答
--      「这次加了多少、改前多少、改后多少、谁改的、关联哪张单」。退货闭环尤其需要它——
--      货退回入仓后如果对不上账，没有流水就只能靠猜。
--   3. 本次只覆盖退货相关 + 入库/出库/FIFO/调拨收发这几类核心变更点（按既定决策）；
--      其余变更点（库位移动、预警调整）留待后续接入，表结构已按通用形状设计，不需要改表。
--
-- 设计约束：
--   - 只增不改：流水是事实记录，任何 update 都是错误用法（DB 层不加限制，靠契约测试守）。
--   - qty_before / qty_after 必填：只记 delta 无法发现「并发下 before 已经变了」这类问题。
--   - movement_type 与 source_type 分开：前者是业务动作（RETURN_INBOUND），
--     后者是单据类型（RETURN_ORDER），同一动作可能来自不同单据。
--
-- 兼容性：MySQL 8.0；仅新增表。
-- 回滚：DROP TABLE amz_inventory_movement;

CREATE TABLE IF NOT EXISTS amz_inventory_movement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '所属店铺 ID',
    warehouse_id BIGINT DEFAULT NULL COMMENT '仓库 ID（FIFO 批次变更可为空）',
    sku VARCHAR(100) NOT NULL COMMENT '卖家 SKU',
    batch_no VARCHAR(64) DEFAULT NULL COMMENT '批次号（批次变更时填写）',
    qty_before INT DEFAULT NULL COMMENT '变更前数量',
    qty_after INT DEFAULT NULL COMMENT '变更后数量',
    qty_delta INT NOT NULL COMMENT '变更量（正数入库、负数出库）',
    movement_type VARCHAR(30) NOT NULL
        COMMENT '业务动作：RETURN_INBOUND/INBOUND_RECEIVE/OUTBOUND_SHIP/FIFO_OUTBOUND/FIFO_RETURN/TRANSFER_OUT/TRANSFER_IN',
    source_type VARCHAR(30) DEFAULT NULL COMMENT '来源单据类型：RETURN_ORDER/INBOUND_ORDER/OUTBOUND_ORDER/FBA_SHIPMENT/TRANSFER',
    source_no VARCHAR(64) DEFAULT NULL COMMENT '来源单号',
    unit_cost DECIMAL(14,4) DEFAULT NULL COMMENT '单位成本（批次变更时填写）',
    operator_id BIGINT DEFAULT NULL COMMENT '操作人用户 ID',
    remark VARCHAR(300) DEFAULT NULL COMMENT '备注',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop_sku (shop_id, sku),
    INDEX idx_shop_create (shop_id, create_time),
    INDEX idx_source (shop_id, source_type, source_no),
    INDEX idx_movement_type (shop_id, movement_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存流水账（库存变更留痕，只增不改）';
