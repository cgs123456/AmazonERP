-- Flyway Migration V3: 优惠券引用键类型收敛
-- Service: amz-service-order
--
-- Why（可核证据：tools/synthetic-data/verify.py 的 id types 门禁）
--   amz_order.coupon_id INT vs amz_product.amz_coupon.id BIGINT
--
--   这是 V2 收敛之后由门禁补漏发现的第 4 处真实类型缺陷。它此前没被发现，是因为
--   verify.py 的 FK_TARGETS 缺少 coupon_id / platform_account_id 两个引用池，
--   `id types OK (0)` 从未检查它们（假阴性）。
--
--   更危险的是生成器的静默降级：generate.py 的 fit() 对超出 INT 上界的值执行
--   `abs(n) % 2000000000 + 1`，会把 900000000877000000 这类 BIGINT 优惠券 ID
--   变成 877000010 这种"看起来合理"的错误值。真实数据写入时表现为
--   ERROR 1264 (out of range) 或被静默截断，两种结果都不可接受。
--
-- 兼容性：MySQL 8.0（compose/k8s image: mysql:8.0）；不改写已执行的 V1/V2，存量库随本迁移升级。
-- 回滚：见 docs/superpowers/runbooks/reference-key-convergence-rollback.md
--       （仅在零真实数据窗口内可逆；有真实数据后按本迁移方向继续，不再回退）。

ALTER TABLE amz_order
    MODIFY COLUMN coupon_id BIGINT NULL COMMENT '优惠券ID（对齐 amz_product.amz_coupon.id BIGINT）';