-- ============================================
-- Amazon ERP 广告管理模块建表脚本
-- 数据库: amz_ad
-- ============================================

CREATE DATABASE IF NOT EXISTS amz_ad DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE amz_ad;

-- 广告活动表
CREATE TABLE IF NOT EXISTS amz_ad_campaign (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    campaign_id VARCHAR(64) NOT NULL COMMENT 'Amazon 广告活动 ID',
    shop_id BIGINT NOT NULL COMMENT '所属店铺 ID',
    name VARCHAR(200) NOT NULL COMMENT '活动名称',
    campaign_type VARCHAR(10) NOT NULL COMMENT 'SP/SB/SD',
    state VARCHAR(10) DEFAULT 'ENABLED' COMMENT 'ENABLED/PAUSED/ARCHIVED',
    daily_budget DECIMAL(10,2) DEFAULT 0 COMMENT '日预算（美元）',
    bidding_strategy VARCHAR(30) DEFAULT 'LEGACY_FOR_SALES',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_campaign (shop_id, campaign_id),
    INDEX idx_shop (shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='广告活动表';

-- 广告关键词表
CREATE TABLE IF NOT EXISTS amz_ad_keyword (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    campaign_id VARCHAR(64) NOT NULL COMMENT '所属广告活动 ID',
    shop_id BIGINT NOT NULL,
    keyword VARCHAR(200) NOT NULL COMMENT '关键词文本',
    match_type VARCHAR(10) DEFAULT 'EXACT' COMMENT 'EXACT/PHRASE/BROAD',
    bid DECIMAL(10,2) DEFAULT 0 COMMENT '当前竞价（美元）',
    state VARCHAR(10) DEFAULT 'ENABLED',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_campaign (campaign_id),
    INDEX idx_shop (shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='广告关键词表';

-- 分时调价规则表
CREATE TABLE IF NOT EXISTS amz_ad_bid_schedule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    campaign_id VARCHAR(64) DEFAULT NULL COMMENT '活动 ID，null 表示作用于该店铺所有活动',
    start_hour TINYINT NOT NULL COMMENT '生效起始小时 0-23',
    end_hour TINYINT NOT NULL COMMENT '生效结束小时 0-23（含）',
    multiplier DECIMAL(5,2) NOT NULL DEFAULT 1.00 COMMENT '竞价倍率：0.7=降30%，1.5=加50%',
    enabled TINYINT DEFAULT 1 COMMENT '1=启用 0=停用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop_enabled (shop_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分时调价规则表';

-- 预置示例分时调价规则（shop_id=1）
INSERT IGNORE INTO amz_ad_bid_schedule (shop_id, campaign_id, start_hour, end_hour, multiplier, enabled) VALUES
(1, NULL, 0,  6, 0.70, 1),   -- 凌晨低转化时段：竞价降 30%
(1, NULL, 20, 23, 1.50, 1),  -- 晚高峰时段：竞价加 50%
(1, NULL, 7,  9, 1.20, 1);   -- 早高峰时段：竞价加 20%
