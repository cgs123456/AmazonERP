-- Flyway Migration V7: 删除 amz_order.coupon_id 悬空列
-- Service: amz-service-order
--
-- Why
--   amz_coupon 表已在 amz-service-product V5__drop_dead_tables.sql 中作为死表删除
--   （全仓零读写）。订单表的 coupon_id 是跨库属性列，表删后它再无引用目标，
--   成为悬空列——留着会让后人以为本仓有促销/优惠券能力。
--
--   删除前已核实引用面清零（2026-10-12 实测）：
--   - 代码：仅 Order.java 的 couponId 字段声明，无任何业务逻辑读取；
--   - 测试：仅 AllModulesUpgradeFlywayMySqlIT 断言列类型（本迁移后改为断言「列已删」）；
--   - 合成数据：generate.py 不产 coupon 数据，verify.py 的 FK_TARGETS 不含 coupon_id；
--   - API：无任何端点暴露 coupon_id（订单导出/查询响应均不含该字段）。
--
-- 兼容性：MySQL 8.0；DROP COLUMN 不可逆，但该列无任何读写方，删除不丢业务数据。
-- 口径：促销/优惠券属商业化营销域，本项目按范围前提明确不做；若未来要立项，
--   属新域建设而非恢复这一列——那时按新表设计，不要复用这个悬空引用。

ALTER TABLE amz_order
    DROP COLUMN coupon_id;
