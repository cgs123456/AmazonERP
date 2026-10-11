-- Flyway Migration V2: 利润明细补退款列（退货退款闭环）
-- Service: amz-service-report
--
-- Why（可核证据：RealtimeProfitServiceImpl 的写死值）
--   `RealtimeProfitServiceImpl.snapshotProfit` 里 `refundCost` 恒为 BigDecimal.ZERO，
--   注释自认「ProfitDetail 无退款字段，退款从 otherFees 中按业务规则拆分，此处暂置为 0」；
--   而 amz_profit_snapshot.refund_cost 列存在（V1），于是净利公式里的退款扣减永远为 0
--   ——「退款损失」这一维在报表里彻底看不到，净利系统性偏高。
--
--   本迁移给明细表补 refund_cost 与 refunded_units 两列：明细是快照的聚合源，
--   退款必须能随明细一起进来，快照才聚合得到真实值。refunded_units 让「退款件数」
--   也能参与 COGS 冲减（只冲收入不冲成本会让售出成本虚高）。
--
-- 兼容性：MySQL 8.0；ADD COLUMN 带默认值 0，对既有行无影响。
-- 回滚：ALTER TABLE amz_profit_detail 去掉 refund_cost / refunded_units 两列。

ALTER TABLE amz_profit_detail
    ADD COLUMN refund_cost DECIMAL(12,2) DEFAULT 0 COMMENT '退款金额（正数表示损失）',
    ADD COLUMN refunded_units INT DEFAULT 0 COMMENT '退款件数（用于冲减 COGS）';
