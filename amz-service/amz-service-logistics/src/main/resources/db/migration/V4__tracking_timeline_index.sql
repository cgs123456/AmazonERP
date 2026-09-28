-- Flyway Migration V4: 轨迹时间线复合索引（支撑按货件稳定翻页）
-- Service: amz-service-logistics
--
-- 查询形态：
--   WHERE shipment_id = ?
--   ORDER BY (event_time IS NULL) ASC, event_time ASC, id ASC
--   LIMIT ?+1
--
-- 旧索引只有 shipment_id 单列，同一货件的轨迹增多后会先取全量再排序，
-- 翻到后页时成本线性增长。复合索引至少把租户内单货件的候选行收敛到
-- (shipment_id, event_time) 连续区间，并为同时间点按 id 稳定排序提供覆盖。
--
-- 注意：
--   1. event_time 当前是 VARCHAR(25)，排序依赖写入值采用可字典序比较的
--      ISO-8601 形式；若后续引入非 ISO 格式，需先迁移为 DATETIME。
--   2. MySQL 8 不支持 CREATE INDEX IF NOT EXISTS，使用 information_schema
--      守卫保证重复执行安全。
--   3. 生产大表上线前需先在只读副本执行 EXPLAIN，并在低峰期评估在线 DDL；
--      本迁移不在业务事务内执行数据回填，只创建索引。

SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'amz_tracking_event'
        AND INDEX_NAME = 'idx_shipment_event_time_id') = 0,
    'CREATE INDEX idx_shipment_event_time_id ON amz_tracking_event (shipment_id, event_time, id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;