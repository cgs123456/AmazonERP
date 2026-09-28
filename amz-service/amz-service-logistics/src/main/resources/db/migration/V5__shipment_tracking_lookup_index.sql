-- Flyway Migration V5: 运单号定位索引（支撑轨迹落库/自动同步）
-- Service: amz-service-logistics
--
-- 查询形态（TrackingIngestServiceImpl.resolveShipment）：
--   WHERE shop_id = ?
--     AND master_tracking_no = ?
--   ORDER BY id ASC
--   LIMIT 2
--
-- master_tracking_no 当前没有唯一约束，且 V1 只建了 idx_shop(shop_id)。
-- 没有该复合索引时，每次外部导入或调度同步都会扫描该店铺的全部货件；
-- 数据量增长后，轨迹落库延迟与数据库 CPU 会随货件数线性恶化。
--
-- 注意：这里只加查询索引，不改成 UNIQUE。历史数据可能已经存在重复运单号，
-- 直接建唯一键会导致迁移失败；业务层已对“命中多条”采取 fail-closed，
-- 重复数据治理完成后再单独评估唯一约束。

SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'amz_shipment'
        AND INDEX_NAME = 'idx_shop_master_tracking_id') = 0,
    'CREATE INDEX idx_shop_master_tracking_id ON amz_shipment (shop_id, master_tracking_no, id)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;