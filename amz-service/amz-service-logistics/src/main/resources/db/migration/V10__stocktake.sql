-- Flyway Migration V10: 库存盘点单（账实核对）
-- Service: amz-service-logistics
--
-- Why（对标同类 ERP 标配；全仓此前零盘点实现）
--   1. 标准跨境 ERP（领星/wimoor 类）均有「盘点」：按仓库+SKU 快照账面数 → 录入实盘 →
--      差异审核 → 按实盘修正账面并留痕。本仓此前库存只有快照表，账实不符时
--      只能手工改库，无单据、无留痕、无审批——等于没有盘点能力。
--   2. 库存流水账（V8）已就绪：盘点修正以 STOCKTAKE_ADJUST 类型入流水，
--      before/after 取「apply 时的真实账面 → 实盘值」，与「快照差额」分开
--      （快照到 apply 之间账面可能已被出入库改变，按快照调会调错）。
--
-- 状态机（服务层白名单强制，DB 不加 CHECK）：
--   DRAFT(盘点中) → COUNTED(已录入实盘) → APPLIED(已按实盘修正)
--   DRAFT / COUNTED → CANCELLED
--   修正只发生在 APPLIED 一步；COUNTED 只允许录入/修改实盘数，不动账面。
--
-- 设计约束：
--   - 主表+明细两表：一次盘点覆盖多 SKU，明细行是修正与流水的最小单位。
--   - expected_qty 是建单时的账面快照（冻结），counted_qty 录入后不可被报表差异误导；
--     apply 时重新读当前账面作为 before，以 counted 为目标 SET（实盘为准语义）。
--   - 未录入实盘（counted_qty NULL）的明细行在 apply 时跳过并计数，不按 0 处理
--     （「没盘」与「盘为 0」是两件事）。
--
-- 兼容性：MySQL 8.0；仅新增表。
-- 回滚：DROP TABLE amz_stocktake_item; DROP TABLE amz_stocktake;

CREATE TABLE IF NOT EXISTS amz_stocktake (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '所属店铺 ID',
    stocktake_no VARCHAR(40) NOT NULL COMMENT '盘点单号（STK + 时间戳 + 随机数）',
    warehouse_id BIGINT NOT NULL COMMENT '盘点仓库 ID',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
        COMMENT '状态：DRAFT/COUNTED/APPLIED/CANCELLED',
    remark VARCHAR(300) DEFAULT NULL COMMENT '备注',
    operator_id BIGINT DEFAULT NULL COMMENT '建单人用户 ID',
    applied_at DATETIME DEFAULT NULL COMMENT '修正落账时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_stocktake_no (stocktake_no),
    INDEX idx_shop_status (shop_id, status),
    INDEX idx_shop_warehouse (shop_id, warehouse_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存盘点单';

CREATE TABLE IF NOT EXISTS amz_stocktake_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stocktake_id BIGINT NOT NULL COMMENT '盘点单 ID（amz_stocktake.id）',
    shop_id BIGINT NOT NULL COMMENT '所属店铺 ID（冗余，便于按店对账）',
    sku VARCHAR(100) NOT NULL COMMENT '卖家 SKU',
    expected_qty INT NOT NULL DEFAULT 0 COMMENT '建单时账面快照（冻结）',
    counted_qty INT DEFAULT NULL COMMENT '实盘数量（NULL=未盘）',
    batch_no VARCHAR(64) DEFAULT NULL COMMENT '批次号（可空；按行修正时定位库存行）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_stocktake_sku (stocktake_id, sku),
    INDEX idx_shop (shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存盘点明细';
