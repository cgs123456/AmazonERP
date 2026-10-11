-- Flyway Migration V12: 店铺绩效原始留痕（P3.1）
-- Service: amz-service-spapi
--
-- Why：
--   店铺绩效（订单缺陷率 ODR/迟发率等）是 wimoor performance 域与商业 ERP 的标配，
--   本仓整块缺失。SP-API 的 GET_V2_SELLER_PERFORMANCE_REPORT 是官方存在的报表类型，
--   但本仓官方快照里**没有它的列结构**（reportType 无 enum、无字段定义）——
--   诚实边界下不编造字段级解析器，因此本轮做「原始留痕 + 查询」：
--   报表原文完整入库（raw_content），字段级解析留到取得真实报表样本后补
--   （届时新增列 + 迁移，原文不丢）。
--
-- 兼容性：仅新增表。
-- 回滚：DROP TABLE amz_shop_performance;

CREATE TABLE IF NOT EXISTS amz_shop_performance (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '所属店铺 ID',
    marketplace_id VARCHAR(20) DEFAULT NULL COMMENT '站点',
    report_type VARCHAR(80) NOT NULL DEFAULT 'GET_V2_SELLER_PERFORMANCE_REPORT'
        COMMENT '官方报表类型',
    report_id VARCHAR(64) DEFAULT NULL COMMENT 'SP-API reportId（追溯）',
    raw_content LONGTEXT COMMENT '报表原文（未解析，等真实样本后补字段级解析）',
    content_length INT DEFAULT 0 COMMENT '原文长度（列表页展示用）',
    status VARCHAR(20) NOT NULL DEFAULT 'SYNCED' COMMENT '状态：SYNCED/EMPTY',
    operator_id BIGINT DEFAULT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop_time (shop_id, create_time),
    INDEX idx_shop_type (shop_id, report_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='店铺绩效原始留痕';
