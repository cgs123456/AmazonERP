-- Flyway Migration V13: Listing 调价规则（P3.2）
-- Service: amz-service-spapi
--
-- Why（对标商业 ERP「Listing 自动调价」）：
--   广告分时调价（ad BidSchedule）只改广告出价；Listing 价格调整此前只能手工经
--   listingsItems PATCH 操作执行。本表落规则，执行经统一 operation 执行器下发
--   （listingsItems.patchListingsItem，官方快照 listingsItems_2021-08-01.json）。
--   本轮只做「手动触发执行」（POST execute/{shopId}）——定时自动执行等联调后开。
--
-- 兼容性：仅新增表。
-- 回滚：DROP TABLE amz_listing_price_rule;

CREATE TABLE IF NOT EXISTS amz_listing_price_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '所属店铺 ID',
    marketplace_id VARCHAR(20) NOT NULL COMMENT '站点',
    sku VARCHAR(100) NOT NULL COMMENT '卖家 SKU（listingsItems 定位键）',
    rule_type VARCHAR(30) NOT NULL DEFAULT 'MANUAL_SCHEDULE'
        COMMENT '规则类型：MANUAL_SCHEDULE（预留 TRACK_COMPETITOR 等）',
    target_price DECIMAL(14,2) DEFAULT NULL COMMENT '目标价（与 adjust_pct 二选一）',
    adjust_pct DECIMAL(6,4) DEFAULT NULL COMMENT '调整幅度（0.05 = -5%，负数降价）',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    last_executed_at DATETIME DEFAULT NULL COMMENT '最近一次执行时间',
    last_result VARCHAR(300) DEFAULT NULL COMMENT '最近一次执行结果摘要',
    remark VARCHAR(300) DEFAULT NULL,
    operator_id BIGINT DEFAULT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_shop_marketplace_sku (shop_id, marketplace_id, sku),
    INDEX idx_shop_enabled (shop_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Listing 调价规则';
