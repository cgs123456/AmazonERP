-- ============================================================
-- P1-1 Listing 健康监控（product 模块升级）
-- 表：Listing健康度 / 变更日志 / 关键词排名 / 竞品监控 / BuyBox
-- ============================================================

-- Listing 健康度
CREATE TABLE IF NOT EXISTS amz_listing_health (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '店铺ID',
    asin VARCHAR(16) NOT NULL COMMENT 'ASIN',
    sku VARCHAR(64) COMMENT '卖家SKU',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/INACTIVE/SUPPRESSED',
    title_ok TINYINT(1) DEFAULT 1 COMMENT '标题完整',
    bullet_points_ok TINYINT(1) DEFAULT 1 COMMENT '五点描述完整',
    description_ok TINYINT(1) DEFAULT 1 COMMENT '描述完整',
    aplus_ok TINYINT(1) DEFAULT 1 COMMENT 'A+内容正常',
    images_ok TINYINT(1) DEFAULT 1 COMMENT '图片合规（主图白底/≥7张）',
    search_terms_ok TINYINT(1) DEFAULT 1 COMMENT '后台搜索词完整',
    suppressed_reason VARCHAR(128) COMMENT '下架原因',
    health_score INT DEFAULT 100 COMMENT '健康分0-100',
    severity VARCHAR(10) DEFAULT 'OK' COMMENT '严重度：OK/WARNING/CRITICAL',
    check_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '检查时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop (shop_id),
    INDEX idx_asin (asin),
    UNIQUE KEY uk_shop_asin (shop_id, asin)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Listing健康度';

-- Listing 变更日志
CREATE TABLE IF NOT EXISTS amz_listing_change_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '店铺ID',
    asin VARCHAR(16) NOT NULL COMMENT 'ASIN',
    sku VARCHAR(64) COMMENT 'SKU',
    field_name VARCHAR(64) NOT NULL COMMENT '变更字段（title/bullet_points/price/images/search_terms等）',
    old_value TEXT COMMENT '旧值',
    new_value TEXT COMMENT '新值',
    change_source VARCHAR(20) DEFAULT 'MANUAL' COMMENT '变更来源：MANUAL/AUTO/AMAZON/FEED',
    operator VARCHAR(64) COMMENT '操作人',
    change_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop_asin (shop_id, asin),
    INDEX idx_change_time (change_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Listing变更日志';

-- 关键词排名追踪
CREATE TABLE IF NOT EXISTS amz_keyword_ranking (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '店铺ID',
    asin VARCHAR(16) NOT NULL COMMENT 'ASIN',
    keyword VARCHAR(256) NOT NULL COMMENT '关键词',
    organic_rank INT COMMENT '自然排名（0=未上榜）',
    ad_rank INT COMMENT '广告排名（0=未投放）',
    search_volume INT DEFAULT 0 COMMENT '月搜索量',
    rank_date DATE NOT NULL COMMENT '排名日期',
    marketplace_id VARCHAR(16) COMMENT '站点',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop_asin (shop_id, asin),
    INDEX idx_keyword (keyword),
    INDEX idx_rank_date (rank_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='关键词排名追踪';

-- 竞品监控
CREATE TABLE IF NOT EXISTS amz_competitor_monitor (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '店铺ID',
    competitor_asin VARCHAR(16) NOT NULL COMMENT '竞品ASIN',
    competitor_title VARCHAR(512) COMMENT '竞品标题',
    price DECIMAL(10,2) COMMENT '竞品售价',
    price_change DECIMAL(10,2) COMMENT '价格变动',
    bs_rank INT COMMENT 'Best Seller排名',
    review_count INT COMMENT '评论数',
    review_rating DECIMAL(2,1) COMMENT '评论评分',
    rating_change DECIMAL(3,2) COMMENT '评分变化',
    in_stock TINYINT(1) DEFAULT 1 COMMENT '是否有货',
    has_coupon TINYINT(1) DEFAULT 0 COMMENT '是否有Coupon',
    has_deal TINYINT(1) DEFAULT 0 COMMENT '是否有Deal',
    snapshot_date DATE NOT NULL COMMENT '快照日期',
    marketplace_id VARCHAR(16) COMMENT '站点',
    notes TEXT COMMENT '备注',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop (shop_id),
    INDEX idx_competitor_asin (competitor_asin),
    INDEX idx_snapshot (snapshot_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='竞品监控';

-- Buy Box 状态
CREATE TABLE IF NOT EXISTS amz_buy_box (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '店铺ID',
    asin VARCHAR(16) NOT NULL COMMENT 'ASIN',
    seller_id VARCHAR(32) COMMENT '占据BuyBox的卖家ID',
    is_self TINYINT(1) DEFAULT 0 COMMENT '是否自有卖家占BuyBox',
    buybox_price DECIMAL(10,2) COMMENT 'BuyBox价格',
    our_price DECIMAL(10,2) COMMENT '我方价格',
    price_gap DECIMAL(10,2) COMMENT '价格差',
    fulfillment_type VARCHAR(10) COMMENT '配送类型：FBA/FBM',
    ownership_pct DECIMAL(5,2) COMMENT 'BuyBox占有率(小时%)',
    snapshot_time DATETIME NOT NULL COMMENT '快照时间',
    marketplace_id VARCHAR(16) COMMENT '站点',
    INDEX idx_shop_asin (shop_id, asin),
    INDEX idx_snapshot_time (snapshot_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='BuyBox状态监控';
