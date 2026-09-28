-- Preserve the exact authentication source and bounded RDT scope for replay.
-- RDT token values are intentionally never persisted; replay requests a new token
-- from the Tokens API using the encrypted resource definition.
ALTER TABLE `amz_spapi_call_outbox`
    ADD COLUMN `token_source` VARCHAR(16) NOT NULL DEFAULT 'LWA'
        COMMENT 'LWA or RDT; existing rows are LWA' AFTER `replay_of_id`,
    ADD COLUMN `restricted_resources_encrypted` MEDIUMTEXT DEFAULT NULL
        COMMENT '受限资源定义 AES-256-GCM 密文，不含 RDT' AFTER `token_source`,
    ADD COLUMN `restricted_resource_hash` VARCHAR(64) DEFAULT NULL
        COMMENT '受限资源规范集合 SHA-256，用于重放篡改检测' AFTER `restricted_resources_encrypted`,
    ADD COLUMN `restricted_resource_count` INT DEFAULT NULL
        COMMENT '受限资源数量，用于重放完整性校验' AFTER `restricted_resource_hash`;

-- Backfill is explicit and fail-closed: legacy calls did not persist restricted
-- resources, so they can only be replayed as normal LWA calls.
UPDATE `amz_spapi_call_outbox`
SET `token_source` = 'LWA'
WHERE `token_source` IS NULL OR TRIM(`token_source`) = '';