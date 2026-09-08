-- Flyway Migration V2: 统一订单明细持久化（B3）
-- Service: amz-service-multiplatform
--
-- 背景：parseOrders 曾只取明细首行，多商品订单静默丢数据；B3 改为全量保留，
-- 明细存本列 JSON（读时回填 items，头字段保持首行兼容）。
ALTER TABLE amz_unified_order
    ADD COLUMN items_json TEXT DEFAULT NULL COMMENT '订单明细行 JSON（sku/productName/quantity 数组）';
