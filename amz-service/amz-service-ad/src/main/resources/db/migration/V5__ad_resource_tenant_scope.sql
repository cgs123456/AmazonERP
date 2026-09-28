-- Flyway Migration V5: add tenant scope to ad creatives and targeting rules.
--
-- These tables previously stored only campaign_id. campaign_id is unique only
-- within a shop (amz_ad_campaign_ext has UNIQUE(shop_id, campaign_id)), so the
-- old shape could not distinguish two shops that reused the same campaign id.
--
-- Backfill only rows whose campaign_id maps to exactly one shop. Ambiguous or
-- orphaned legacy rows intentionally remain NULL and are hidden by the
-- application's fail-closed shop_id filters until an operator remediates them.

ALTER TABLE amz_ad_creative
    ADD COLUMN shop_id BIGINT NULL COMMENT '所属店铺ID' AFTER id;

ALTER TABLE amz_ad_targeting
    ADD COLUMN shop_id BIGINT NULL COMMENT '所属店铺ID' AFTER id;

UPDATE amz_ad_creative c
JOIN (
    SELECT campaign_id, MIN(shop_id) AS shop_id
    FROM amz_ad_campaign_ext
    GROUP BY campaign_id
    HAVING COUNT(DISTINCT shop_id) = 1
) resolved ON resolved.campaign_id = c.campaign_id
SET c.shop_id = resolved.shop_id
WHERE c.shop_id IS NULL;

UPDATE amz_ad_targeting t
JOIN (
    SELECT campaign_id, MIN(shop_id) AS shop_id
    FROM amz_ad_campaign_ext
    GROUP BY campaign_id
    HAVING COUNT(DISTINCT shop_id) = 1
) resolved ON resolved.campaign_id = t.campaign_id
SET t.shop_id = resolved.shop_id
WHERE t.shop_id IS NULL;

CREATE INDEX idx_ad_creative_shop_campaign
    ON amz_ad_creative (shop_id, campaign_id, id);

CREATE INDEX idx_ad_targeting_shop_campaign
    ON amz_ad_targeting (shop_id, campaign_id, targeting_type, id);