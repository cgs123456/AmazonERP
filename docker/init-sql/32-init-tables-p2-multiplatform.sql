-- ============================================================
-- 32-init-tables-p2-multiplatform.sql
-- Phase 3 P2-1：多平台对接升级
-- 新增 6 张表
-- ============================================================

-- ---------------------------------------
-- 平台账号配置
-- ---------------------------------------
CREATE TABLE IF NOT EXISTS amz_platform_account (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id         BIGINT          NOT NULL COMMENT '店铺 ID',
    platform        VARCHAR(20)     NOT NULL COMMENT '平台：SHOPIFY/EBAY/WALMART/SHOPEE/LAZADA',
    store_name      VARCHAR(100)    NOT NULL COMMENT '店铺名称',
    api_endpoint    VARCHAR(300)    NOT NULL COMMENT 'API 端点',
    api_key         VARCHAR(500)    COMMENT 'API Key',
    api_secret_encrypted   VARCHAR(500) COMMENT 'API Secret (AES-256-GCM 加密)',
    access_token_encrypted VARCHAR(500) COMMENT 'Access Token (AES-256-GCM 加密)',
    refresh_token_encrypted VARCHAR(500) COMMENT 'Refresh Token (AES-256-GCM 加密)',
    token_expires_at       DATETIME  COMMENT 'Token 过期时间',
    status          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE/ERROR',
    last_sync_time  DATETIME        COMMENT '最近同步时间',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_shop_platform (shop_id, platform)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='多平台账号配置';

-- ---------------------------------------
-- 平台商品统一映射
-- ---------------------------------------
CREATE TABLE IF NOT EXISTS amz_platform_product (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id         BIGINT          NOT NULL COMMENT '店铺 ID',
    platform        VARCHAR(20)     NOT NULL COMMENT '平台',
    platform_product_id VARCHAR(100) NOT NULL COMMENT '平台侧商品 ID',
    platform_product_sku VARCHAR(100) COMMENT '平台侧 SKU',
    amazon_asin     VARCHAR(20)     COMMENT '对应亚马逊 ASIN',
    amazon_sku      VARCHAR(100)    COMMENT '对应亚马逊 SKU',
    title           VARCHAR(500)    NOT NULL COMMENT '商品标题',
    price            DECIMAL(12,2)  NOT NULL DEFAULT 0 COMMENT '商品价格（原币）',
    currency        VARCHAR(10)     DEFAULT 'USD' COMMENT '币种',
    stock_qty       INT             NOT NULL DEFAULT 0 COMMENT '库存数量',
    status          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE/OUT_OF_STOCK',
    image_url       VARCHAR(1000)   COMMENT '主图 URL',
    category        VARCHAR(200)    COMMENT '类目',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_platform_product (platform, platform_product_id),
    INDEX idx_shop_platform (shop_id, platform),
    INDEX idx_amazon_asin (amazon_asin)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='多平台商品统一映射表';

-- ---------------------------------------
-- 平台消息/站内信统一管理
-- ---------------------------------------
CREATE TABLE IF NOT EXISTS amz_platform_message (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id         BIGINT          NOT NULL COMMENT '店铺 ID',
    platform        VARCHAR(20)     NOT NULL COMMENT '平台',
    platform_message_id VARCHAR(100) NOT NULL COMMENT '平台侧消息 ID',
    buyer_name      VARCHAR(200)    COMMENT '买家名称',
    buyer_email     VARCHAR(200)    COMMENT '买家邮箱',
    order_id        VARCHAR(100)    COMMENT '关联订单 ID',
    subject         VARCHAR(500)    COMMENT '主题',
    content         TEXT            COMMENT '消息内容',
    direction       VARCHAR(10)     NOT NULL DEFAULT 'IN' COMMENT 'IN(买家→卖家) / OUT(卖家→买家)',
    status          VARCHAR(20)     NOT NULL DEFAULT 'UNREAD' COMMENT 'UNREAD/READ/REPLIED/ARCHIVED',
    assigned_to     VARCHAR(100)    COMMENT '分配给客服',
    is_urgent       TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '是否紧急',
    receive_time    DATETIME        NOT NULL COMMENT '接收时间',
    reply_time      DATETIME        COMMENT '回复时间',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_platform_msg_id (platform, platform_message_id),
    INDEX idx_shop_status (shop_id, status),
    INDEX idx_shop_time (shop_id, receive_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='多平台消息统一管理表';

-- ---------------------------------------
-- 平台库存快照
-- ---------------------------------------
CREATE TABLE IF NOT EXISTS amz_platform_inventory (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id         BIGINT          NOT NULL COMMENT '店铺 ID',
    platform        VARCHAR(20)     NOT NULL COMMENT '平台',
    platform_product_id VARCHAR(100) NOT NULL COMMENT '平台侧商品 ID',
    sku             VARCHAR(100)    NOT NULL COMMENT 'SKU',
    warehouse       VARCHAR(100)    COMMENT '仓库名称',
    available_qty   INT             NOT NULL DEFAULT 0 COMMENT '可售数量',
    reserved_qty    INT             NOT NULL DEFAULT 0 COMMENT '预留数量',
    inbound_qty     INT             NOT NULL DEFAULT 0 COMMENT '在途数量',
    snapshot_time   DATETIME        NOT NULL COMMENT '快照时间',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_sku_snapshot (sku, snapshot_time),
    INDEX idx_shop_platform (shop_id, platform)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='多平台库存快照表';

-- ---------------------------------------
-- Webhook 事件记录
-- ---------------------------------------
CREATE TABLE IF NOT EXISTS amz_webhook_event (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id         BIGINT          NOT NULL COMMENT '店铺 ID',
    platform        VARCHAR(20)     NOT NULL COMMENT '平台',
    event_type      VARCHAR(100)    NOT NULL COMMENT '事件类型：ORDER_CREATED/ORDER_CANCELLED/INVENTORY_CHANGE/PRICE_CHANGE/MESSAGE_NEW',
    event_id        VARCHAR(100)    COMMENT '平台侧事件 ID（幂等去重）',
    payload         LONGTEXT        COMMENT '原始 Payload JSON',
    status          VARCHAR(20)     NOT NULL DEFAULT 'RECEIVED' COMMENT 'RECEIVED/PROCESSED/FAILED',
    process_result  VARCHAR(1000)   COMMENT '处理结果/错误信息',
    process_time    DATETIME        COMMENT '处理完成时间',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_event_id (event_id),
    INDEX idx_shop_type (shop_id, event_type),
    INDEX idx_status_time (status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='Webhook 事件记录表';

-- ---------------------------------------
-- OAuth 应用注册表（开放 API）
-- ---------------------------------------
CREATE TABLE IF NOT EXISTS amz_oauth_app (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    app_name        VARCHAR(100)    NOT NULL COMMENT '应用名称',
    app_key         VARCHAR(64)     NOT NULL COMMENT 'App Key',
    app_secret_encrypted VARCHAR(256) NOT NULL COMMENT 'App Secret (AES-256-GCM 加密)',
    redirect_uris   TEXT            COMMENT '回调 URL 列表 (JSON Array)',
    scopes          VARCHAR(500)    COMMENT '授权范围（逗号分隔）',
    owner_shop_id   BIGINT          NOT NULL COMMENT '应用归属店铺',
    rate_limit_rpm  INT             NOT NULL DEFAULT 60 COMMENT '每分钟请求限制',
    status          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/SUSPENDED/REVOKED',
    description     VARCHAR(500)    COMMENT '应用描述',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_app_key (app_key),
    INDEX idx_owner (owner_shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='OAuth 应用注册表';

-- ---------------------------------------
-- OAuth 授权 Token
-- ---------------------------------------
CREATE TABLE IF NOT EXISTS amz_oauth_token (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    app_id          BIGINT          NOT NULL COMMENT 'OAuth App ID',
    access_token    VARCHAR(256)    NOT NULL COMMENT 'Access Token',
    refresh_token   VARCHAR(256)    COMMENT 'Refresh Token',
    token_type      VARCHAR(20)     NOT NULL DEFAULT 'Bearer',
    expires_at      DATETIME        NOT NULL COMMENT '过期时间',
    scopes          VARCHAR(500)    COMMENT '已授权范围',
    shop_id         BIGINT          COMMENT '授权店铺 ID',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_access_token (access_token),
    INDEX idx_app_id (app_id),
    INDEX idx_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='OAuth 授权 Token 表';

-- 预置示例数据
INSERT IGNORE INTO amz_oauth_app (id, app_name, app_key, app_secret_encrypted, redirect_uris, scopes, owner_shop_id, rate_limit_rpm, description)
VALUES (1, 'ERP Partner App', 'ak_demo_001', 'base64encrypted_placeholder', '["https://partner.example.com/oauth/callback"]', 'order:read,order:write,inventory:read,report:read', 1, 60, '演示 OAuth 应用');
