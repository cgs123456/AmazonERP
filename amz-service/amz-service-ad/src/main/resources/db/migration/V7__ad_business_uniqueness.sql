-- Flyway Migration V7: enforce advertising business uniqueness.
--
-- MySQL 8.0 only. Before production execution, run the preflight queries in
-- docs/superpowers/runbooks/ad-business-uniqueness-migration.md and take a
-- backup/snapshot. Duplicate rows are normalized first, then the newest
-- snapshot is copied onto the canonical row before older rows are deleted.
--
-- Why:
--   * keyword writes use ON DUPLICATE KEY UPDATE, but no unique key exists.
--   * converting terms are aggregated by shop + campaign + search term, but
--     the current table has no matching uniqueness constraint.
--   * ASIN reverse lookup is keyed by shop + ASIN + keyword, but writes used
--     select-then-insert/update and can race into duplicates.

-- ---------------------------------------------------------------------------
-- 1. amz_ad_keyword: normalize, merge the newest snapshot, deduplicate.
-- ---------------------------------------------------------------------------
UPDATE amz_ad_keyword
   SET campaign_id = TRIM(campaign_id),
       keyword = LOWER(TRIM(keyword)),
       match_type = UPPER(TRIM(COALESCE(NULLIF(match_type, ''), 'EXACT')));

UPDATE amz_ad_keyword AS keeper
JOIN (
    SELECT shop_id,
           campaign_id,
           keyword,
           match_type,
           MIN(id) AS keeper_id,
           MAX(id) AS latest_id
      FROM amz_ad_keyword
     GROUP BY shop_id, campaign_id, keyword, match_type
    HAVING COUNT(*) > 1
) AS duplicate_keys
  ON duplicate_keys.shop_id = keeper.shop_id
 AND duplicate_keys.campaign_id = keeper.campaign_id
 AND duplicate_keys.keyword = keeper.keyword
 AND duplicate_keys.match_type = keeper.match_type
 AND duplicate_keys.keeper_id = keeper.id
JOIN amz_ad_keyword AS latest
  ON latest.id = duplicate_keys.latest_id
 SET keeper.bid = latest.bid,
     keeper.base_bid = COALESCE(latest.base_bid, keeper.base_bid),
     keeper.state = latest.state,
     keeper.update_time = NOW();

DELETE older
  FROM amz_ad_keyword AS older
  JOIN amz_ad_keyword AS keeper
    ON keeper.shop_id = older.shop_id
   AND keeper.campaign_id = older.campaign_id
   AND keeper.keyword = older.keyword
   AND keeper.match_type = older.match_type
   AND keeper.id < older.id;

ALTER TABLE amz_ad_keyword
    MODIFY COLUMN keyword VARCHAR(200) NOT NULL,
    MODIFY COLUMN match_type VARCHAR(10) NOT NULL DEFAULT 'EXACT',
    ADD UNIQUE KEY uk_ad_keyword_shop_campaign_keyword_match
        (shop_id, campaign_id, keyword, match_type);

-- ---------------------------------------------------------------------------
-- 2. amz_ad_converting_terms: normalize, merge the newest snapshot, dedupe.
-- ---------------------------------------------------------------------------
UPDATE amz_ad_converting_terms
   SET campaign_id = ''
 WHERE campaign_id IS NULL;

UPDATE amz_ad_converting_terms
   SET asin = CASE WHEN asin IS NULL OR TRIM(asin) = '' THEN NULL ELSE UPPER(TRIM(asin)) END,
       search_term = LOWER(TRIM(search_term)),
       campaign_id = TRIM(campaign_id);

UPDATE amz_ad_converting_terms AS keeper
JOIN (
    SELECT shop_id,
           campaign_id,
           search_term,
           MIN(id) AS keeper_id,
           MAX(id) AS latest_id
      FROM amz_ad_converting_terms
     GROUP BY shop_id, campaign_id, search_term
    HAVING COUNT(*) > 1
) AS duplicate_keys
  ON duplicate_keys.shop_id = keeper.shop_id
 AND duplicate_keys.campaign_id = keeper.campaign_id
 AND duplicate_keys.search_term = keeper.search_term
 AND duplicate_keys.keeper_id = keeper.id
JOIN amz_ad_converting_terms AS latest
  ON latest.id = duplicate_keys.latest_id
 SET keeper.asin = COALESCE(latest.asin, keeper.asin),
     keeper.total_orders = latest.total_orders,
     keeper.total_sales = latest.total_sales,
     keeper.total_cost = latest.total_cost,
     keeper.avg_acos = latest.avg_acos,
     keeper.first_seen = latest.first_seen,
     keeper.last_seen = latest.last_seen,
     keeper.update_time = NOW();

DELETE older
  FROM amz_ad_converting_terms AS older
  JOIN amz_ad_converting_terms AS keeper
    ON keeper.shop_id = older.shop_id
   AND keeper.campaign_id = older.campaign_id
   AND keeper.search_term = older.search_term
   AND keeper.id < older.id;

ALTER TABLE amz_ad_converting_terms
    MODIFY campaign_id VARCHAR(50) NOT NULL DEFAULT '',
    ADD UNIQUE KEY uk_ad_converting_shop_campaign_term
        (shop_id, campaign_id, search_term);

-- ---------------------------------------------------------------------------
-- 3. amz_ad_asin_keyword: normalize, merge the newest snapshot, deduplicate.
-- ---------------------------------------------------------------------------
UPDATE amz_ad_asin_keyword
   SET asin = UPPER(TRIM(asin)),
       keyword = LOWER(TRIM(keyword));

UPDATE amz_ad_asin_keyword AS keeper
JOIN (
    SELECT shop_id,
           asin,
           keyword,
           MIN(id) AS keeper_id,
           MAX(id) AS latest_id
      FROM amz_ad_asin_keyword
     GROUP BY shop_id, asin, keyword
    HAVING COUNT(*) > 1
) AS duplicate_keys
  ON duplicate_keys.shop_id = keeper.shop_id
 AND duplicate_keys.asin = keeper.asin
 AND duplicate_keys.keyword = keeper.keyword
 AND duplicate_keys.keeper_id = keeper.id
JOIN amz_ad_asin_keyword AS latest
  ON latest.id = duplicate_keys.latest_id
 SET keeper.organic_rank = latest.organic_rank,
     keeper.ad_rank = latest.ad_rank,
     keeper.search_volume = latest.search_volume,
     keeper.relevance_score = latest.relevance_score,
     keeper.is_indexed = latest.is_indexed,
     keeper.last_checked = latest.last_checked,
     keeper.update_time = NOW();

DELETE older
  FROM amz_ad_asin_keyword AS older
  JOIN amz_ad_asin_keyword AS keeper
    ON keeper.shop_id = older.shop_id
   AND keeper.asin = older.asin
   AND keeper.keyword = older.keyword
   AND keeper.id < older.id;

ALTER TABLE amz_ad_asin_keyword
    ADD UNIQUE KEY uk_ad_asin_shop_asin_keyword
        (shop_id, asin, keyword);