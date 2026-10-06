-- Flyway Migration V3: 清理零引用死表
-- Service: amz-service-ai
--
-- Why：amz_listing_seo / amz_logistics_quote（物流报价实际使用 amz_logistics
--   库的 amz_carrier_quote）/ amz_report_template 建于 V1，全仓无任何读写。
--   用户拍板直接清理。
DROP TABLE IF EXISTS amz_listing_seo;
DROP TABLE IF EXISTS amz_logistics_quote;
DROP TABLE IF EXISTS amz_report_template;
