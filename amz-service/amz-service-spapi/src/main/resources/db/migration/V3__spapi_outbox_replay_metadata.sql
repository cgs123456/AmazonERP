-- Preserve the complete success-status set and the rate-limit variant needed to
-- replay calls without widening 2xx into success or losing per-variant quotas.
ALTER TABLE `amz_spapi_call_outbox`
    ADD COLUMN `expected_statuses` VARCHAR(64) DEFAULT NULL
        COMMENT '逗号分隔的成功 HTTP 状态码集合，如 200,202' AFTER `expected_status`,
    ADD COLUMN `rate_limit_variant` VARCHAR(128) DEFAULT NULL
        COMMENT 'SP-API 限流分档（如 JSON_LISTINGS_FEED）' AFTER `expected_statuses`;

-- Existing rows only retained the first status. Backfill the new field so
-- replay remains fail-closed for old records instead of defaulting to 200.
UPDATE `amz_spapi_call_outbox`
SET `expected_statuses` = CAST(`expected_status` AS CHAR)
WHERE `expected_statuses` IS NULL
  AND `expected_status` IS NOT NULL;
