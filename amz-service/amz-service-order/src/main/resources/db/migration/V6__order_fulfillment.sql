-- Flyway Migration V6: 订单发货回传字段（FBM 确认发货）
-- Service: amz-service-order
--
-- Why（可核证据：字段写入点全仓扫描）
--   1. amz_order.tracking_number 建于 V1，全仓**零写入点**；sync_status 注释写着
--      「0=未同步 1=已同步 2=已上传跟踪号 3=已完成」，但代码里只有一处 setSyncStatus(1)，
--      取值 2/3 从未被写过。也就是说「上传跟踪号」这条链路在数据结构上留了位置，
--      行为上完全不存在——FBM 卖家只能在 Amazon 后台手工确认发货。
--   2. 没有承运商列：Amazon 的 confirmShipment 要求 carrierCode（所有站点必填），
--      本地无处保存，导致「回传了什么承运商」不可追溯。
--   3. 没有「已回传」的显式状态：sync_status 的语义混杂（同步本地 / 上传跟踪号 / 已完成
--      三种含义挤在一个 int 里），无法直接回答「哪些订单还没回传 Amazon」。
--
-- 设计约束：
--   - 只加列 + 加索引，不改既有列（tracking_number / sync_status 保留，本轮建立其写入路径）。
--   - fulfillment_status 是**回传状态**，与 order_status（Amazon 侧订单状态）语义不同：
--     order_status 由同步拉取写入，fulfillment_status 由本地回传动作推进，两者不可互相覆盖。
--   - 不建物理外键（amz_order 无外键先例）。
--
-- 状态机（由服务层白名单强制，DB 层不加 CHECK）：
--   NOT_CONFIRMED → CONFIRMED（回传 Amazon 成功）
--   CONFIRMED 为当前终点；取消发货/重新回传属后续需求，届时扩展而非预置
--
-- 兼容性：MySQL 8.0；ADD COLUMN 均为可空或带默认值，对既有行无影响。
-- 回滚：ALTER TABLE amz_order 去掉 carrier_code / carrier_name / ship_confirmed_at /
--       fulfillment_status 四列与 idx_fulfillment 索引（本迁移只增列与索引，不删任何既有对象）。

ALTER TABLE amz_order
    ADD COLUMN carrier_code VARCHAR(50) DEFAULT NULL COMMENT '承运商代码（Amazon confirmShipment 的 carrierCode）',
    ADD COLUMN carrier_name VARCHAR(100) DEFAULT NULL COMMENT '承运商名称（carrierCode=Other 时必填）',
    ADD COLUMN ship_confirmed_at DATETIME DEFAULT NULL COMMENT '向 Amazon 回传发货的时间',
    ADD COLUMN fulfillment_status VARCHAR(20) DEFAULT 'NOT_CONFIRMED'
        COMMENT '回传状态：NOT_CONFIRMED/CONFIRMED（与 order_status 语义不同，见迁移注释）';

CREATE INDEX idx_fulfillment ON amz_order (shop_id, marketplace_id, fulfillment_status);
