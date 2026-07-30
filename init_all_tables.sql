-- =============================================================================
-- Amazon ERP 全量建表脚本 (init_all_tables.sql)
-- =============================================================================
-- 用途：一键初始化 Amazon ERP 全部微服务所需的数据库与表结构。
--       本脚本聚合 docker/init-sql/ 下 01~22 各模块脚本与
--       amz-service-spapi/src/main/resources/db/schema.sql 的全部 CREATE TABLE。
-- 字段：与各服务的 @TableName / @TableField 实体类一一对应。
-- 幂等：所有 CREATE TABLE / CREATE DATABASE 均使用 IF NOT EXISTS，可重复执行。
-- 字符集：utf8mb4 + utf8mb4_unicode_ci
-- 引擎：InnoDB
-- =============================================================================
-- 对应实体覆盖清单（@TableName 注解）：
--   amz_user:               amz_user, amz_attention, amz_user_shop, amz_oper_log, amz_field_permission
--   amz_shop (amz_user):    amz_shop
--   amz_product:            amz_cart, amz_product_browse, amz_coupon, amz_user_coupon,
--                           amz_product, amz_listing_copy_task, amz_translation_cache
--   amz_order:              amz_order_attribute, amz_order, amz_product_cost,
--                           amz_category_fee_rate, amz_fba_fee_table, amz_profit_report
--   amz_search:             amz_history
--   amz_spapi:              amz_fba_inventory, amz_product_sales_stats, amz_inventory_sync_log,
--                           amz_replenishment_suggestion, amz_sales_history, amz_seasonal_index,
--                           amz_promotion_calendar, amz_shop_credential
--   amz_ad:                 amz_ad_campaign, amz_ad_keyword, amz_ad_bid_schedule,
--                           amz_ad_campaign_ext, amz_ad_creative, amz_ad_targeting
--   amz_procurement:        amz_purchase_order, amz_quality_check
--   amz_customer:           amz_customer_ticket, amz_review_solicitation
--   amz_logistics:          amz_shipment, amz_tracking_event, amz_warehouse,
--                           amz_warehouse_inventory, amz_inbound_order, amz_outbound_order
--   amz_ops:                amz_negative_review_alert, amz_hijack_alert, amz_keyword_rank,
--                           amz_selection_opportunity, amz_keyword_research
--   amz_finance:            amz_accounting_voucher
--   amz_multiplatform:      amz_unified_order
--   amz_ai:                 amz_user_preference, amz_conversation_memory
-- =============================================================================


-- =============================================================================
-- 0. 建库
-- =============================================================================
CREATE DATABASE IF NOT EXISTS amz_user          DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS amz_product       DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS amz_order         DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS amz_search        DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS amz_spapi         DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS amz_ad            DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS amz_procurement   DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS amz_customer      DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS amz_logistics     DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS amz_ops           DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS amz_finance       DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS amz_multiplatform DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS amz_ai            DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;


-- =============================================================================
-- 1. amz_user 库：用户 / 关注 / 店铺 / RBAC / 操作日志 / 字段权限
-- =============================================================================
USE amz_user;

-- 用户表（对应 com.amz.model.pojo.User @TableName("amz_user")）
CREATE TABLE IF NOT EXISTS amz_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    nickname VARCHAR(50) DEFAULT '',
    image VARCHAR(500) DEFAULT '',
    mail VARCHAR(100) DEFAULT '',
    phone VARCHAR(20) DEFAULT '',
    sex TINYINT DEFAULT 0,
    birthday VARCHAR(20) DEFAULT '',
    address VARCHAR(200) DEFAULT '',
    role VARCHAR(50) NOT NULL DEFAULT 'VIEWER' COMMENT '角色 ADMIN/OPERATOR/VIEWER',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 用户关注表
CREATE TABLE IF NOT EXISTS amz_attention (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    attention_id BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_attention (user_id, attention_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户关注表';

-- Amazon 店铺表（对应 com.amz.model.pojo.Shop @TableName("amz_shop")）
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

-- 用户-店铺关联表（对应 com.amz.model.pojo.UserShop @TableName("amz_user_shop")）
CREATE TABLE IF NOT EXISTS amz_user_shop (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    shop_id BIGINT NOT NULL COMMENT '店铺ID',
    role VARCHAR(20) DEFAULT 'OPERATOR' COMMENT 'ADMIN/OPERATOR/VIEWER',
    INDEX idx_user (user_id),
    INDEX idx_shop (shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户-店铺关联表（RBAC）';

-- 操作日志审计表
CREATE TABLE IF NOT EXISTS amz_oper_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键 ID',
    user_id BIGINT DEFAULT NULL COMMENT '操作人用户 ID（来自 UserContext，未登录时为空）',
    module VARCHAR(64) NOT NULL COMMENT '业务模块名',
    action VARCHAR(64) NOT NULL COMMENT '操作类型，如 查询/新增/修改/删除/导出',
    description VARCHAR(255) DEFAULT NULL COMMENT '操作描述',
    method VARCHAR(255) NOT NULL COMMENT '执行方法签名',
    params TEXT DEFAULT NULL COMMENT '方法入参（JSON）',
    result TEXT DEFAULT NULL COMMENT '返回值（JSON，异常时为空）',
    ip VARCHAR(64) DEFAULT NULL COMMENT '请求来源 IP',
    status VARCHAR(16) NOT NULL DEFAULT 'SUCCESS' COMMENT '执行状态：SUCCESS / FAIL',
    error_msg TEXT DEFAULT NULL COMMENT '异常信息（status=FAIL 时填充）',
    cost_time BIGINT DEFAULT 0 COMMENT '方法耗时（毫秒）',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    INDEX idx_user_id (user_id),
    INDEX idx_create_time (create_time),
    INDEX idx_module (module)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志审计表';

-- 字段级数据权限规则表
CREATE TABLE IF NOT EXISTS amz_field_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_code VARCHAR(50) NOT NULL COMMENT '角色代码 ADMIN/OPERATOR/VIEWER',
    service_name VARCHAR(50) NOT NULL COMMENT '微服务名',
    entity_name VARCHAR(100) NOT NULL COMMENT '实体名（Java 类简单名）',
    field_name VARCHAR(100) NOT NULL COMMENT '字段名（Java 反射字段名）',
    visible TINYINT(1) DEFAULT 0 COMMENT '是否可见 0-隐藏 1-可见',
    UNIQUE KEY uk_role_entity_field (role_code, service_name, entity_name, field_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字段级数据权限规则表';


-- =============================================================================
-- 2. amz_product 库：商品主数据 / 购物车 / 优惠券 / Listing 复制 / 翻译缓存
-- =============================================================================
USE amz_product;

-- 购物车表
CREATE TABLE IF NOT EXISTS amz_cart (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    count INT DEFAULT 1,
    custom_attribute TEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购物车表';

-- 商品浏览历史表
CREATE TABLE IF NOT EXISTS amz_product_browse (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品浏览历史表';

-- 优惠券表
CREATE TABLE IF NOT EXISTS amz_coupon (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    discount DECIMAL(10,2) DEFAULT 0,
    `limit` DECIMAL(10,2) DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优惠券表';

-- 用户-优惠券关联表
CREATE TABLE IF NOT EXISTS amz_user_coupon (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    coupon_id BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户-优惠券关联表';

-- Amazon 商品主数据（对应 com.amz.model.AmzProduct / pojo.Product @TableName("amz_product")）
CREATE TABLE IF NOT EXISTS amz_product (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shop_id BIGINT NOT NULL,
    sku VARCHAR(50) NOT NULL,
    asin VARCHAR(20),
    marketplace_id VARCHAR(20) NOT NULL,
    title VARCHAR(500) NOT NULL,
    description TEXT,
    brand VARCHAR(100),
    price DECIMAL(10,2),
    currency VARCHAR(10) DEFAULT 'USD',
    category VARCHAR(50),
    size_tier VARCHAR(30),
    weight_g INT,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_shop_sku_market (shop_id, sku, marketplace_id),
    INDEX idx_asin (asin)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Amazon 商品主数据';

-- Listing 复制任务（对应 com.amz.model.ListingCopyTask @TableName("amz_listing_copy_task")）
CREATE TABLE IF NOT EXISTS amz_listing_copy_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shop_id BIGINT NOT NULL,
    source_marketplace_id VARCHAR(20) NOT NULL,
    target_marketplace_id VARCHAR(20) NOT NULL,
    sku VARCHAR(50) NOT NULL,
    source_title VARCHAR(500),
    source_price DECIMAL(10,2),
    target_title VARCHAR(500),
    target_price DECIMAL(10,2),
    target_language VARCHAR(10) COMMENT 'de/it/es/fr/ja',
    exchange_rate DECIMAL(10,4),
    price_markup DECIMAL(4,2) DEFAULT 0.20 COMMENT '加价比例 0.20=20%',
    status VARCHAR(20) DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/SUBMITTED/SUCCESS/FAILED',
    feed_submission_id VARCHAR(50) COMMENT 'Amazon Feed ID',
    error_message TEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop_status (shop_id, status),
    INDEX idx_sku (sku)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Listing 复制任务';

-- 翻译缓存（对应 com.amz.model.TranslationCache @TableName("amz_translation_cache")）
CREATE TABLE IF NOT EXISTS amz_translation_cache (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_text_hash VARCHAR(64) NOT NULL COMMENT 'SHA-256 原文哈希',
    source_lang VARCHAR(10) NOT NULL,
    target_lang VARCHAR(10) NOT NULL,
    source_text TEXT,
    translated_text TEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_hash_langs (source_text_hash, source_lang, target_lang)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='翻译缓存';


-- =============================================================================
-- 3. amz_order 库：订单 / 订单属性 / 利润核算 / 成本与费率
-- =============================================================================
USE amz_order;

-- 订单属性表（对应 com.amz.model.pojo.OrderAttribute @TableName("amz_order_attribute")）
CREATE TABLE IF NOT EXISTS amz_order_attribute (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    name VARCHAR(100) DEFAULT '',
    value VARCHAR(200) DEFAULT '',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单属性表';

-- Amazon 订单表（对应 com.amz.model.pojo.Order @TableName("amz_order")）
CREATE TABLE IF NOT EXISTS amz_order (
    id BIGINT PRIMARY KEY,
    product_id INT COMMENT '产品ID',
    quantity INT COMMENT '商品数量',
    coupon_id INT COMMENT '优惠券ID',
    final_price DECIMAL(10,2) COMMENT '最终价格',
    user_id INT COMMENT '订单归属人ID',
    status INT DEFAULT 0 COMMENT '原状态字段',
    shop_id BIGINT COMMENT '所属店铺',
    amazon_order_id VARCHAR(30) COMMENT 'Amazon 订单号',
    marketplace_id VARCHAR(20) COMMENT '站点 ID',
    order_status VARCHAR(20) COMMENT 'Amazon 订单状态：Pending/Unshipped/Shipped/Canceled',
    buyer_name VARCHAR(100) COMMENT '买家姓名（PII）',
    purchase_date DATETIME COMMENT '购买时间',
    last_update_date DATETIME COMMENT '最后更新时间',
    fulfillment_channel VARCHAR(10) COMMENT 'AFN（FBA）或 MFN（自发货）',
    ship_service_level VARCHAR(30) COMMENT 'Standard/Expedited/Priority',
    tracking_number VARCHAR(100) COMMENT '物流跟踪号',
    sync_status INT DEFAULT 0 COMMENT '0=未同步 1=已同步 2=已上传跟踪号 3=已完成',
    UNIQUE INDEX uk_amazon_order (amazon_order_id),
    INDEX idx_shop (shop_id),
    INDEX idx_status (order_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Amazon 订单表';

-- 采购成本表（对应 com.amz.model.ProductCost @TableName("amz_product_cost")）
CREATE TABLE IF NOT EXISTS amz_product_cost (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shop_id BIGINT NOT NULL,
    sku VARCHAR(50) NOT NULL,
    unit_cost DECIMAL(10,2) NOT NULL COMMENT '采购单价',
    shipping_cost DECIMAL(10,2) DEFAULT 0 COMMENT '头程运费',
    customs_cost DECIMAL(10,2) DEFAULT 0 COMMENT '关税',
    lead_time_days INT DEFAULT 30 COMMENT '采购周期',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_shop_sku (shop_id, sku)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购成本表';

-- 类目佣金率表（对应 com.amz.model.CategoryFeeRate @TableName("amz_category_fee_rate")）
CREATE TABLE IF NOT EXISTS amz_category_fee_rate (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    category_name VARCHAR(50) NOT NULL,
    referral_fee_rate DECIMAL(5,4) NOT NULL COMMENT '类目佣金率 0.15=15%',
    UNIQUE KEY uk_category (category_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='类目佣金率';

-- FBA 费率表（对应 com.amz.model.FbaFeeTable @TableName("amz_fba_fee_table")）
CREATE TABLE IF NOT EXISTS amz_fba_fee_table (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    size_tier VARCHAR(30) NOT NULL COMMENT 'small-standard/large-standard/...',
    weight_g INT NOT NULL COMMENT '重量（克）',
    region VARCHAR(20) NOT NULL COMMENT 'NA/EU/FE',
    fulfillment_fee DECIMAL(10,2) NOT NULL COMMENT 'FBA 履约费',
    storage_fee_per_month DECIMAL(10,2) NOT NULL COMMENT '月仓储费',
    UNIQUE KEY uk_size_weight_region (size_tier, weight_g, region)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='FBA 费率表';

-- 订单级利润报告（对应 com.amz.model.ProfitReport @TableName("amz_profit_report")）
CREATE TABLE IF NOT EXISTS amz_profit_report (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shop_id BIGINT NOT NULL,
    amazon_order_id VARCHAR(30) NOT NULL,
    sku VARCHAR(50) NOT NULL,
    stat_date DATE NOT NULL,
    revenue DECIMAL(12,2) COMMENT '收入',
    product_cost DECIMAL(12,2) COMMENT '采购成本',
    fba_fulfillment_fee DECIMAL(12,2) COMMENT 'FBA 履约费',
    fba_storage_fee DECIMAL(12,2) COMMENT 'FBA 仓储费',
    referral_fee DECIMAL(12,2) COMMENT '平台佣金',
    ad_cost DECIMAL(12,2) COMMENT '广告费',
    vat DECIMAL(12,2) COMMENT 'VAT',
    gross_profit DECIMAL(12,2) COMMENT '毛利',
    net_profit DECIMAL(12,2) COMMENT '净利',
    net_margin DECIMAL(6,4) COMMENT '净利率',
    data_complete TINYINT(1) DEFAULT 1 COMMENT '数据是否完整：0=缺失采购成本或类目佣金率，仅作估算',
    UNIQUE KEY uk_shop_order_sku (shop_id, amazon_order_id, sku),
    INDEX idx_date (stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单级利润报告';


-- =============================================================================
-- 4. amz_search 库：搜索历史
-- =============================================================================
USE amz_search;

-- 搜索历史表（对应 com.amz.model.pojo.History @TableName("amz_history")）
CREATE TABLE IF NOT EXISTS amz_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    keyword VARCHAR(200) NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='搜索历史表';


-- =============================================================================
-- 5. amz_spapi 库：FBA 库存 / 销量统计 / 补货引擎 / SP-API 凭证
-- =============================================================================
USE amz_spapi;

-- FBA 库存主表（对应 com.amz.model.FbaInventory @TableName("amz_fba_inventory")）
CREATE TABLE IF NOT EXISTS amz_fba_inventory (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shop_id BIGINT NOT NULL COMMENT '店铺ID',
    marketplace_id VARCHAR(20) NOT NULL COMMENT 'Marketplace ID',
    sku VARCHAR(50) NOT NULL COMMENT '卖家 SKU',
    asin VARCHAR(20) COMMENT 'ASIN',
    fn_sku VARCHAR(50) COMMENT 'Fulfillment Network SKU',
    product_name VARCHAR(500) COMMENT '商品名',
    available_quantity INT DEFAULT 0 COMMENT '可售库存',
    unfulfillable_quantity INT DEFAULT 0 COMMENT '不可售库存',
    inbound_working INT DEFAULT 0 COMMENT '在途入库',
    inbound_shipped INT DEFAULT 0 COMMENT '已发货入库',
    last_updated_time DATETIME COMMENT 'Amazon 最后更新时间',
    sync_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '本地同步时间',
    days_of_supply DECIMAL(8,2) COMMENT '库存可供天数 DOS',
    avg_7_days DECIMAL(10,2) COMMENT '7 天日均销量',
    avg_30_days DECIMAL(10,2) COMMENT '30 天日均销量',
    health_status VARCHAR(20) DEFAULT 'HEALTHY' COMMENT 'URGENT/AT_RISK/HEALTHY/OVERSTOCK/STOCKOUT',
    UNIQUE KEY uk_shop_market_sku (shop_id, marketplace_id, sku),
    INDEX idx_health (health_status),
    INDEX idx_shop (shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='FBA 库存主表';

-- 商品销量统计（对应 com.amz.model.ProductSalesStats @TableName("amz_product_sales_stats")）
CREATE TABLE IF NOT EXISTS amz_product_sales_stats (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shop_id BIGINT NOT NULL,
    sku VARCHAR(50) NOT NULL,
    stat_date DATE NOT NULL COMMENT '统计日期',
    qty_1_day INT DEFAULT 0 COMMENT '当日销量',
    qty_7_days INT DEFAULT 0 COMMENT '7 天累计',
    qty_30_days INT DEFAULT 0 COMMENT '30 天累计',
    qty_90_days INT DEFAULT 0 COMMENT '90 天累计',
    UNIQUE KEY uk_shop_sku_date (shop_id, sku, stat_date),
    INDEX idx_date (stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品销量统计';

-- 同步日志（对应 com.amz.model.InventorySyncLog @TableName("amz_inventory_sync_log")）
CREATE TABLE IF NOT EXISTS amz_inventory_sync_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shop_id BIGINT NOT NULL,
    sync_type VARCHAR(20) NOT NULL COMMENT 'INVENTORY/ORDERS',
    status VARCHAR(20) NOT NULL COMMENT 'SUCCESS/FAILED',
    records_synced INT DEFAULT 0,
    error_message TEXT,
    start_time DATETIME,
    end_time DATETIME,
    INDEX idx_shop_time (shop_id, start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同步日志';

-- 补货建议（对应 com.amz.model.ReplenishmentSuggestion @TableName("amz_replenishment_suggestion")）
CREATE TABLE IF NOT EXISTS amz_replenishment_suggestion (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shop_id BIGINT NOT NULL,
    sku VARCHAR(50) NOT NULL,
    asin VARCHAR(20),
    stat_date DATE NOT NULL COMMENT '建议生成日期',
    current_total_stock INT NOT NULL COMMENT '当前总库存',
    baseline_demand DECIMAL(10,2) COMMENT '基线需求（加权日均）',
    safety_factor DECIMAL(4,2) COMMENT '安全系数',
    seasonal_index DECIMAL(4,2) COMMENT '季节性指数',
    promotion_multiplier DECIMAL(4,2) COMMENT '促销乘数',
    suggested_replenish_qty INT COMMENT '建议补货量',
    estimated_stockout_date DATE COMMENT '预计断货日期',
    urgency_level VARCHAR(20) COMMENT 'URGENT/NORMAL/LOW',
    UNIQUE KEY uk_shop_sku_date (shop_id, sku, stat_date),
    INDEX idx_urgency (urgency_level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='补货建议';

-- 销售历史（对应 com.amz.model.SalesHistory @TableName("amz_sales_history")）
CREATE TABLE IF NOT EXISTS amz_sales_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shop_id BIGINT NOT NULL,
    sku VARCHAR(50) NOT NULL,
    sale_date DATE NOT NULL,
    quantity INT DEFAULT 0,
    revenue DECIMAL(12,2) DEFAULT 0,
    UNIQUE KEY uk_shop_sku_date (shop_id, sku, sale_date),
    INDEX idx_date (sale_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售历史（按日）';

-- 季节性指数表（对应 com.amz.model.SeasonalIndex @TableName("amz_seasonal_index")）
CREATE TABLE IF NOT EXISTS amz_seasonal_index (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    category VARCHAR(50) NOT NULL COMMENT '类目',
    month INT NOT NULL COMMENT '月份 1-12',
    seasonal_index DECIMAL(4,2) NOT NULL COMMENT '季节性指数 1.0=正常',
    UNIQUE KEY uk_category_month (category, month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='季节性指数表';

-- 促销日历（对应 com.amz.model.PromotionCalendar @TableName("amz_promotion_calendar")）
CREATE TABLE IF NOT EXISTS amz_promotion_calendar (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    promotion_name VARCHAR(100) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    multiplier DECIMAL(4,2) NOT NULL COMMENT '促销乘数 2.5-3.0',
    region VARCHAR(20) COMMENT 'NA/EU/FE/ALL',
    UNIQUE KEY uk_name (promotion_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='促销日历';

-- 店铺 SP-API 凭证表（对应 com.amz.model.ShopCredentialEntity @TableName("amz_shop_credential")）
-- 敏感字段（client_secret / refresh_token / access_key / secret_key）均以 AES-256-GCM 密文存储，
-- 列名以 _encrypted 结尾；加解密由应用层 CryptoUtil 负责，DB 仅保存密文。
-- 主键 shop_id 由业务层提供（IdType.INPUT），不自增。
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


-- =============================================================================
-- 6. amz_ad 库：广告活动 / 关键词 / 分时调价 / SB/SD/DSP 扩展
-- =============================================================================
USE amz_ad;

-- 广告活动表（对应 com.amz.model.AdCampaign @TableName("amz_ad_campaign")）
CREATE TABLE IF NOT EXISTS amz_ad_campaign (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    campaign_id VARCHAR(64) NOT NULL COMMENT 'Amazon 广告活动 ID',
    shop_id BIGINT NOT NULL COMMENT '所属店铺 ID',
    name VARCHAR(200) NOT NULL COMMENT '活动名称',
    campaign_type VARCHAR(10) NOT NULL COMMENT 'SP/SB/SD',
    state VARCHAR(10) DEFAULT 'ENABLED' COMMENT 'ENABLED/PAUSED/ARCHIVED',
    daily_budget DECIMAL(10,2) DEFAULT 0 COMMENT '日预算（美元）',
    bidding_strategy VARCHAR(30) DEFAULT 'LEGACY_FOR_SALES',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_campaign (shop_id, campaign_id),
    INDEX idx_shop (shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='广告活动表';

-- 广告关键词表（对应 com.amz.model.AdKeyword @TableName("amz_ad_keyword")）
CREATE TABLE IF NOT EXISTS amz_ad_keyword (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    campaign_id VARCHAR(64) NOT NULL COMMENT '所属广告活动 ID',
    shop_id BIGINT NOT NULL,
    keyword VARCHAR(200) NOT NULL COMMENT '关键词文本',
    match_type VARCHAR(10) DEFAULT 'EXACT' COMMENT 'EXACT/PHRASE/BROAD',
    bid DECIMAL(10,2) DEFAULT 0 COMMENT '当前竞价（美元）',
    state VARCHAR(10) DEFAULT 'ENABLED',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_campaign (campaign_id),
    INDEX idx_shop (shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='广告关键词表';

-- 分时调价规则表（对应 com.amz.model.BidSchedule @TableName("amz_ad_bid_schedule")）
CREATE TABLE IF NOT EXISTS amz_ad_bid_schedule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    campaign_id VARCHAR(64) DEFAULT NULL COMMENT '活动 ID，null 表示作用于该店铺所有活动',
    start_hour TINYINT NOT NULL COMMENT '生效起始小时 0-23',
    end_hour TINYINT NOT NULL COMMENT '生效结束小时 0-23（含）',
    multiplier DECIMAL(5,2) NOT NULL DEFAULT 1.00 COMMENT '竞价倍率：0.7=降30%，1.5=加50%',
    enabled TINYINT DEFAULT 1 COMMENT '1=启用 0=停用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop_enabled (shop_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分时调价规则表';

-- 广告活动扩展表（对应 com.amz.model.AdCampaignExt @TableName("amz_ad_campaign_ext")）
CREATE TABLE IF NOT EXISTS amz_ad_campaign_ext (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    campaign_id VARCHAR(50) NOT NULL,
    campaign_name VARCHAR(200) NOT NULL,
    ad_type VARCHAR(20) NOT NULL COMMENT 'SP/SB/SD/DSP',
    campaign_type VARCHAR(20) COMMENT 'SPONSORED_PRODUCTS/SPONSORED_BRANDS/SPONSORED_DISPLAY/DEMAND_SIDE_PLATFORM',
    budget DECIMAL(10,2),
    budget_type VARCHAR(20) COMMENT 'DAILY/LIFETIME',
    bidding_strategy VARCHAR(30) COMMENT 'LEGACY_SUGGESTED_FOR_SALES/AGGRESSIVE/DOWN_ONLY/UP_AND_DOWN',
    status VARCHAR(20) DEFAULT 'ENABLED',
    start_date DATE,
    end_date DATE,
    impressions BIGINT DEFAULT 0,
    clicks BIGINT DEFAULT 0,
    spend DECIMAL(10,2) DEFAULT 0,
    sales DECIMAL(10,2) DEFAULT 0,
    orders INT DEFAULT 0,
    acos DECIMAL(5,2) DEFAULT 0,
    roas DECIMAL(5,2) DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop_type (shop_id, ad_type),
    INDEX idx_campaign_id (campaign_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='广告活动扩展表(支持SB/SD/DSP)';

-- SB 广告素材表（对应 com.amz.model.AdCreative @TableName("amz_ad_creative")）
CREATE TABLE IF NOT EXISTS amz_ad_creative (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    campaign_id VARCHAR(50) NOT NULL,
    creative_type VARCHAR(20) COMMENT 'Video/IMAGE/STORE_SPOTLIGHT/CUSTOM_HEADLINE',
    headline VARCHAR(200),
    brand_name VARCHAR(100),
    logo_url VARCHAR(500),
    video_url VARCHAR(500),
    landing_page_url VARCHAR(500),
    asin VARCHAR(20) COMMENT '关联 ASIN',
    status VARCHAR(20) DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_campaign (campaign_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SB 广告素材表';

-- SD 受众定向表（对应 com.amz.model.AdTargeting @TableName("amz_ad_targeting")）
CREATE TABLE IF NOT EXISTS amz_ad_targeting (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    campaign_id VARCHAR(50) NOT NULL,
    targeting_type VARCHAR(30) COMMENT 'CONTEXTUAL/REMARKETING/AUDIENCE/LOOKALIKE',
    targeting_value VARCHAR(200) COMMENT 'ASIN/CATEGORY/INTEREST',
    bid DECIMAL(8,2),
    impressions BIGINT DEFAULT 0,
    clicks BIGINT DEFAULT 0,
    spend DECIMAL(10,2) DEFAULT 0,
    sales DECIMAL(10,2) DEFAULT 0,
    INDEX idx_campaign_type (campaign_id, targeting_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SD 受众定向表';


-- =============================================================================
-- 7. amz_procurement 库：采购单 / 质检单
-- =============================================================================
USE amz_procurement;

-- 采购订单表（对应 com.amz.model.PurchaseOrder @TableName("amz_purchase_order")）
CREATE TABLE IF NOT EXISTS amz_purchase_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(32) NOT NULL UNIQUE COMMENT '采购单号（业务唯一）',
    shop_id BIGINT NOT NULL COMMENT '所属店铺 ID',
    supplier_offer_id VARCHAR(64) DEFAULT NULL COMMENT '1688 供应商 offerId',
    supplier_name VARCHAR(200) DEFAULT NULL COMMENT '供应商名称',
    sku VARCHAR(64) NOT NULL COMMENT '采购商品 SKU',
    quantity INT NOT NULL COMMENT '采购数量',
    unit_price DECIMAL(10,2) NOT NULL COMMENT '采购单价（含税 CNY）',
    total_amount DECIMAL(12,2) DEFAULT NULL COMMENT '总金额（CNY）',
    status VARCHAR(20) DEFAULT 'DRAFT' COMMENT 'DRAFT/SUBMITTED/PAID/PRODUCING/SHIPPED/QC_PENDING/QC_PASSED/QC_FAILED/RECEIVED/COMPLETED/CANCELED',
    alibaba_order_no VARCHAR(64) DEFAULT NULL COMMENT '1688 平台订单号',
    expected_delivery_date VARCHAR(20) DEFAULT NULL COMMENT '预计交期',
    tracking_no VARCHAR(64) DEFAULT NULL COMMENT '物流单号',
    remark VARCHAR(500) DEFAULT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop (shop_id),
    INDEX idx_status (status),
    INDEX idx_alibaba (alibaba_order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购订单表';

-- 质检单表（对应 com.amz.model.QualityCheck @TableName("amz_quality_check")）
CREATE TABLE IF NOT EXISTS amz_quality_check (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    purchase_order_id BIGINT NOT NULL COMMENT '关联采购单 ID',
    sample_count INT NOT NULL COMMENT '抽检总数',
    passed_count INT DEFAULT NULL COMMENT '合格数',
    failed_count INT DEFAULT 0 COMMENT '不合格数',
    pass_rate DECIMAL(5,2) DEFAULT NULL COMMENT '合格率（%）',
    defect_description VARCHAR(500) DEFAULT NULL COMMENT '缺陷类型描述',
    result VARCHAR(15) DEFAULT NULL COMMENT 'PASS/FAIL/CONDITIONAL',
    inspector VARCHAR(50) DEFAULT NULL COMMENT '质检员',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_purchase (purchase_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质检单表';


-- =============================================================================
-- 8. amz_customer 库：客服工单 / 索评请求
-- =============================================================================
USE amz_customer;

-- 客服工单表（对应 com.amz.model.CustomerTicket @TableName("amz_customer_ticket")）
CREATE TABLE IF NOT EXISTS amz_customer_ticket (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '所属店铺 ID',
    amazon_order_id VARCHAR(32) DEFAULT NULL COMMENT 'Amazon 订单号',
    buyer_id VARCHAR(64) DEFAULT NULL COMMENT '买家 ID',
    buyer_name VARCHAR(100) DEFAULT NULL COMMENT '买家昵称',
    channel VARCHAR(15) DEFAULT NULL COMMENT 'MESSAGE/REVIEW/RETURN/A_TO_Z',
    content TEXT COMMENT '买家原始消息内容',
    category VARCHAR(20) DEFAULT NULL COMMENT 'SHIPPING/PRODUCT_QUALITY/RETURN_REFUND/INVOICE/OTHER',
    priority VARCHAR(10) DEFAULT 'NORMAL' COMMENT 'URGENT/HIGH/NORMAL/LOW',
    sentiment VARCHAR(10) DEFAULT 'NEUTRAL' COMMENT 'POSITIVE/NEUTRAL/NEGATIVE/ANGRY',
    status VARCHAR(15) DEFAULT 'PENDING' COMMENT 'PENDING/ASSIGNED/REPLIED/RESOLVED/ESCALATED',
    reply TEXT COMMENT '客服回复内容',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop (shop_id),
    INDEX idx_status (status),
    INDEX idx_category (category),
    INDEX idx_priority (priority)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服工单表';

-- 索评请求表（对应 com.amz.model.ReviewSolicitation @TableName("amz_review_solicitation")）
CREATE TABLE IF NOT EXISTS amz_review_solicitation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    amazon_order_id VARCHAR(32) NOT NULL COMMENT 'Amazon 订单号',
    asin VARCHAR(15) NOT NULL COMMENT '商品 ASIN',
    status VARCHAR(15) DEFAULT 'PENDING' COMMENT 'PENDING/SENT/FAILED/OPTED_OUT',
    channel VARCHAR(20) DEFAULT 'OFFICIAL_BUTTON' COMMENT 'OFFICIAL_BUTTON/EMAIL',
    failure_reason VARCHAR(500) DEFAULT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_order (shop_id, amazon_order_id),
    INDEX idx_shop (shop_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='索评请求表';


-- =============================================================================
-- 9. amz_logistics 库：货件 / 轨迹 / 仓库 / 库存 / 入库单 / 出库单
-- =============================================================================
USE amz_logistics;

-- 头程物流单 / FBA 货件表（对应 com.amz.model.Shipment @TableName("amz_shipment")）
CREATE TABLE IF NOT EXISTS amz_shipment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shipment_no VARCHAR(32) NOT NULL UNIQUE COMMENT '货件编号（业务唯一）',
    fba_shipment_id VARCHAR(32) DEFAULT NULL COMMENT 'Amazon FBA shipmentId',
    shop_id BIGINT NOT NULL COMMENT '所属店铺 ID',
    shipping_method VARCHAR(10) DEFAULT NULL COMMENT 'SEA/AIR/EXPRESS/TRUCK',
    carrier VARCHAR(50) DEFAULT NULL COMMENT '承运商',
    master_tracking_no VARCHAR(64) DEFAULT NULL COMMENT '主运单号',
    origin_port VARCHAR(100) DEFAULT NULL COMMENT '起运港口/城市',
    destination_port VARCHAR(100) DEFAULT NULL COMMENT '目的港口/城市',
    fba_warehouse_address VARCHAR(300) DEFAULT NULL COMMENT 'FBA 仓库地址',
    box_count INT DEFAULT 0 COMMENT '货物箱数',
    weight DECIMAL(10,2) DEFAULT NULL COMMENT '货物重量（kg）',
    freight_cost DECIMAL(10,2) DEFAULT NULL COMMENT '运费（USD）',
    status VARCHAR(15) DEFAULT 'CREATED' COMMENT 'CREATED/IN_TRANSIT/CUSTOMS/DELIVERED/RECEIVED/CLOSED/DELAYED/EXCEPTION',
    eta VARCHAR(20) DEFAULT NULL COMMENT '预计到港日期',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop (shop_id),
    INDEX idx_status (status),
    INDEX idx_fba (fba_shipment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='头程物流单 / FBA 货件表';

-- 物流轨迹点表（对应 com.amz.model.TrackingEvent @TableName("amz_tracking_event")）
CREATE TABLE IF NOT EXISTS amz_tracking_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shipment_id BIGINT NOT NULL COMMENT '关联货件 ID',
    event_status VARCHAR(25) DEFAULT NULL COMMENT 'CREATED/DEPARTED/IN_TRANSIT/CUSTOMS_CLEARANCE/ARRIVED/OUT_FOR_DELIVERY/DELIVERED/EXCEPTION',
    location VARCHAR(200) DEFAULT NULL COMMENT '事件发生地点',
    description VARCHAR(500) DEFAULT NULL COMMENT '事件描述',
    event_time VARCHAR(25) DEFAULT NULL COMMENT '事件发生时间',
    longitude DOUBLE DEFAULT NULL COMMENT '经度（轨迹可视化）',
    latitude DOUBLE DEFAULT NULL COMMENT '纬度（轨迹可视化）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shipment (shipment_id),
    INDEX idx_event_time (event_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物流轨迹点表';

-- 海外仓表（对应 com.amz.model.Warehouse @TableName("amz_warehouse")）
CREATE TABLE IF NOT EXISTS amz_warehouse (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    warehouse_name VARCHAR(100) NOT NULL,
    warehouse_code VARCHAR(20) NOT NULL,
    warehouse_type VARCHAR(20) DEFAULT 'THIRD_PARTY' COMMENT 'FBA/AWD/THIRD_PARTY',
    country VARCHAR(10) NOT NULL,
    city VARCHAR(50),
    address VARCHAR(500),
    contact_name VARCHAR(50),
    contact_phone VARCHAR(30),
    capacity_cbm DECIMAL(10,2) COMMENT '容量(立方米)',
    used_cbm DECIMAL(10,2) DEFAULT 0,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop_type (shop_id, warehouse_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='海外仓表';

-- 仓库库存表（对应 com.amz.model.WarehouseInventory @TableName("amz_warehouse_inventory")）
CREATE TABLE IF NOT EXISTS amz_warehouse_inventory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    warehouse_id BIGINT NOT NULL,
    shop_id BIGINT NOT NULL,
    sku VARCHAR(50) NOT NULL,
    asin VARCHAR(20),
    quantity INT DEFAULT 0,
    reserved_quantity INT DEFAULT 0 COMMENT '预留数量(已分配订单)',
    available_quantity INT GENERATED ALWAYS AS (quantity - reserved_quantity) STORED COMMENT '可用数量',
    inbound_quantity INT DEFAULT 0 COMMENT '在途数量',
    location_code VARCHAR(50) COMMENT '库位码',
    batch_no VARCHAR(50) COMMENT '批次号',
    expire_date DATE COMMENT '过期日期',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_warehouse_sku (warehouse_id, sku),
    INDEX idx_shop_sku (shop_id, sku)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库库存表';

-- 入库单（对应 com.amz.model.InboundOrder @TableName("amz_inbound_order")）
CREATE TABLE IF NOT EXISTS amz_inbound_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    inbound_no VARCHAR(50) NOT NULL UNIQUE,
    source VARCHAR(20) COMMENT 'FBA_TRANSFER/1688_PURCHASE/OTHER',
    reference_no VARCHAR(50) COMMENT '关联单号(采购单/FBA货件)',
    status VARCHAR(20) DEFAULT 'PENDING' COMMENT 'PENDING/IN_TRANSIT/RECEIVED/PARTIAL/CANCELLED',
    total_items INT DEFAULT 0,
    received_items INT DEFAULT 0,
    expected_arrival DATE,
    actual_arrival DATETIME,
    remark TEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop_status (shop_id, status),
    INDEX idx_inbound_no (inbound_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='入库单';

-- 出库单（对应 com.amz.model.OutboundOrder @TableName("amz_outbound_order")）
CREATE TABLE IF NOT EXISTS amz_outbound_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    outbound_no VARCHAR(50) NOT NULL UNIQUE,
    order_type VARCHAR(20) COMMENT 'ORDER/TRANSFER/RETURN/SCRAP',
    reference_no VARCHAR(50),
    status VARCHAR(20) DEFAULT 'PENDING' COMMENT 'PENDING/PICKING/PACKED/SHIPPED/CANCELLED',
    carrier VARCHAR(50),
    tracking_no VARCHAR(100),
    total_items INT DEFAULT 0,
    shipped_items INT DEFAULT 0,
    ship_date DATETIME,
    remark TEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop_status (shop_id, status),
    INDEX idx_outbound_no (outbound_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出库单';


-- =============================================================================
-- 10. amz_ops 库：差评监控 / 跟卖监控 / 关键词排名 / 选品 / 关键词调研
-- =============================================================================
USE amz_ops;

-- 差评监控告警表（对应 com.amz.model.NegativeReviewAlert @TableName("amz_negative_review_alert")）
CREATE TABLE IF NOT EXISTS amz_negative_review_alert (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    asin VARCHAR(15) NOT NULL COMMENT '商品 ASIN',
    review_id VARCHAR(32) DEFAULT NULL COMMENT 'Amazon 评论 ID',
    rating TINYINT DEFAULT NULL COMMENT '评分 1-5',
    title VARCHAR(500) DEFAULT NULL,
    content TEXT,
    reviewer VARCHAR(100) DEFAULT NULL,
    status VARCHAR(10) DEFAULT 'NEW' COMMENT 'NEW/HANDLED/IGNORED',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop (shop_id),
    INDEX idx_status (status),
    INDEX idx_asin (asin)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='差评监控告警表';

-- 跟卖监控告警表（对应 com.amz.model.HijackAlert @TableName("amz_hijack_alert")）
CREATE TABLE IF NOT EXISTS amz_hijack_alert (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    asin VARCHAR(15) NOT NULL COMMENT '被跟卖的商品 ASIN',
    hijacker_seller_id VARCHAR(64) DEFAULT NULL COMMENT '跟卖卖家 ID',
    hijacker_name VARCHAR(200) DEFAULT NULL COMMENT '跟卖卖家名称',
    hijack_price DECIMAL(10,2) DEFAULT NULL COMMENT '跟卖价格',
    buy_box_taken TINYINT DEFAULT 0 COMMENT '是否抢走购物车：1=是 0=否',
    status VARCHAR(10) DEFAULT 'NEW' COMMENT 'NEW/HANDLED/IGNORED',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop (shop_id),
    INDEX idx_status (status),
    INDEX idx_asin (asin)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跟卖监控告警表';

-- 关键词排名追踪表（对应 com.amz.model.KeywordRankRecord @TableName("amz_keyword_rank")）
CREATE TABLE IF NOT EXISTS amz_keyword_rank (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    keyword VARCHAR(200) NOT NULL COMMENT '追踪关键词',
    asin VARCHAR(15) NOT NULL COMMENT '商品 ASIN',
    rank INT DEFAULT NULL COMMENT '自然排名位置（1=首页第1名）',
    marketplace VARCHAR(5) DEFAULT 'US' COMMENT '搜索站点',
    capture_time VARCHAR(25) DEFAULT NULL COMMENT '抓取时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop (shop_id),
    INDEX idx_keyword (keyword),
    INDEX idx_asin (asin),
    INDEX idx_capture (capture_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关键词排名追踪表';

-- 选品分析结果表（对应 com.amz.model.SelectionOpportunity @TableName("amz_selection_opportunity")）
CREATE TABLE IF NOT EXISTS amz_selection_opportunity (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    asin VARCHAR(20) NOT NULL,
    title VARCHAR(500),
    category VARCHAR(200),
    marketplace VARCHAR(10) DEFAULT 'US',
    avg_price DECIMAL(10,2) COMMENT '平均售价',
    avg_reviews INT COMMENT '平均评论数',
    avg_rating DECIMAL(2,1) COMMENT '平均评分',
    search_volume INT COMMENT '月搜索量',
    competitor_count INT COMMENT '竞品数量',
    review_barrier VARCHAR(20) COMMENT '评论壁垒 LOW/MEDIUM/HIGH',
    opportunity_score DECIMAL(3,1) COMMENT '机会评分 0-100',
    trend_30d VARCHAR(20) COMMENT 'UP/FLAT/DOWN',
    trend_90d VARCHAR(20),
    ai_summary TEXT COMMENT 'AI 分析摘要',
    ai_suggestion TEXT COMMENT 'AI 建议',
    status VARCHAR(20) DEFAULT 'ANALYZED',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop_asin (shop_id, asin),
    INDEX idx_opportunity_score (opportunity_score DESC),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='选品分析结果表';

-- 关键词调研表（对应 com.amz.model.KeywordResearch @TableName("amz_keyword_research")）
CREATE TABLE IF NOT EXISTS amz_keyword_research (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    keyword VARCHAR(200) NOT NULL,
    marketplace VARCHAR(10) DEFAULT 'US',
    search_volume INT COMMENT '月搜索量',
    click_share DECIMAL(5,2) COMMENT '点击份额%',
    conversion_share DECIMAL(5,2) COMMENT '转化份额%',
    top_asin VARCHAR(20) COMMENT 'Top 3 ASIN',
    difficulty_score DECIMAL(3,1) COMMENT '竞争难度 0-100',
    recommended_bid DECIMAL(8,2) COMMENT '建议竞价',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop_keyword (shop_id, keyword),
    INDEX idx_search_volume (search_volume DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关键词调研表';


-- =============================================================================
-- 11. amz_finance 库：会计凭证
-- =============================================================================
USE amz_finance;

-- 会计凭证表（对应 com.amz.model.AccountingVoucher @TableName("amz_accounting_voucher")）
CREATE TABLE IF NOT EXISTS amz_accounting_voucher (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    voucher_no VARCHAR(32) NOT NULL UNIQUE COMMENT '凭证编号',
    shop_id BIGINT NOT NULL COMMENT '所属店铺 ID',
    biz_date VARCHAR(20) DEFAULT NULL COMMENT '业务日期',
    summary VARCHAR(200) DEFAULT NULL COMMENT '摘要',
    debit_account VARCHAR(20) DEFAULT NULL COMMENT '借方科目代码',
    credit_account VARCHAR(20) DEFAULT NULL COMMENT '贷方科目代码',
    original_amount DECIMAL(12,2) DEFAULT NULL COMMENT '原币金额',
    currency VARCHAR(5) DEFAULT 'USD' COMMENT '原币币种',
    exchange_rate DECIMAL(10,4) DEFAULT 1.0000 COMMENT '汇率（原币→CNY）',
    cny_amount DECIMAL(12,2) DEFAULT NULL COMMENT '本位币金额（CNY）',
    source_type VARCHAR(20) DEFAULT NULL COMMENT 'ORDER/PROCUREMENT/PLATFORM_FEE/REFUND',
    source_no VARCHAR(64) DEFAULT NULL COMMENT '关联业务单号',
    kingdee_sync_status VARCHAR(10) DEFAULT 'PENDING' COMMENT 'PENDING/SYNCED/FAILED',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop (shop_id),
    INDEX idx_source (source_type),
    INDEX idx_biz_date (biz_date),
    INDEX idx_kingdee (kingdee_sync_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会计凭证表';


-- =============================================================================
-- 12. amz_multiplatform 库：多平台统一订单
-- =============================================================================
USE amz_multiplatform;

-- 多平台统一订单表（对应 com.amz.model.UnifiedOrder @TableName("amz_unified_order")）
CREATE TABLE IF NOT EXISTS amz_unified_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    unified_order_no VARCHAR(32) NOT NULL UNIQUE COMMENT '统一订单号（UO 前缀）',
    platform VARCHAR(10) NOT NULL COMMENT '来源平台：TEMU/TIKTOK/SHEIN',
    platform_order_no VARCHAR(64) NOT NULL COMMENT '平台原始订单号',
    shop_id BIGINT NOT NULL COMMENT '所属店铺 ID',
    buyer_nickname VARCHAR(64) DEFAULT NULL COMMENT '买家昵称',
    ship_country VARCHAR(5) DEFAULT NULL COMMENT '收件国家（ISO 2 位）',
    sku VARCHAR(64) DEFAULT NULL COMMENT '商品 SKU',
    product_name VARCHAR(200) DEFAULT NULL COMMENT '商品名称',
    quantity INT DEFAULT NULL COMMENT '购买数量',
    original_amount DECIMAL(12,2) DEFAULT NULL COMMENT '订单金额（原币种）',
    currency VARCHAR(5) DEFAULT 'USD' COMMENT '币种代码',
    cny_amount DECIMAL(12,2) DEFAULT NULL COMMENT '折算人民币金额',
    status VARCHAR(15) DEFAULT 'UNPAID' COMMENT 'UNPAID/PAID/SHIPPED/DELIVERED/COMPLETED/CANCELED/REFUNDED',
    tracking_no VARCHAR(64) DEFAULT NULL COMMENT '平台物流单号',
    order_create_time VARCHAR(30) DEFAULT NULL COMMENT '平台下单时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_platform_order (platform, platform_order_no),
    INDEX idx_shop (shop_id),
    INDEX idx_platform (platform),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='多平台统一订单表';


-- =============================================================================
-- 13. amz_ai 库：用户偏好 / 对话记忆
-- =============================================================================
USE amz_ai;

-- 用户偏好表（对应 com.amz.model.UserPreference @TableName("amz_user_preference")）
CREATE TABLE IF NOT EXISTS amz_user_preference (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE COMMENT '用户 ID',
    nickname VARCHAR(64) DEFAULT NULL COMMENT '用户昵称',
    preferred_shop_id BIGINT DEFAULT 1 COMMENT '偏好店铺 ID（默认）',
    preferred_shop_name VARCHAR(100) DEFAULT NULL COMMENT '偏好店铺名称',
    preferred_category VARCHAR(50) DEFAULT NULL COMMENT '关注品类',
    language VARCHAR(5) DEFAULT 'ZH' COMMENT '语言偏好：ZH/EN/JA/DE',
    last_active_time DATETIME DEFAULT NULL COMMENT '上次活跃时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_last_active (last_active_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户偏好表（Agent 记忆化）';

-- 对话记忆表（对应 com.amz.model.ConversationMemory @TableName("amz_conversation_memory")）
CREATE TABLE IF NOT EXISTS amz_conversation_memory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(32) NOT NULL COMMENT '会话 ID',
    user_id BIGINT NOT NULL COMMENT '用户 ID',
    role VARCHAR(15) NOT NULL COMMENT '角色：user/assistant/tool',
    content TEXT NOT NULL COMMENT '消息内容',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session (session_id),
    INDEX idx_user (user_id),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话记忆表';

-- =============================================================================
-- 全量建表脚本结束
-- =============================================================================
