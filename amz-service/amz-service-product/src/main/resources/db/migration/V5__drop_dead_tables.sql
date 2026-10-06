-- Flyway Migration V5: 清理零引用死表
-- Service: amz-service-product
--
-- Why：amz_cart / amz_coupon / amz_product_browse / amz_user_coupon 建于 V1，
--   全仓无任何读写（订单表 amz_order.coupon_id 是跨库属性列，无 FK 依赖本表，
--   synthetic-data 的引用池已同步摘除）。用户拍板直接清理。
DROP TABLE IF EXISTS amz_cart;
DROP TABLE IF EXISTS amz_coupon;
DROP TABLE IF EXISTS amz_product_browse;
DROP TABLE IF EXISTS amz_user_coupon;
