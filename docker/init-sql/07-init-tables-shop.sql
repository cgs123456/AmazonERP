-- Amazon ERP 多店铺 RBAC 建表 SQL
-- 数据库: amz_user

CREATE TABLE IF NOT EXISTS amz_shop (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shop_name VARCHAR(100) NOT NULL COMMENT '店铺名称',
    marketplace_id VARCHAR(20) NOT NULL COMMENT 'Amazon Marketplace ID（ATVPDKIKX0DER=美国站）',
    region VARCHAR(10) NOT NULL COMMENT 'NA/EU/FE',
    seller_id VARCHAR(30) COMMENT 'Amazon Seller ID',
    spapi_refresh_token TEXT COMMENT 'SP-API 刷新令牌（加密存储）',
    spapi_client_id VARCHAR(200) COMMENT 'LWA Client ID',
    spapi_client_secret VARCHAR(200) COMMENT 'LWA Client Secret（加密存储）',
    status INT DEFAULT 0 COMMENT '1=已授权 0=未授权 -1=授权过期',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Amazon 店铺表';

CREATE TABLE IF NOT EXISTS amz_user_shop (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    shop_id BIGINT NOT NULL COMMENT '店铺ID',
    role VARCHAR(20) DEFAULT 'OPERATOR' COMMENT 'ADMIN/OPERATOR/VIEWER',
    INDEX idx_user (user_id),
    INDEX idx_shop (shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户-店铺关联表（RBAC）';
