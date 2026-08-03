-- ============================================
-- Amazon ERP 客服管理模块升级 SQL
-- 数据库: amz_customer
-- 升级内容：邮件模板/自动化邮件规则/差评监控/RMA/客服KPI
-- ============================================

USE amz_customer;

-- ============================================
-- 1. 邮件模板表
-- ============================================
CREATE TABLE IF NOT EXISTS amz_email_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    template_name VARCHAR(100) NOT NULL COMMENT '模板名称',
    template_type VARCHAR(30) NOT NULL COMMENT 'ORDER_CONFIRMATION/SHIPPING_NOTIFICATION/REVIEW_SOLICITATION/NEGATIVE_REVIEW_FOLLOWUP/RMA_LABEL/CUSTOM',
    subject VARCHAR(500) COMMENT '邮件主题',
    body TEXT NOT NULL COMMENT '邮件正文(支持变量：{buyer_name}/{order_id}/{asin}/{tracking_no}/{review_link})',
    language VARCHAR(10) DEFAULT 'en' COMMENT '语言(en/zh/de/fr/it/es/ja)',
    trigger_event VARCHAR(30) COMMENT 'ORDER_PLACED/ORDER_SHIPPED/ORDER_DELIVERED/NEGATIVE_REVIEW/DAYS_AFTER_DELIVERY',
    trigger_delay_hours INT DEFAULT 0 COMMENT '触发延迟小时数',
    enabled TINYINT DEFAULT 1,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop_type (shop_id, template_type),
    INDEX idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邮件模板表';

-- ============================================
-- 2. 自动化邮件任务表
-- ============================================
CREATE TABLE IF NOT EXISTS amz_email_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    template_id BIGINT COMMENT '关联模板 ID',
    amazon_order_id VARCHAR(32) NOT NULL,
    asin VARCHAR(20),
    buyer_email VARCHAR(200) COMMENT '买家邮箱',
    buyer_name VARCHAR(100),
    subject VARCHAR(500),
    body TEXT COMMENT '渲染后的邮件正文',
    status VARCHAR(20) DEFAULT 'PENDING' COMMENT 'PENDING/SENT/FAILED/OPTED_OUT/SKIPPED',
    scheduled_time DATETIME COMMENT '计划发送时间',
    sent_time DATETIME COMMENT '实际发送时间',
    failure_reason VARCHAR(500),
    source VARCHAR(30) DEFAULT 'AUTO' COMMENT 'AUTO(自动生成)/MANUAL(手动创建)',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_shop_status (shop_id, status),
    INDEX idx_scheduled (scheduled_time),
    INDEX idx_order (amazon_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自动化邮件任务表';

-- ============================================
-- 3. 差评监控表
-- ============================================
CREATE TABLE IF NOT EXISTS amz_negative_review (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    amazon_order_id VARCHAR(32) COMMENT '匹配到的订单号',
    asin VARCHAR(20) NOT NULL,
    reviewer_name VARCHAR(100),
    review_rating INT NOT NULL COMMENT '评分(1-5)',
    review_title VARCHAR(500),
    review_content TEXT,
    review_date DATE COMMENT '留评日期',
    review_id VARCHAR(64) COMMENT 'Amazon Review ID',
    verified_purchase TINYINT DEFAULT 0 COMMENT '是否VP评论',
    status VARCHAR(20) DEFAULT 'DETECTED' COMMENT 'DETECTED/MATCHED/CONTACTED/RESOLVED/IGNORED',
    matched_order_id VARCHAR(32) COMMENT '匹配到的订单号',
    contact_email_task_id BIGINT COMMENT '关联的邮件任务 ID',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop (shop_id),
    INDEX idx_asin (asin),
    INDEX idx_rating (review_rating),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='差评监控表';

-- ============================================
-- 4. RMA 退货标签表
-- ============================================
CREATE TABLE IF NOT EXISTS amz_rma (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    rma_no VARCHAR(32) NOT NULL UNIQUE COMMENT 'RMA 编号',
    amazon_order_id VARCHAR(32) NOT NULL,
    asin VARCHAR(20),
    sku VARCHAR(64),
    return_reason VARCHAR(100) COMMENT '退货原因',
    return_type VARCHAR(20) COMMENT 'REFUND/REPLACE/RETURN',
    product_condition VARCHAR(20) COMMENT 'NEW/USED_DAMAGED/OPENED/UNOPENED',
    refund_amount DECIMAL(10,2),
    label_url VARCHAR(500) COMMENT '退货标签 URL',
    label_cost DECIMAL(10,2) DEFAULT 0 COMMENT '标签费用',
    carrier VARCHAR(50) COMMENT '承运商',
    tracking_no VARCHAR(100) COMMENT '退货物流单号',
    status VARCHAR(20) DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/LABEL_SENT/IN_TRANSIT/RECEIVED/PROCESSED/CANCELLED',
    remark TEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_shop (shop_id),
    INDEX idx_order (amazon_order_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RMA退货标签表';

-- ============================================
-- 5. 客服 KPI 日报表
-- ============================================
CREATE TABLE IF NOT EXISTS amz_customer_service_kpi (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL,
    report_date DATE NOT NULL,
    total_tickets INT DEFAULT 0 COMMENT '工单总数',
    pending_tickets INT DEFAULT 0 COMMENT '待处理数',
    replied_tickets INT DEFAULT 0 COMMENT '已回复数',
    resolved_tickets INT DEFAULT 0 COMMENT '已解决数',
    avg_response_time_hours DECIMAL(10,2) DEFAULT 0 COMMENT '平均响应时间(小时)',
    overdue_tickets INT DEFAULT 0 COMMENT '超时工单数',
    review_solicitations_sent INT DEFAULT 0 COMMENT '索评发送数',
    negative_reviews_detected INT DEFAULT 0 COMMENT '差评检测数',
    negative_reviews_resolved INT DEFAULT 0 COMMENT '差评解决数',
    satisfaction_score DECIMAL(3,1) DEFAULT 0 COMMENT '满意度评分(0-5)',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_shop_date (shop_id, report_date),
    INDEX idx_shop (shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客服KPI日报表';

-- ============================================
-- 示例邮件模板
-- ============================================
INSERT IGNORE INTO amz_email_template (shop_id, template_name, template_type, subject, body, language, trigger_event, trigger_delay_hours, enabled)
VALUES
(1, '订单确认邮件', 'ORDER_CONFIRMATION',
 'Your order {order_id} has been confirmed!',
 'Hi {buyer_name},\n\nThank you for your purchase! Your order {order_id} for ASIN {asin} has been confirmed.\n\nBest regards,\nCustomer Service Team',
 'en', 'ORDER_PLACED', 1, 1),
(1, '发货通知邮件', 'SHIPPING_NOTIFICATION',
 'Your order {order_id} has shipped!',
 'Hi {buyer_name},\n\nGreat news! Your order {order_id} has been shipped.\nTracking number: {tracking_no}\n\nBest regards,\nCustomer Service Team',
 'en', 'ORDER_SHIPPED', 0, 1),
(1, '索评邮件', 'REVIEW_SOLICITATION',
 'How was your experience with {asin}?',
 'Hi {buyer_name},\n\nWe hope you are enjoying your purchase! If you have a moment, we would greatly appreciate it if you could leave a review:\n{review_link}\n\nThank you for your support!\n\nBest regards',
 'en', 'DAYS_AFTER_DELIVERY', 72, 1),
(1, '差评跟进邮件', 'NEGATIVE_REVIEW_FOLLOWUP',
 'We want to make things right - regarding your order {order_id}',
 'Hi {buyer_name},\n\nWe noticed you had a less-than-ideal experience with your recent purchase. We sincerely apologize for any inconvenience.\n\nWe would love the opportunity to make things right. Please reply to this email and let us know how we can help.\n\nIf you feel we have resolved your issue, you can update your review here: {review_link}\n\nBest regards,\nCustomer Service Team',
 'en', 'NEGATIVE_REVIEW', 0, 1);
