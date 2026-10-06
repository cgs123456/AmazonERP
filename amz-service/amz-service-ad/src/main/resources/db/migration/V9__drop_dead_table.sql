-- Flyway Migration V9: 清理零引用死表
-- Service: amz-service-ad
--
-- Why：amz_ad_placement_report 建于 V1，全仓无任何读写（V8 注释里「不收敛」
--   的那张），用户拍板直接清理。V8 注释中「未来复活须同步改宽」随之作废。
DROP TABLE IF EXISTS amz_ad_placement_report;
