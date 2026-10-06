-- Flyway Migration V2: 清理零引用死表
-- Service: amz-service-customer
--
-- Why：amz_customer_service_kpi 建于 V1，全仓无任何读写。用户拍板直接清理。
DROP TABLE IF EXISTS amz_customer_service_kpi;
