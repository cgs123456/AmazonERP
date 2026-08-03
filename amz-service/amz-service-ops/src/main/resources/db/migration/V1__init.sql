-- Flyway Migration V1: amz_ops database initialization
-- Service: amz-service-ops
-- Source: docker/init-sql/14, 20

-- ============================================
-- Operations Tools (from 14-init-tables-ops.sql)
-- ============================================
CREATE TABLE IF NOT EXISTS amz_negative_review_alert (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    asin VARCHAR(15) NOT NULL COMMENT '商品 ASIN',
    review_id VARCHAR(32) DEFAULT NULL COMMENT 'Amazon 评论 ID',
    rating TINYINT DEFAULT NULL COMMENT '评分 1-5',
    title VARCHAR(500) DEFAULT NULL,
    content TEXT,
    reviewer VARCHAR(100) DEFAULT NULL,
    status VARCHAR(10) DEFAULT 'NEW' COMMENT 'NEW/HANDLED/IGNORED',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop (shop_id),
    INDEX idx_status (status),
    INDEX idx_asin (asin)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='差评监控告警表';

CREATE TABLE IF NOT EXISTS amz_hijack_alert (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    asin VARCHAR(15) NOT NULL COMMENT '被跟卖的商品 ASIN',
    hijacker_seller_id VARCHAR(64) DEFAULT NULL COMMENT '跟卖卖家 ID',
    hijacker_name VARCHAR(200) DEFAULT NULL COMMENT '跟卖卖家名称',
    hijack_price DECIMAL(10,2) DEFAULT NULL COMMENT '跟卖价格',
    buy_box_taken TINYINT DEFAULT 0 COMMENT '是否抢走购物车',
    status VARCHAR(10) DEFAULT 'NEW' COMMENT 'NEW/HANDLED/IGNORED',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop (shop_id),
    INDEX idx_status (status),
    INDEX idx_asin (asin)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跟卖监控告警表';

CREATE TABLE IF NOT EXISTS amz_keyword_rank (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    keyword VARCHAR(200) NOT NULL COMMENT '追踪关键词',
    asin VARCHAR(15) NOT NULL COMMENT '商品 ASIN',
    rank INT DEFAULT NULL COMMENT '自然排名位置',
    marketplace VARCHAR(5) DEFAULT 'US' COMMENT '搜索站点',
    capture_time VARCHAR(25) DEFAULT NULL COMMENT '抓取时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop (shop_id),
    INDEX idx_keyword (keyword),
    INDEX idx_asin (asin),
    INDEX idx_capture (capture_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关键词排名追踪表';

-- ============================================
-- Product Selection (from 20-init-tables-product-selection.sql)
-- ============================================
CREATE TABLE IF NOT EXISTS amz_selection_opportunity (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    asin VARCHAR(20) NOT NULL,
    title VARCHAR(500),
    category VARCHAR(200),
    marketplace VARCHAR(10) DEFAULT 'US',
    avg_price DECIMAL(10,2) COMMENT '平均售价',
    avg_reviews INT COMMENT '平均评论数',
    avg_rating DECIMAL(2,1) COMMENT '平均评分',
    search_volume INT COMMENT '月搜索量',
    competitor_count INT COMMENT '竞品数量',
    review_barrier VARCHAR(20) COMMENT '评论壁垒',
    opportunity_score DECIMAL(3,1) COMMENT '机会评分 0-100',
    trend_30d VARCHAR(20) COMMENT 'UP/FLAT/DOWN',
    trend_90d VARCHAR(20),
    ai_summary TEXT COMMENT 'AI 分析摘要',
    ai_suggestion TEXT COMMENT 'AI 建议',
    status VARCHAR(20) DEFAULT 'ANALYZED',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop_asin (shop_id, asin),
    INDEX idx_opportunity_score (opportunity_score DESC),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='选品分析结果表';

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
