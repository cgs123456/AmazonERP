-- ============================================================
-- 33-init-tables-p2-ai-tools.sql
-- Phase 3 P2-4：AI Agent 工具扩展 14→28
-- 新增 3 张表支持新工具
-- ============================================================

-- ---------------------------------------
-- Listing SEO 优化建议记录
-- ---------------------------------------
CREATE TABLE IF NOT EXISTS amz_listing_seo (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id         BIGINT          NOT NULL COMMENT '店铺 ID',
    asin            VARCHAR(20)     NOT NULL COMMENT 'ASIN',
    check_time      DATETIME        NOT NULL COMMENT '检查时间',
    title_score     TINYINT         COMMENT '标题分 0-100',
    bullet_score    TINYINT         COMMENT '五点分 0-100',
    description_score TINYINT       COMMENT '描述分 0-100',
    keyword_score   TINYINT         COMMENT '搜索词分 0-100',
    image_score     TINYINT         COMMENT '图片分 0-100',
    overall_score   TINYINT         COMMENT '综合分 0-100',
    suggestions     TEXT            COMMENT '优化建议 JSON',
    ai_analysis     TEXT            COMMENT 'AI 分析原文',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_asin_time (asin, check_time),
    INDEX idx_shop (shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Listing SEO 优化记录表';

-- ---------------------------------------
-- 物流成本预估记录
-- ---------------------------------------
CREATE TABLE IF NOT EXISTS amz_logistics_quote (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id         BIGINT          NOT NULL COMMENT '店铺 ID',
    origin          VARCHAR(100)    NOT NULL COMMENT '起运地',
    destination     VARCHAR(100)    NOT NULL COMMENT '目的地',
    transport_mode  VARCHAR(20)     NOT NULL COMMENT '运输方式：SEA/AIR/EXPRESS',
    weight_kg       DECIMAL(10,2)   NOT NULL COMMENT '重量 kg',
    volume_cbm      DECIMAL(10,3)   NOT NULL DEFAULT 0 COMMENT '体积 CBM',
    estimated_cost  DECIMAL(12,2)   NOT NULL COMMENT '预估费用 CNY',
    estimated_days  INT             NOT NULL COMMENT '预估天数',
    currency        VARCHAR(10)     DEFAULT 'CNY',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop_mode (shop_id, transport_mode)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='物流成本预估记录表';

-- ---------------------------------------
-- 自定义报表模板
-- ---------------------------------------
CREATE TABLE IF NOT EXISTS amz_report_template (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id         BIGINT          NOT NULL COMMENT '店铺 ID',
    name            VARCHAR(100)    NOT NULL COMMENT '模板名称',
    report_type     VARCHAR(50)     NOT NULL COMMENT '报表类型：SALES/INVENTORY/PROFIT/AD/COMPOSITE',
    dimensions      TEXT            NOT NULL COMMENT '维度配置 JSON',
    metrics         TEXT            NOT NULL COMMENT '指标配置 JSON',
    filters         TEXT            COMMENT '筛选条件 JSON',
    schedule_cron   VARCHAR(100)    COMMENT '定时推送 cron 表达式',
    recipients      TEXT            COMMENT '推送接收人（逗号分隔）',
    enabled         TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '是否启用',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop_type (shop_id, report_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='自定义报表模板表';

-- 预置示例模板
INSERT IGNORE INTO amz_report_template (id, shop_id, name, report_type, dimensions, metrics, filters, enabled)
VALUES
(1, 1, '每日销售概览', 'SALES', '["date","asin","marketplace"]', '["orderCount","totalSales","grossProfit","margin"]', '{"days":7}', 1),
(2, 1, '库存健康周报', 'INVENTORY', '["sku","warehouse","healthStatus"]', '["availableQty","dos","daysInStock","suggestedReplenish"]', '{"healthStatus":["URGENT","STOCKOUT"]}', 1),
(3, 1, '广告 ACoS 周报', 'AD', '["asin","campaign","searchTerm"]', '["impressions","clicks","spend","sales","acos","roas"]', '{"acosGte":0.3}', 1);
