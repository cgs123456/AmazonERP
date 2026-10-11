-- Flyway Migration V8: 物料清单（BOM，组合装/套装）
-- Service: amz-service-procurement
--
-- Why（对标同类 ERP material 域）：
--   组合装（买 A 送 B / 套装）在亚马逊是真实商品形态：售出 1 个组合 SKU，
--   实际出库的是 N 个子品。本仓此前无 BOM——配货出库只能按组合 SKU 扣减，
--   而本地仓按子品备货，库存账直接对不上。
--
-- 语义：
--   - composite_sku 是亚马逊上在售的组合 SKU；child_sku 是本地仓实际备货的子品。
--   - 履约出库前按 BOM 展开成子品需求再扣减（层级支持递归，环检测强制）。
--   - 展开只在 order 履约编排里发生（order→procurement Feign），
--     logistics 出库通道保持「给什么扣什么」的无状态语义。
--
-- 兼容性：仅新增表。
-- 回滚：DROP TABLE amz_bom;

CREATE TABLE IF NOT EXISTS amz_bom (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shop_id BIGINT NOT NULL COMMENT '所属店铺 ID',
    composite_sku VARCHAR(100) NOT NULL COMMENT '组合 SKU',
    child_sku VARCHAR(100) NOT NULL COMMENT '子品 SKU（本地仓备货粒度）',
    child_qty INT NOT NULL DEFAULT 1 COMMENT '单份组合包含的子品数量',
    remark VARCHAR(300) DEFAULT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_composite_child (shop_id, composite_sku, child_sku),
    INDEX idx_shop_composite (shop_id, composite_sku)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物料清单（组合装 BOM）';
