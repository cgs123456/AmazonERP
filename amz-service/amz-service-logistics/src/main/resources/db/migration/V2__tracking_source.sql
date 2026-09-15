-- Flyway Migration V2: 轨迹数据来源标识（支撑「外部导入」与「第三方 API」双入口）
-- Service: amz-service-logistics
--
-- 背景：物流看板要求两种取数入口并存——
--   ① 外部爬取后经导入接口灌入（source=IMPORT）
--   ② 第三方聚合 API 主动拉取（source=API）
--   Mock 环境为 source=MOCK。记录来源与承运商原始状态文本，用于区分数据血缘、
--   排查状态映射偏差，并为「按 (货件, 状态, 时间) 幂等合并」提供依据。
--
-- 为何用守卫式 ALTER 而非直接 ADD COLUMN：
--   本项目的建列同时落在 docker/init-sql 与 Flyway 两处（与既有 items_json 一致），
--   全新容器库已由 init-sql 建好列，此时直连 ALTER 会因 duplicate column 中断迁移。
--   MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，故用 information_schema 判定后动态执行。

-- ---------- amz_tracking_event：来源与原始状态 ----------
SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'amz_tracking_event'
        AND COLUMN_NAME = 'source') = 0,
    'ALTER TABLE amz_tracking_event
        ADD COLUMN source VARCHAR(16) DEFAULT NULL COMMENT ''数据来源：MOCK/IMPORT/API'',
        ADD COLUMN raw_status VARCHAR(64) DEFAULT NULL COMMENT ''承运商原始状态文本（映射前）''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- amz_shipment：取数来源偏好 ----------
-- AUTO 表示交给调度按可用入口（API 优先、导入兜底）决定，避免逐单配置
SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'amz_shipment'
        AND COLUMN_NAME = 'data_source') = 0,
    'ALTER TABLE amz_shipment
        ADD COLUMN data_source VARCHAR(16) DEFAULT ''AUTO'' COMMENT ''取数来源偏好：IMPORT/API/AUTO''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 幂等合并依赖索引 ----------
-- 同一货件下 (event_status, event_time) 视为同一轨迹点，据此判定「已存在则跳过」
SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'amz_tracking_event'
        AND INDEX_NAME = 'idx_shipment_status_time') = 0,
    'CREATE INDEX idx_shipment_status_time ON amz_tracking_event (shipment_id, event_status, event_time)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
