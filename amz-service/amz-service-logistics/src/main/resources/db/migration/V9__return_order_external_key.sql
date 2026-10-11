-- Flyway Migration V9: 退货单外部幂等键（Amazon 退货报表自动导入）
-- Service: amz-service-logistics
--
-- Why（可核证据：V7 的约束与 ReturnOrderService.create 的行为）
--   1. V7 的 amz_return_order 只有 uk_return_no，而 ReturnOrderService.create 在
--      returnNo 为空时**自动生成新号**（RET+时间戳+随机数）——同一笔 Amazon 退货
--      重复导入会生成不同 return_no，唯一键拦不住重复，库存会被重复回补。
--   2. 手工建单（source=MANUAL）没有外部标识，不需要幂等键；但自动导入的
--      Amazon 退货行必须能识别「这行导过了」。
--
-- 幂等口径：external_key = 店铺 + 来源 + Amazon 退货业务指纹。
--   FBA 客户退货报表一行由 (return-date, order-id, sku) 唯一确定（同一订单同一 SKU
--   在报表里按退货日期分行），因此指纹取这三者的稳定拼接。
--   不把 quantity/disposition 放进指纹：它们会随 Amazon 处理进展变化
--   （例如 status=Unit returned 后 disposition 从 PENDING 变 SELLABLE），
--   若进指纹就会把「同一笔退货的更新」当成新行，反而制造重复。
--
-- 兼容性：MySQL 8.0；ADD COLUMN 可空 + 唯一索引对既有行无影响
--   （既有行 external_key 为 NULL，MySQL 唯一索引允许多个 NULL）。
-- 回滚：ALTER TABLE amz_return_order 去掉 external_key 列与 uk_external_key 索引。

ALTER TABLE amz_return_order
    ADD COLUMN external_key VARCHAR(128) DEFAULT NULL
        COMMENT '外部幂等键（店铺|来源|业务指纹）；手工建单为 NULL，Amazon 导入必填';

CREATE UNIQUE INDEX uk_external_key ON amz_return_order (external_key);
