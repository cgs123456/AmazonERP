-- Flyway Migration V3: align amz_ad_daily_report with mapper upsert contract.
-- Existing V1 deployments do not have update_time, while AdDailyReportMapper.upsert
-- writes it on duplicate-key updates.
ALTER TABLE amz_ad_daily_report
    ADD COLUMN update_time DATETIME DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP AFTER create_time;