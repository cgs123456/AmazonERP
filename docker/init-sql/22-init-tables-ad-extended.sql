-- ============================================
-- Amazon ERP 广告扩展模块建表脚本
-- 数据库: amz_ad
-- 支持 SP/SB/SD/DSP 全广告类型
-- ============================================

CREATE DATABASE IF NOT EXISTS amz_ad DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE amz_ad;

-- 广告活动表（扩展支持 SB/SD/DSP）
CREATE TABLE IF NOT EXISTS amz_ad_campaign_ext (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    campaign_id VARCHAR(50) NOT NULL,
    campaign_name VARCHAR(200) NOT NULL,
    ad_type VARCHAR(20) NOT NULL COMMENT 'SP/SB/SD/DSP',
    campaign_type VARCHAR(20) COMMENT 'SPONSORED_PRODUCTS/SPONSORED_BRANDS/SPONSORED_DISPLAY/DEMAND_SIDE_PLATFORM',
    budget DECIMAL(10,2),
    budget_type VARCHAR(20) COMMENT 'DAILY/LIFETIME',
    bidding_strategy VARCHAR(30) COMMENT 'LEGACY_SUGGESTED_FOR_SALES/AGGRESSIVE/DOWN_ONLY/UP_AND_DOWN',
    status VARCHAR(20) DEFAULT 'ENABLED',
    start_date DATE,
    end_date DATE,
    -- 性能指标
    impressions BIGINT DEFAULT 0,
    clicks BIGINT DEFAULT 0,
    spend DECIMAL(10,2) DEFAULT 0,
    sales DECIMAL(10,2) DEFAULT 0,
    orders INT DEFAULT 0,
    acos DECIMAL(5,2) DEFAULT 0,
    roas DECIMAL(5,2) DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop_type (shop_id, ad_type),
    INDEX idx_campaign_id (campaign_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='广告活动扩展表(支持SB/SD/DSP)';

-- SB 广告素材表
CREATE TABLE IF NOT EXISTS amz_ad_creative (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    campaign_id VARCHAR(50) NOT NULL,
    creative_type VARCHAR(20) COMMENT 'Video/IMAGE/STORE_SPOTLIGHT/CUSTOM_HEADLINE',
    headline VARCHAR(200),
    brand_name VARCHAR(100),
    logo_url VARCHAR(500),
    video_url VARCHAR(500),
    landing_page_url VARCHAR(500),
    asin VARCHAR(20) COMMENT '关联 ASIN',
    status VARCHAR(20) DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_campaign (campaign_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SB 广告素材表';

-- SD 受众定向表
CREATE TABLE IF NOT EXISTS amz_ad_targeting (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    campaign_id VARCHAR(50) NOT NULL,
    targeting_type VARCHAR(30) COMMENT 'CONTEXTUAL/REMARKETING/AUDIENCE/LOOKALIKE',
    targeting_value VARCHAR(200) COMMENT 'ASIN/CATEGORY/INTEREST',
    bid DECIMAL(8,2),
    impressions BIGINT DEFAULT 0,
    clicks BIGINT DEFAULT 0,
    spend DECIMAL(10,2) DEFAULT 0,
    sales DECIMAL(10,2) DEFAULT 0,
    INDEX idx_campaign_type (campaign_id, targeting_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SD 受众定向表';
