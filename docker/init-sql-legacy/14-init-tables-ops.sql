-- ============================================
-- Amazon ERP 运营工具模块建表脚本
-- 数据库: amz_ops
-- 含：差评监控、跟卖监控、关键词排名追踪
-- ============================================

CREATE DATABASE IF NOT EXISTS amz_ops DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE amz_ops;

-- 差评监控告警表
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

-- 跟卖监控告警表
CREATE TABLE IF NOT EXISTS amz_hijack_alert (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    asin VARCHAR(15) NOT NULL COMMENT '被跟卖的商品 ASIN',
    hijacker_seller_id VARCHAR(64) DEFAULT NULL COMMENT '跟卖卖家 ID',
    hijacker_name VARCHAR(200) DEFAULT NULL COMMENT '跟卖卖家名称',
    hijack_price DECIMAL(10,2) DEFAULT NULL COMMENT '跟卖价格',
    buy_box_taken TINYINT DEFAULT 0 COMMENT '是否抢走购物车：1=是 0=否',
    status VARCHAR(10) DEFAULT 'NEW' COMMENT 'NEW/HANDLED/IGNORED',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop (shop_id),
    INDEX idx_status (status),
    INDEX idx_asin (asin)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跟卖监控告警表';

-- 关键词排名追踪表
CREATE TABLE IF NOT EXISTS amz_keyword_rank (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    keyword VARCHAR(200) NOT NULL COMMENT '追踪关键词',
    asin VARCHAR(15) NOT NULL COMMENT '商品 ASIN',
    rank INT DEFAULT NULL COMMENT '自然排名位置（1=首页第1名）',
    marketplace VARCHAR(5) DEFAULT 'US' COMMENT '搜索站点',
    capture_time VARCHAR(25) DEFAULT NULL COMMENT '抓取时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop (shop_id),
    INDEX idx_keyword (keyword),
    INDEX idx_asin (asin),
    INDEX idx_capture (capture_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关键词排名追踪表';
