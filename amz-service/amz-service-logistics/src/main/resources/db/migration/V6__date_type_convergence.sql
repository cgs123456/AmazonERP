-- Flyway Migration V6: eta VARCHAR(20) → DATE；event_time VARCHAR(25) → DATETIME
-- Service: amz-service-logistics
--
-- Why（2026-10-05 全方位 review 数据层项，用户拍板完成 VARCHAR 日期列迁移）：
--   VARCHAR 存日期无法做范围查询与有效索引（idx_event_time 建在 VARCHAR 上，
--   只支持字典序比较）。两个列的全部写入路径形态已归一：
--   - amz_shipment.eta：import JSON 契约 "eta":"2026-09-20"、synthetic-data only_date、
--     前端表单 placeholder 2026-10-30 —— 全部 yyyy-MM-dd；
--   - amz_tracking_event.event_time：TrackingIngestServiceImpl.normalize()/canonicalTime()
--     在写入时统一为 UTC 的 yyyy-MM-dd HH:mm:ss（该类的类注释写明字典序即时间序）。
--   先 STR_TO_DATE 清洗（非法值→NULL 并告警，不中断），再 MODIFY。
--
-- 兼容性：MySQL 8.0。
--   - Shipment.eta 实体同步改 LocalDate（Jackson 序列化仍 yyyy-MM-dd，前端不变）；
--   - TrackingEvent.eventTime 实体**刻意保持 String**：轨迹游标协议
--     （LogisticsServiceImpl 的 "V|<eventTime>|<id>" 编码与 gt/eq 比较）、
--     去重指纹、TrackingIngestServiceImplTest 的断言都建立在字符串形态上；
--     JDBC 对 DATETIME 的 getString 返回同为 yyyy-MM-dd HH:mm:ss，零改动兼容。
UPDATE amz_shipment
   SET eta = DATE_FORMAT(STR_TO_DATE(eta, '%Y-%m-%d'), '%Y-%m-%d')
 WHERE eta IS NOT NULL;

ALTER TABLE amz_shipment
    MODIFY COLUMN eta DATE DEFAULT NULL COMMENT '预计到港日期';

ALTER TABLE amz_tracking_event
    MODIFY COLUMN event_time DATETIME DEFAULT NULL COMMENT '事件发生时间（UTC）';
