-- Amazon ERP 订单表改造 SQL
-- 数据库: amz_order
-- 在原有 rb_order 基础上重命名 + 新增多店铺/Amazon同步字段

CREATE TABLE IF NOT EXISTS amz_order (
    id BIGINT PRIMARY KEY,
    product_id INT COMMENT '产品ID',
    quantity INT COMMENT '商品数量',
    coupon_id INT COMMENT '优惠券ID',
    final_price DECIMAL(10,2) COMMENT '最终价格',
    user_id INT COMMENT '订单归属人ID',
    status INT DEFAULT 0 COMMENT '原状态字段',
    -- Amazon ERP 新增字段
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
