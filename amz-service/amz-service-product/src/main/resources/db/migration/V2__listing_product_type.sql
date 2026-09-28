-- JSON_LISTINGS_FEED 必须携带源 Listing 的真实 productType。
-- 存量行先允许 NULL，由应用层在创建复制任务时 fail-closed，避免迁移阻塞生产升级。
ALTER TABLE amz_product
    ADD COLUMN product_type VARCHAR(100) NULL COMMENT 'Amazon product type，如 LUGGAGE/SHOES' AFTER category;

ALTER TABLE amz_listing_copy_task
    ADD COLUMN product_type VARCHAR(100) NULL COMMENT '提交 Feed 使用的源商品类型' AFTER sku;
