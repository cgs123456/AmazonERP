-- FBA 签收幂等键：同一货件明细最多绑定一个库存批次。
-- MySQL 唯一索引允许多个 NULL，因此不影响采购入库等非 FBA 批次。
ALTER TABLE amz_inventory_batch
    ADD COLUMN shipment_item_id BIGINT NULL COMMENT 'FBA货件明细ID（签收幂等键）' AFTER inbound_order_id,
    ADD UNIQUE KEY uk_inventory_batch_shipment_item (shipment_item_id);