-- =============================================================================
-- amz_shop_credential：店铺 SP-API 凭证表
-- =============================================================================
-- 用途：持久化各店铺的 SP-API 凭证，避免服务重启后凭证丢失。
-- 安全：敏感字段（client_secret / refresh_token / access_key / secret_key）
--       均以 AES-256-GCM 密文存储，列名以 _encrypted 结尾。
--       加解密由应用层 CryptoUtil 负责，DB 仅保存密文。
-- 主键：shop_id 由业务层提供（IdType.INPUT），不自增。
-- 字符集：utf8mb4，与 amz_spapi 库其他表保持一致。
-- =============================================================================
CREATE TABLE IF NOT EXISTS `amz_shop_credential` (
    `shop_id`                   BIGINT       NOT NULL                            COMMENT '店铺主键 ID（业务提供）',
    `client_id`                 VARCHAR(128)         DEFAULT NULL                 COMMENT 'LWA Client ID（非敏感）',
    `client_secret_encrypted`   VARCHAR(512)         DEFAULT NULL                 COMMENT 'LWA Client Secret（AES-256-GCM 密文）',
    `refresh_token_encrypted`   VARCHAR(512)         DEFAULT NULL                 COMMENT 'SP-API 刷新令牌（AES-256-GCM 密文）',
    `access_key_encrypted`      VARCHAR(512)         DEFAULT NULL                 COMMENT 'AWS Access Key ID（AES-256-GCM 密文）',
    `secret_key_encrypted`      VARCHAR(512)         DEFAULT NULL                 COMMENT 'AWS Secret Access Key（AES-256-GCM 密文）',
    `region`                    VARCHAR(16)          DEFAULT NULL                 COMMENT 'SP-API 区域：NA / EU / FE',
    `marketplace_id`            VARCHAR(32)          DEFAULT NULL                 COMMENT 'Amazon Marketplace ID（如 ATVPDKIKX0DER）',
    `seller_id`                 VARCHAR(32)          DEFAULT NULL                 COMMENT 'Amazon Seller ID',
    `create_time`               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP     COMMENT '创建时间',
    `update_time`               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`shop_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='店铺 SP-API 凭证（密文存储）';
