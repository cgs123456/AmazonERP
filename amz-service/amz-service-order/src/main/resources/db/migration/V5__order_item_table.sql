-- Flyway Migration V5: Amazon 订单明细行（order line）
-- Service: amz-service-order
--
-- Why（可核证据：amz_order 的列集 + amz_profit_report 的唯一键）
--   1. amz_order 只有 product_id / quantity / final_price 一组「单商品」列，
--      且 V4 之前唯一键是 (amazon_order_id) —— 结构上就是「一个 Amazon 订单只能有一行」。
--      真实 Amazon 订单是多商品的（SP-API getOrderItems 返回 OrderItem 列表），
--      当前 schema 存不下第二件商品。
--   2. 同一库内的 amz_profit_report 唯一键是 uk_shop_order_sku (shop_id, amazon_order_id, sku)，
--      即利润层已经按「订单 + SKU」建模。也就是说下游要求明细粒度，而订单表提供不了——
--      这是本仓库内部自证的结构缺口，不是外部猜测。
--   3. amz-service-multiplatform 的 amz_unified_order.items_json 用 TEXT 存明细 JSON 作为变通，
--      JSON 列无法被 SQL 聚合/关联，无法支撑「按 SKU 的销量/利润/退货/FBA 费用」这类
--      ERP 的核心查询。明细必须是行式表。
--
-- 设计约束：
--   - 跨库不建物理外键（amz_order 与 amz_product 是独立 database），引用靠应用层与
--     tools/synthetic-data 的引用门禁保证。
--   - product_id 直接对齐 amz_product.amz_product.id BIGINT（V4 系列已收敛的类型）。
--   - shop_id / amazon_order_id 与 amz_order 同名同类型，保证 uk_shop_amazon_order 语义一致。
--   - 明细行保留 ASIN / seller_sku / title 快照：平台侧数据会被卖家改动，
--     订单行必须冻结下单时的值，否则历史订单会随商品主数据漂移而对不上账。
--
-- 兼容性：MySQL 8.0；仅新增表，不影响既有表与既有数据。
-- 回滚：DROP TABLE amz_order_item;（零真实数据窗口内安全；有数据后不可回退）。

CREATE TABLE IF NOT EXISTS amz_order_item (
    id BIGINT NOT NULL COMMENT '雪花ID',
    shop_id BIGINT NOT NULL COMMENT '店铺ID（对齐 amz_order.shop_id BIGINT）',
    marketplace_id VARCHAR(20) DEFAULT NULL COMMENT '站点 ID',
    amazon_order_id VARCHAR(30) NOT NULL COMMENT 'Amazon 订单号（对齐 amz_order.amazon_order_id）',
    amazon_order_item_id VARCHAR(40) NOT NULL COMMENT 'Amazon OrderItemId（平台侧明细唯一标识）',
    asin VARCHAR(20) DEFAULT NULL COMMENT 'ASIN',
    seller_sku VARCHAR(64) DEFAULT NULL COMMENT '卖家 SKU',
    product_id BIGINT DEFAULT NULL COMMENT '本地商品ID（对齐 amz_product.amz_product.id BIGINT）',
    title VARCHAR(255) DEFAULT NULL COMMENT '商品标题快照（下单时冻结）',
    quantity INT NOT NULL DEFAULT 0 COMMENT '数量',
    item_price DECIMAL(14,2) DEFAULT NULL COMMENT '明细金额（原币种，含税）',
    item_tax DECIMAL(14,2) DEFAULT NULL COMMENT '税金（原币种）',
    promotion_discount DECIMAL(14,2) DEFAULT NULL COMMENT '促销折扣（原币种，正数）',
    currency VARCHAR(8) DEFAULT NULL COMMENT '原币种',
    fulfillment_channel VARCHAR(10) DEFAULT NULL COMMENT 'AFN（FBA）或 MFN（自发货）',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_item (shop_id, amazon_order_id, amazon_order_item_id),
    KEY idx_shop_sku (shop_id, seller_sku),
    KEY idx_asin (asin),
    KEY idx_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Amazon 订单明细行';