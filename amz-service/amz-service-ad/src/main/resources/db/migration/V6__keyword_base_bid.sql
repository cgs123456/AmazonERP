-- Flyway Migration V6: persist the baseline bid used by hourly bid scheduling.
--
-- BidScheduleExecutor computes every hour as baseline * multiplier. Without a
-- durable baseline, a second run would treat the first run's adjusted bid as
-- the next baseline and compound the multiplier. The column is nullable so the
-- executor can claim existing rows safely and fail closed if persistence fails.

ALTER TABLE amz_ad_keyword
    ADD COLUMN base_bid DECIMAL(10,2) NULL COMMENT '分时调价基准竞价（美元）' AFTER bid;

CREATE INDEX idx_ad_keyword_shop_campaign_id
    ON amz_ad_keyword (shop_id, campaign_id, id);