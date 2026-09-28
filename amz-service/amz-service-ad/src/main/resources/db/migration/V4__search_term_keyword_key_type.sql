-- Flyway Migration V4: 搜索词表关键词引用键类型收敛
-- Service: amz-service-ad
--
-- Why（可核证据：tools/synthetic-data/verify.py 的 id types 门禁）
--   amz_ad_search_term.keyword_id VARCHAR(50)  vs  amz_ad_keyword.id BIGINT。
--   Advertising API 的 keywordId 是数值型（AdvertisingApiRealClient 用 requiredLong(row, "keywordId")
--   解析并写入 amz_ad_keyword.id），搜索词报表里以字符串形式下发，落库后应与本地关键词主键同类型，
--   否则 `keyword_id = amz_ad_keyword.id` 的关联会走隐式类型转换，索引失效。
--   统一为 BIGINT；Ads 平台 keywordId 的取值远小于 BIGINT 上界，无溢出风险。
--
-- 兼容性：MySQL 8.0；不改写已执行的 V1。存量行若为非数值字符串，迁移前需先清洗（当前无真实数据）。
--
-- 回滚：见 docs/superpowers/runbooks/reference-key-convergence-rollback.md
--       （仅在零真实数据窗口内可逆；有真实数据后按本迁移方向继续，不再回退）。
ALTER TABLE amz_ad_search_term
    MODIFY COLUMN keyword_id BIGINT NULL COMMENT '广告关键词 ID（对齐 amz_ad_keyword.id BIGINT）';
