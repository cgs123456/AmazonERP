-- ============================================
-- Amazon ERP 选品模块建表脚本
-- 数据库: amz_ops
-- 含：选品分析结果、关键词调研
-- ============================================

USE amz_ops;

-- 选品分析结果表
CREATE TABLE IF NOT EXISTS amz_selection_opportunity (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    asin VARCHAR(20) NOT NULL,
    title VARCHAR(500),
    category VARCHAR(200),
    marketplace VARCHAR(10) DEFAULT 'US',
    -- 市场指标
    avg_price DECIMAL(10,2) COMMENT '平均售价',
    avg_reviews INT COMMENT '平均评论数',
    avg_rating DECIMAL(2,1) COMMENT '平均评分',
    search_volume INT COMMENT '月搜索量',
    -- 竞争指标
    competitor_count INT COMMENT '竞品数量',
    review_barrier VARCHAR(20) COMMENT '评论壁垒 LOW/MEDIUM/HIGH',
    opportunity_score DECIMAL(3,1) COMMENT '机会评分 0-100',
    -- 趋势
    trend_30d VARCHAR(20) COMMENT 'UP/FLAT/DOWN',
    trend_90d VARCHAR(20),
    -- AI 建议
    ai_summary TEXT COMMENT 'AI 分析摘要',
    ai_suggestion TEXT COMMENT 'AI 建议',
    status VARCHAR(20) DEFAULT 'ANALYZED',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop_asin (shop_id, asin),
    INDEX idx_opportunity_score (opportunity_score DESC),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='选品分析结果表';

-- 关键词调研表
CREATE TABLE IF NOT EXISTS amz_keyword_research (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    keyword VARCHAR(200) NOT NULL,
    marketplace VARCHAR(10) DEFAULT 'US',
    search_volume INT COMMENT '月搜索量',
    click_share DECIMAL(5,2) COMMENT '点击份额%',
    conversion_share DECIMAL(5,2) COMMENT '转化份额%',
    top_asin VARCHAR(20) COMMENT 'Top 3 ASIN',
    difficulty_score DECIMAL(3,1) COMMENT '竞争难度 0-100',
    recommended_bid DECIMAL(8,2) COMMENT '建议竞价',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop_keyword (shop_id, keyword),
    INDEX idx_search_volume (search_volume DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关键词调研表';
