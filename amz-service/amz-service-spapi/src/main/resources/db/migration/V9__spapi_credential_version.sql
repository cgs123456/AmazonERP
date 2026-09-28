-- Flyway Migration V9: optimistic concurrency version for shop credentials
-- Service: amz-service-spapi
--
-- Multi-instance credential rotation must not overwrite a concurrent field
-- update. Existing rows start at version 0; every successful write increments
-- the version atomically. Partial admin updates use the version in WHERE.

ALTER TABLE `amz_shop_credential`
    ADD COLUMN `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号，每次写入原子递增';
