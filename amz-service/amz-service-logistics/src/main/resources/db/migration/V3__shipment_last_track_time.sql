-- Flyway Migration V3: 货件最近轨迹取数时间（支撑定时自动同步）
-- Service: amz-service-logistics
--
-- 背景：定时同步需要「优先处理最久未更新的货件」。若只按 id 排序并限制单轮处理量，
--   每一轮都会固定命中前 N 条，其余货件永远轮不到——即调度饥饿。
--   本列记录「最近一次成功取数时间」，使轮换（ORDER BY last_track_time ASC）成为可能。
--
-- 为什么不能复用 update_time：
--   轨迹落库只在货件整体状态发生变化时才写 amz_shipment。仅新增轨迹点、状态未变时
--   货件行不变，update_time 也停留在状态变更那一刻，无法反映轨迹数据的新鲜度。
--
-- 同时用于看板展示「该货件的数据最后更新于何时」——这是判断自动化是否在正常工作的
--   唯一信号，避免使用者因「看起来没动」而对自动同步失去信任、退回人工检索。

SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'amz_shipment'
        AND COLUMN_NAME = 'last_track_time') = 0,
    'ALTER TABLE amz_shipment
        ADD COLUMN last_track_time DATETIME DEFAULT NULL
            COMMENT ''最近一次轨迹取数时间（导入/API 均写入，含查询成功但无新轨迹）''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 轮换查询为「非终态 + 按 last_track_time 升序」，走该索引避免全表扫描后排序
SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'amz_shipment'
        AND INDEX_NAME = 'idx_shipment_last_track') = 0,
    'CREATE INDEX idx_shipment_last_track ON amz_shipment (last_track_time)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
