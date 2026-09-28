-- Flyway Migration V3: 平台消息表的订单号列消歧
-- Service: amz-service-multiplatform
--
-- Why（可核证据：tools/synthetic-data/verify.py 的 id types 门禁）
--   amz_platform_message.order_id 存的是外部平台侧的订单号（Amazon/Walmart/eBay/... 各平台格式不同），
--   既不是 amz_order.id 也不是本地任何表的主键，必须保持 VARCHAR。
--   命名为 order_id 会被误读成本地订单主键，因此重命名为 platform_order_no，
--   与 amz_unified_order.platform_order_no 的既有命名对齐。
--
-- 兼容性：MySQL 8.0；不改写已执行的 V1。
--
-- 回滚：见 docs/superpowers/runbooks/reference-key-convergence-rollback.md
--       （仅在零真实数据窗口内可逆；有真实数据后按本迁移方向继续，不再回退）。
ALTER TABLE amz_platform_message
    CHANGE COLUMN order_id platform_order_no VARCHAR(100) NULL COMMENT '平台侧订单号（外部平台字符串，非本地订单主键）';
