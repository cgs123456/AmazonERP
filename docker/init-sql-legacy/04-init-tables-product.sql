USE amz_product;

-- 说明：
--   amz_product 主表已迁移至 09-init-tables-p0-modules.sql（新 schema：shop_id/sku/asin/marketplace_id/title）
--   amz_shop   主表已迁移至 07-init-tables-shop.sql（新 schema：shop_name/marketplace_id/region/seller_id/spapi_*/status）
-- 本脚本仅保留购物车、浏览、优惠券等辅助表，避免与 07/09 的同名表 schema 冲突。

CREATE TABLE IF NOT EXISTS amz_cart (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, product_id BIGINT NOT NULL, count INT DEFAULT 1, custom_attribute TEXT, create_time DATETIME DEFAULT CURRENT_TIMESTAMP, update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS amz_product_browse (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, product_id BIGINT NOT NULL, create_time DATETIME DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS amz_coupon (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100) NOT NULL, discount DECIMAL(10,2) DEFAULT 0, `limit` DECIMAL(10,2) DEFAULT 0, create_time DATETIME DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS amz_user_coupon (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, coupon_id BIGINT NOT NULL, create_time DATETIME DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;