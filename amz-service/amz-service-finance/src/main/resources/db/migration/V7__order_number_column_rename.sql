-- Flyway Migration V7: 结算/回款表的订单号列消歧
-- Service: amz-service-finance
--
-- Why（可核证据：tools/synthetic-data/verify.py 的 id types 门禁 + SettlementParser）
--   amz_settlement_detail.order_id / amz_payment_collection.order_id 的取值来自 SP-API 结算报表
--   的 `order-id` 列（SettlementParser.COL_ORDER_ID），即 Amazon 订单号字符串，不是 amz_order.id。
--   它们必须保持 VARCHAR —— 强行改成 BIGINT 会让真实结算数据无法落库（订单号含连字符）。
--   真正的问题是列名歧义：order_id 在本库其它表里均指 amz_order.id（BIGINT），
--   这里却指平台订单号。重命名为 amazon_order_id，使三方对账（订单/结算/回款）的关联键不再有歧义。
--
-- 兼容性：MySQL 8.0（RENAME INDEX 为 8.0 语法）；不改写已执行的 V2/V3。
-- 回滚：见 docs/superpowers/runbooks/reference-key-convergence-rollback.md
--       （仅在零真实数据窗口内可逆；有真实数据后按本迁移方向继续，不再回退）。

ALTER TABLE amz_settlement_detail
    CHANGE COLUMN order_id amazon_order_id VARCHAR(64) NULL COMMENT 'Amazon 订单号（平台调整行可能为空，非 amz_order.id）';

ALTER TABLE amz_settlement_detail
    RENAME INDEX idx_shop_order TO idx_shop_amazon_order;

ALTER TABLE amz_payment_collection
    CHANGE COLUMN order_id amazon_order_id VARCHAR(64) NOT NULL COMMENT 'Amazon 订单号（平台侧字符串，非 amz_order.id）';

ALTER TABLE amz_payment_collection
    RENAME INDEX uk_shop_order TO uk_shop_amazon_order;
