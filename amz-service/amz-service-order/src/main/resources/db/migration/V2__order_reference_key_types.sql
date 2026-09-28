-- Flyway Migration V2: 引用键类型收敛 + 订单号列消歧
-- Service: amz-service-order
--
-- Why（可核证据：tools/synthetic-data/verify.py 的 id types 门禁）
--   amz_order.product_id INT            vs amz_product.amz_product.id BIGINT
--   amz_order.user_id    INT            vs amz_user.amz_user.id       BIGINT
--   两者是真正的类型缺陷：父键 BIGINT 超出 INT 上界后会静默截断/报错，必须在有真实数据前加宽。
--
--   amz_shipment_routing.order_id VARCHAR(64) 存的是 Amazon 订单号（平台侧字符串，形如
--   111-0000000-7599439），与 amz_order.id 无关。列名 order_id 会诱导
--   `JOIN amz_order o ON o.id = r.order_id` 这类错误关联，因此重命名为 amazon_order_id，
--   与仓库既有约定（amz_order.amazon_order_id / amz_customer.*.amazon_order_id）保持一致。
--
-- 兼容性：MySQL 8.0（compose/k8s image: mysql:8.0）；不改写已执行的 V1，存量库随本迁移升级。
-- 回滚：见 docs/superpowers/runbooks/reference-key-convergence-rollback.md
--       （仅在零真实数据窗口内可逆；有真实数据后按本迁移方向继续，不再回退）。

ALTER TABLE amz_order
    MODIFY COLUMN product_id BIGINT NULL COMMENT '商品ID（对齐 amz_product.id BIGINT）';

ALTER TABLE amz_order
    MODIFY COLUMN user_id BIGINT NULL COMMENT '下单用户ID（对齐 amz_user.id BIGINT）';

ALTER TABLE amz_shipment_routing
    CHANGE COLUMN order_id amazon_order_id VARCHAR(64) NOT NULL COMMENT 'Amazon 订单号（平台侧字符串，非 amz_order.id）';

ALTER TABLE amz_shipment_routing
    RENAME INDEX idx_order TO idx_amazon_order;
