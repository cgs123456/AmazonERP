-- Flyway Migration V6: expected_delivery_date VARCHAR(20) → DATE
-- Service: amz-service-procurement
--
-- Why（2026-10-05 全方位 review 数据层项，用户拍板完成 VARCHAR 日期列迁移）：
--   VARCHAR 存日期无法做范围查询与有效索引，且跨格式字符串的字典序与时间序不一致。
--   全部写入路径的形态已是 yyyy-MM-dd（import JSON 契约见 LogisticsImportController
--   同款文档、前端表单 placeholder 2026-10-20、synthetic-data only_date）。
--   先 STR_TO_DATE 清洗（非法值→NULL 并告警，不中断），再 MODIFY；严格模式下
--   直接 MODIFY 遇脏数据会整条迁移失败。
--
-- 兼容性：MySQL 8.0。实体 PurchaseOrder.expectedDeliveryDate 同步改 LocalDate
--   （Jackson 序列化仍为 yyyy-MM-dd，前端契约不变）。
UPDATE amz_purchase_order
   SET expected_delivery_date = DATE_FORMAT(STR_TO_DATE(expected_delivery_date, '%Y-%m-%d'), '%Y-%m-%d')
 WHERE expected_delivery_date IS NOT NULL;

ALTER TABLE amz_purchase_order
    MODIFY COLUMN expected_delivery_date DATE DEFAULT NULL COMMENT '预计交期';
