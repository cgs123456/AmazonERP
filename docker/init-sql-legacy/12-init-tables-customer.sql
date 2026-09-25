-- ============================================
-- Amazon ERP 客服工单模块建表脚本
-- 数据库: amz_customer
-- ============================================

CREATE DATABASE IF NOT EXISTS amz_customer DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE amz_customer;

-- 客服工单表
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

-- 索评请求表
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
