-- Flyway Migration V2: make Advertising campaign metadata upsert-safe.
-- Keep the latest row (highest id) for each shop/campaign pair, then enforce
-- the same unique key used by AdCampaignExtMapper.upsertMetadata.
DELETE older
FROM amz_ad_campaign_ext older
JOIN amz_ad_campaign_ext newer
  ON newer.shop_id = older.shop_id
 AND newer.campaign_id = older.campaign_id
 AND newer.id > older.id;

ALTER TABLE amz_ad_campaign_ext
    ADD UNIQUE KEY uk_shop_campaign (shop_id, campaign_id);
