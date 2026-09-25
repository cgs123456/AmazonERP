-- ============================================
-- Amazon ERP 广告管理模块升级 SQL
-- 数据库: amz_ad
-- 升级内容：搜索词分析/出单词库/广告报表/自动规则/ASIN反查
-- ============================================

USE amz_ad;

-- ============================================
-- 1. 搜索词报表表（Search Term Report）
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

-- ============================================
-- 2. 出单词库表
-- ============================================
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
    is_added_to_keyword TINYINT DEFAULT 0 COMMENT '是否已加入精准词(1=是)',
    status VARCHAR(20) DEFAULT 'ACTIVE' COMMENT 'ACTIVE/ARCHIVED',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop (shop_id),
    INDEX idx_asin (asin),
    INDEX idx_search_term (search_term(100))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出单词库';

-- ============================================
-- 3. 广告日报表表
-- ============================================
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

-- ============================================
-- 4. 广告自动规则表
-- ============================================
CREATE TABLE IF NOT EXISTS amz_ad_auto_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    rule_name VARCHAR(100) NOT NULL COMMENT '规则名称',
    rule_type VARCHAR(30) NOT NULL COMMENT 'KEYWORD_BID/CAMPAIGN_BUDGET/KEYWORD_PAUSE/NEGATIVE_KEYWORD',
    scope VARCHAR(20) DEFAULT 'KEYWORD' COMMENT 'KEYWORD/CAMPAIGN/ASIN',
    scope_value VARCHAR(200) COMMENT '作用范围值(campaignId/keyword/asin)',
    condition_field VARCHAR(20) NOT NULL COMMENT 'ACOS/CR/CTR/CPC/SPEND/SALES/IMPRESSIONS',
    condition_op VARCHAR(10) NOT NULL COMMENT 'GT/GTE/LT/LTE/EQ/BETWEEN',
    condition_value DECIMAL(10,2) NOT NULL COMMENT '阈值',
    condition_value2 DECIMAL(10,2) COMMENT 'BETWEEN 上界',
    action VARCHAR(30) NOT NULL COMMENT 'INCREASE_BID/DECREASE_BID/PAUSE/ENABLE/ADD_NEGATIVE/INCREASE_BUDGET/DECREASE_BUDGET',
    action_value DECIMAL(10,2) COMMENT '动作参数(如降价比例%)',
    time_window INT DEFAULT 7 COMMENT '统计时间窗口(天)',
    priority INT DEFAULT 0 COMMENT '优先级(数字越大越优先)',
    enabled TINYINT DEFAULT 1,
    last_executed DATETIME COMMENT '上次执行时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop_enabled (shop_id, enabled),
    INDEX idx_rule_type (rule_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='广告自动规则表';

-- ============================================
-- 5. ASIN 反查表（关键词排名追踪）
-- ============================================
CREATE TABLE IF NOT EXISTS amz_ad_asin_keyword (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    asin VARCHAR(20) NOT NULL,
    keyword VARCHAR(200) NOT NULL COMMENT '关键词',
    organic_rank INT COMMENT '自然排名',
    ad_rank INT COMMENT '广告排名',
    search_volume INT DEFAULT 0 COMMENT '搜索量',
    relevance_score DECIMAL(3,1) DEFAULT 0 COMMENT '相关性评分(0-5)',
    is_indexed TINYINT DEFAULT 1 COMMENT '是否被索引(1=是)',
    last_checked DATE COMMENT '最近检查日期',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop_asin (shop_id, asin),
    INDEX idx_keyword (keyword(100))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='ASIN关键词反查表';

-- ============================================
-- 6. 广告投放位置报表
-- ============================================
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

-- ============================================
-- 示例数据
-- ============================================
INSERT IGNORE INTO amz_ad_auto_rule (shop_id, rule_name, rule_type, scope, scope_value, condition_field, condition_op, condition_value, action, action_value, time_window, priority, enabled)
VALUES
(1, '高ACoS自动暂停', 'KEYWORD_PAUSE', 'KEYWORD', NULL, 'ACOS', 'GT', 50.00, 'PAUSE', NULL, 14, 10, 1),
(1, '高ACoS降价', 'KEYWORD_BID', 'KEYWORD', NULL, 'ACOS', 'GT', 35.00, 'DECREASE_BID', 15.00, 7, 8, 1),
(1, '低ACoS加价', 'KEYWORD_BID', 'KEYWORD', NULL, 'ACOS', 'LT', 15.00, 'INCREASE_BID', 25.00, 7, 8, 1),
(1, '高曝光零点击否词', 'NEGATIVE_KEYWORD', 'KEYWORD', NULL, 'CTR', 'LT', 0.20, 'ADD_NEGATIVE', NULL, 7, 5, 1);
