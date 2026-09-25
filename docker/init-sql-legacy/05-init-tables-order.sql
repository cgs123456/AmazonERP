USE amz_order;

-- 说明：
--   amz_order 主表已迁移至 08-init-tables-amz-order.sql（新 schema：quantity/coupon_id/final_price/amazon_* 等多店铺同步字段）
-- 本脚本仅保留订单属性辅助表，避免与 08 的同名表 schema 冲突。

CREATE TABLE IF NOT EXISTS amz_order_attribute (id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT NOT NULL, name VARCHAR(100) DEFAULT '', value VARCHAR(200) DEFAULT '', create_time DATETIME DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;