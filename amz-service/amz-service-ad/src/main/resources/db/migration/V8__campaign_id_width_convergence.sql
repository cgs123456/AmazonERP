-- Flyway Migration V8: campaign_id 引用键宽度收敛 + 冗余索引清理
-- Service: amz-service-ad
--
-- Why（可核证据：2026-10-05 全方位 review，数据层审查发现 6）
--   引用主键 amz_ad_campaign.campaign_id 与 amz_ad_keyword.campaign_id 是
--   VARCHAR(64)，而 6 张子表的 campaign_id 是 VARCHAR(50)。两列参与
--   uk_shop_campaign/JOIN 时走长度不一致的比较；Amazon campaignId 超过 50
--   字符时子表写入将失败或截断。统一收敛为 VARCHAR(64)。
--   （keyword_id 已由 V4 收敛为 BIGINT，不在本迁移范围。）
--   amz_ad_placement_report 也有 VARCHAR(50)，但它在零引用死表清单里
--   （HANDOFF 死表条目），按「不接线不收敛」处理；未来复活该表时必须同步改宽。
--
-- 附带：amz_ad_campaign 的 UNIQUE KEY uk_campaign(shop_id, campaign_id)
--   左前缀已覆盖 shop_id，单列 INDEX idx_shop 纯属冗余（写放大），一并 DROP。
--
-- 兼容性：MySQL 8.0；VARCHAR 只放宽不收紧， widening 是安全的在线 DDL。
-- 存量行长度均 <= 50，无需清洗。
--
-- 回滚：放宽类变更不需要回滚路径（收窄才是危险方向）；
--       idx_shop 如确需恢复可用 V1 定义重建。
ALTER TABLE amz_ad_campaign_ext
    MODIFY COLUMN campaign_id VARCHAR(64) NOT NULL COMMENT 'Amazon 广告活动 ID（对齐 amz_ad_campaign.campaign_id 64）';
ALTER TABLE amz_ad_creative
    MODIFY COLUMN campaign_id VARCHAR(64) NOT NULL COMMENT 'Amazon 广告活动 ID（对齐 amz_ad_campaign.campaign_id 64）';
ALTER TABLE amz_ad_targeting
    MODIFY COLUMN campaign_id VARCHAR(64) NOT NULL COMMENT 'Amazon 广告活动 ID（对齐 amz_ad_campaign.campaign_id 64）';
ALTER TABLE amz_ad_search_term
    MODIFY COLUMN campaign_id VARCHAR(64) NOT NULL COMMENT 'Amazon 广告活动 ID（对齐 amz_ad_campaign.campaign_id 64）';
-- converting_terms 的空性契约归 V7：V7 先把 NULL 归一成 '' 再按
-- uk_ad_converting_shop_campaign_term(shop_id, campaign_id, search_term) 全量去重，
-- 放回 NULL 会让 MySQL 唯一索引放过 NULL 重复、静默推翻该保证。只放宽宽度。
ALTER TABLE amz_ad_converting_terms
    MODIFY COLUMN campaign_id VARCHAR(64) NOT NULL DEFAULT '' COMMENT '来源活动（对齐 amz_ad_campaign.campaign_id 64）';
ALTER TABLE amz_ad_daily_report
    MODIFY COLUMN campaign_id VARCHAR(64) NOT NULL COMMENT 'Amazon 广告活动 ID（对齐 amz_ad_campaign.campaign_id 64）';

ALTER TABLE amz_ad_campaign DROP INDEX idx_shop;
