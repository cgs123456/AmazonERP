-- Flyway Migration V1: amz_ad database initialization
-- Service: amz-service-ad
-- Source: docker/init-sql/10, 22, 24

-- ============================================
-- Ad Campaign Management (from 10-init-tables-ad.sql)
-- ============================================
CREATE TABLE IF NOT EXISTS amz_ad_campaign (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    campaign_id VARCHAR(64) NOT NULL COMMENT 'Amazon 广告活动 ID',
    shop_id BIGINT NOT NULL COMMENT '所属店铺 ID',
    name VARCHAR(200) NOT NULL COMMENT '活动名称',
    campaign_type VARCHAR(10) NOT NULL COMMENT 'SP/SB/SD',
    state VARCHAR(10) DEFAULT 'ENABLED' COMMENT 'ENABLED/PAUSED/ARCHIVED',
    daily_budget DECIMAL(10,2) DEFAULT 0 COMMENT '日预算',
    bidding_strategy VARCHAR(30) DEFAULT 'LEGACY_FOR_SALES',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_campaign (shop_id, campaign_id),
    INDEX idx_shop (shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='广告活动表';

CREATE TABLE IF NOT EXISTS amz_ad_keyword (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    campaign_id VARCHAR(64) NOT NULL COMMENT '所属广告活动 ID',
    shop_id BIGINT NOT NULL,
    keyword VARCHAR(200) NOT NULL COMMENT '关键词文本',
    match_type VARCHAR(10) DEFAULT 'EXACT' COMMENT 'EXACT/PHRASE/BROAD',
    bid DECIMAL(10,2) DEFAULT 0 COMMENT '当前竞价',
    state VARCHAR(10) DEFAULT 'ENABLED',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_campaign (campaign_id),
    INDEX idx_shop (shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='广告关键词表';

CREATE TABLE IF NOT EXISTS amz_ad_bid_schedule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    campaign_id VARCHAR(64) DEFAULT NULL,
    start_hour TINYINT NOT NULL COMMENT '生效起始小时 0-23',
    end_hour TINYINT NOT NULL COMMENT '生效结束小时 0-23',
    multiplier DECIMAL(5,2) NOT NULL DEFAULT 1.00 COMMENT '竞价倍率',
    enabled TINYINT DEFAULT 1 COMMENT '1=启用 0=停用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop_enabled (shop_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分时调价规则表';

INSERT IGNORE INTO amz_ad_bid_schedule (shop_id, campaign_id, start_hour, end_hour, multiplier, enabled) VALUES
(1, NULL, 0,  6, 0.70, 1),
(1, NULL, 20, 23, 1.50, 1),
(1, NULL, 7,  9, 1.20, 1);

-- ============================================
-- Ad Extended (from 22-init-tables-ad-extended.sql)
-- ============================================
CREATE TABLE IF NOT EXISTS amz_ad_campaign_ext (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    campaign_id VARCHAR(50) NOT NULL,
    campaign_name VARCHAR(200) NOT NULL,
    ad_type VARCHAR(20) NOT NULL COMMENT 'SP/SB/SD/DSP',
    campaign_type VARCHAR(20) COMMENT '赞助类型',
    budget DECIMAL(10,2),
    budget_type VARCHAR(20) COMMENT 'DAILY/LIFETIME',
    bidding_strategy VARCHAR(30) COMMENT '竞价策略',
    status VARCHAR(20) DEFAULT 'ENABLED',
    start_date DATE,
    end_date DATE,
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='广告活动扩展表';

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

-- ============================================
-- Ad Upgrade (from 24-init-tables-ad-upgrade.sql)
-- ============================================
CREATE TABLE IF NOT EXISTS amz_ad_search_term (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    campaign_id VARCHAR(50) NOT NULL,
    keyword_id VARCHAR(50) COMMENT '关联关键词 ID',
    search_term VARCHAR(500) NOT NULL COMMENT '买家实际搜索词',
    match_type VARCHAR(10) COMMENT 'EXACT/PHRASE/BROAD',
    impressions BIGINT DEFAULT 0,
    clicks BIGINT DEFAULT 0,
    cost DECIMAL(10,2) DEFAULT 0,
    sales DECIMAL(10,2) DEFAULT 0,
    orders INT DEFAULT 0,
    acos DECIMAL(5,2) DEFAULT 0 COMMENT 'ACoS(%)',
    cr DECIMAL(5,2) DEFAULT 0 COMMENT 'CR(%)',
    ctr DECIMAL(5,2) DEFAULT 0 COMMENT 'CTR(%)',
    cpc DECIMAL(8,2) DEFAULT 0 COMMENT 'CPC($)',
    report_date DATE NOT NULL COMMENT '报表日期',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop_date (shop_id, report_date),
    INDEX idx_campaign (campaign_id),
    INDEX idx_search_term (search_term(100))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='广告搜索词报表';

CREATE TABLE IF NOT EXISTS amz_ad_converting_terms (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    asin VARCHAR(20) COMMENT '关联 ASIN',
    search_term VARCHAR(500) NOT NULL COMMENT '出单搜索词',
    campaign_id VARCHAR(50) COMMENT '来源活动',
    total_orders INT DEFAULT 0 COMMENT '累计出单数',
    total_sales DECIMAL(10,2) DEFAULT 0 COMMENT '累计销售额',
    total_cost DECIMAL(10,2) DEFAULT 0 COMMENT '累计广告花费',
    avg_acos DECIMAL(5,2) DEFAULT 0 COMMENT '平均 ACoS(%)',
    first_seen DATE COMMENT '首次出单日期',
    last_seen DATE COMMENT '最近出单日期',
    is_added_to_keyword TINYINT DEFAULT 0 COMMENT '是否已加入精准词',
    status VARCHAR(20) DEFAULT 'ACTIVE' COMMENT 'ACTIVE/ARCHIVED',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop (shop_id),
    INDEX idx_asin (asin),
    INDEX idx_search_term (search_term(100))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出单词库';

CREATE TABLE IF NOT EXISTS amz_ad_daily_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    campaign_id VARCHAR(50) NOT NULL,
    ad_type VARCHAR(20) COMMENT 'SP/SB/SD/DSP',
    report_date DATE NOT NULL,
    impressions BIGINT DEFAULT 0,
    clicks BIGINT DEFAULT 0,
    cost DECIMAL(10,2) DEFAULT 0,
    sales DECIMAL(10,2) DEFAULT 0,
    orders INT DEFAULT 0,
    units INT DEFAULT 0 COMMENT '售出件数',
    acos DECIMAL(5,2) DEFAULT 0,
    roas DECIMAL(5,2) DEFAULT 0,
    cr DECIMAL(5,2) DEFAULT 0,
    ctr DECIMAL(5,2) DEFAULT 0,
    cpc DECIMAL(8,2) DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_shop_campaign_date (shop_id, campaign_id, report_date),
    INDEX idx_shop_date (shop_id, report_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='广告日报表';

CREATE TABLE IF NOT EXISTS amz_ad_auto_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    rule_name VARCHAR(100) NOT NULL COMMENT '规则名称',
    rule_type VARCHAR(30) NOT NULL COMMENT '规则类型',
    scope VARCHAR(20) DEFAULT 'KEYWORD' COMMENT 'KEYWORD/CAMPAIGN/ASIN',
    scope_value VARCHAR(200) COMMENT '作用范围值',
    condition_field VARCHAR(20) NOT NULL COMMENT 'ACOS/CR/CTR/CPC/SPEND/SALES/IMPRESSIONS',
    condition_op VARCHAR(10) NOT NULL COMMENT 'GT/GTE/LT/LTE/EQ/BETWEEN',
    condition_value DECIMAL(10,2) NOT NULL COMMENT '阈值',
    condition_value2 DECIMAL(10,2) COMMENT 'BETWEEN 上界',
    action VARCHAR(30) NOT NULL COMMENT '动作',
    action_value DECIMAL(10,2) COMMENT '动作参数',
    time_window INT DEFAULT 7 COMMENT '统计时间窗口(天)',
    priority INT DEFAULT 0 COMMENT '优先级',
    enabled TINYINT DEFAULT 1,
    last_executed DATETIME COMMENT '上次执行时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop_enabled (shop_id, enabled),
    INDEX idx_rule_type (rule_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='广告自动规则表';

INSERT IGNORE INTO amz_ad_auto_rule (shop_id, rule_name, rule_type, scope, scope_value, condition_field, condition_op, condition_value, action, action_value, time_window, priority, enabled) VALUES
(1, '高ACoS自动暂停', 'KEYWORD_PAUSE', 'KEYWORD', NULL, 'ACOS', 'GT', 50.00, 'PAUSE', NULL, 14, 10, 1),
(1, '高ACoS降价', 'KEYWORD_BID', 'KEYWORD', NULL, 'ACOS', 'GT', 35.00, 'DECREASE_BID', 15.00, 7, 8, 1),
(1, '低ACoS加价', 'KEYWORD_BID', 'KEYWORD', NULL, 'ACOS', 'LT', 15.00, 'INCREASE_BID', 25.00, 7, 8, 1),
(1, '高曝光零点击否词', 'NEGATIVE_KEYWORD', 'KEYWORD', NULL, 'CTR', 'LT', 0.20, 'ADD_NEGATIVE', NULL, 7, 5, 1);

CREATE TABLE IF NOT EXISTS amz_ad_asin_keyword (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    asin VARCHAR(20) NOT NULL,
    keyword VARCHAR(200) NOT NULL COMMENT '关键词',
    organic_rank INT COMMENT '自然排名',
    ad_rank INT COMMENT '广告排名',
    search_volume INT DEFAULT 0 COMMENT '搜索量',
    relevance_score DECIMAL(3,1) DEFAULT 0 COMMENT '相关性评分(0-5)',
    is_indexed TINYINT DEFAULT 1 COMMENT '是否被索引',
    last_checked DATE COMMENT '最近检查日期',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop_asin (shop_id, asin),
    INDEX idx_keyword (keyword(100))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ASIN关键词反查表';

CREATE TABLE IF NOT EXISTS amz_ad_placement_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    campaign_id VARCHAR(50) NOT NULL,
    placement VARCHAR(50) NOT NULL COMMENT 'TOP_OF_SEARCH/PRODUCT_PAGES/REST_OF_SEARCH/DETAIL_PAGE',
    report_date DATE NOT NULL,
    impressions BIGINT DEFAULT 0,
    clicks BIGINT DEFAULT 0,
    cost DECIMAL(10,2) DEFAULT 0,
    sales DECIMAL(10,2) DEFAULT 0,
    orders INT DEFAULT 0,
    bid_multiplier DECIMAL(5,2) DEFAULT 1.00 COMMENT '位置竞价倍率',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop_date (shop_id, report_date),
    INDEX idx_campaign_placement (campaign_id, placement)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='广告投放位置报表';
