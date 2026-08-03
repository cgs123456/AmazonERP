-- Flyway Migration V1: amz_user database initialization
-- Service: amz-service-user
-- Source: docker/init-sql/02, 07, 18, 19

-- ============================================
-- User & Attention Tables (from 02-init-tables-user.sql)
-- ============================================
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
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS amz_attention (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    attention_id BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_attention (user_id, attention_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO amz_user (id, username, password, nickname, image) VALUES
(1, 'testuser', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '测试卖家', 'https://i.pravatar.cc/150?img=1');

-- ============================================
-- Multi-shop RBAC Tables (from 07-init-tables-shop.sql)
-- ============================================
CREATE TABLE IF NOT EXISTS amz_shop (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shop_name VARCHAR(100) NOT NULL COMMENT '店铺名称',
    marketplace_id VARCHAR(20) NOT NULL COMMENT 'Amazon Marketplace ID',
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

-- ============================================
-- Operation Audit Log (from 18-init-tables-oper-log.sql)
-- ============================================
CREATE TABLE IF NOT EXISTS amz_oper_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键 ID',
    user_id BIGINT DEFAULT NULL COMMENT '操作人用户 ID',
    module VARCHAR(64) NOT NULL COMMENT '业务模块名',
    action VARCHAR(64) NOT NULL COMMENT '操作类型',
    description VARCHAR(255) DEFAULT NULL COMMENT '操作描述',
    method VARCHAR(255) NOT NULL COMMENT '执行方法签名',
    params TEXT DEFAULT NULL COMMENT '方法入参（JSON）',
    result TEXT DEFAULT NULL COMMENT '返回值（JSON）',
    ip VARCHAR(64) DEFAULT NULL COMMENT '请求来源 IP',
    status VARCHAR(16) NOT NULL DEFAULT 'SUCCESS' COMMENT '执行状态',
    error_msg TEXT DEFAULT NULL COMMENT '异常信息',
    cost_time BIGINT DEFAULT 0 COMMENT '方法耗时（毫秒）',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    INDEX idx_user_id (user_id),
    INDEX idx_create_time (create_time),
    INDEX idx_module (module)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志审计表';

-- ============================================
-- Field-level Permission (from 19-init-tables-field-permission.sql)
-- ============================================
CREATE TABLE IF NOT EXISTS amz_field_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_code VARCHAR(50) NOT NULL COMMENT '角色代码',
    service_name VARCHAR(50) NOT NULL COMMENT '微服务名',
    entity_name VARCHAR(100) NOT NULL COMMENT '实体名',
    field_name VARCHAR(100) NOT NULL COMMENT '字段名',
    visible TINYINT(1) DEFAULT 0 COMMENT '是否可见',
    UNIQUE KEY uk_role_entity_field (role_code, service_name, entity_name, field_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字段级数据权限规则表';

ALTER TABLE amz_user
    ADD COLUMN IF NOT EXISTS role VARCHAR(50) NOT NULL DEFAULT 'VIEWER' COMMENT '角色 ADMIN/OPERATOR/VIEWER';

UPDATE amz_user SET role = 'ADMIN' WHERE id = 1 AND username = 'testuser';

INSERT IGNORE INTO amz_field_permission (role_code, service_name, entity_name, field_name, visible) VALUES
('VIEWER', 'amz-service-order', 'ProfitReport', 'productCost', 0),
('VIEWER', 'amz-service-order', 'ProfitReport', 'grossProfit', 0),
('VIEWER', 'amz-service-order', 'ProfitReport', 'fbaFulfillmentFee', 0),
('VIEWER', 'amz-service-order', 'ProfitReport', 'referralFee', 0),
('VIEWER', 'amz-service-procurement', 'PurchaseOrder', 'unitPrice', 0),
('VIEWER', 'amz-service-procurement', 'PurchaseOrder', 'totalAmount', 0),
('VIEWER', 'amz-service-finance', 'AccountingVoucher', 'originalAmount', 0),
('VIEWER', 'amz-service-finance', 'AccountingVoucher', 'cnyAmount', 0),
('OPERATOR', 'amz-service-order', 'ProfitReport', 'productCost', 1),
('OPERATOR', 'amz-service-order', 'ProfitReport', 'grossProfit', 1),
('OPERATOR', 'amz-service-procurement', 'PurchaseOrder', 'unitPrice', 1),
('OPERATOR', 'amz-service-procurement', 'PurchaseOrder', 'totalAmount', 1),
('ADMIN', 'amz-service-order', 'ProfitReport', 'productCost', 1),
('ADMIN', 'amz-service-order', 'ProfitReport', 'grossProfit', 1),
('ADMIN', 'amz-service-procurement', 'PurchaseOrder', 'unitPrice', 1),
('ADMIN', 'amz-service-finance', 'AccountingVoucher', 'originalAmount', 1),
('ADMIN', 'amz-service-finance', 'AccountingVoucher', 'cnyAmount', 1);
