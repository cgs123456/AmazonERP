-- Flyway Migration V4: 订单身份从「全局订单号」收敛到「店铺 + 站点 + 订单号」
-- Service: amz-service-order
--
-- Why（可核证据：spec P0-40 / generate.py KNOWN_SCHEMA_CONFLICTS P0-40）
--   amz_order 的唯一键曾是 uk_amazon_order (amazon_order_id)，即「一个订单号全局只能有一行」。
--   规范 §7.6 第 1 条要求：同一 amazon_order_id 出现在不同店铺/市场，不能互相覆盖。
--   生成器的 KNOWN_SCHEMA_CONFLICTS（P0-40）显式登记过这个冲突，并写明
--   「跨店重复场景在本 schema 下无法表达，必须先修 schema 才能测」——登记不等于解决。
--
--   不修的真实代价（不是理论风险）：
--     OrderServiceImpl.syncAmazonOrder 的幂等判定是
--       selectCount(eq(Order::getAmazonOrderId, amazonOrderId))   // 不带 shopId
--     一旦订单号在不同店铺/站点间撞值，B 店的订单会因「A 店已存在同号订单」被判为幂等重复而
--     **静默丢弃**——日志写「幂等跳过」，运维看到的是「订单少了一张，但没有报错」。
--     这是多租户 ERP 最坏的一类缺陷：数据丢失且不可观测。
--
--   Amazon 未公开承诺 AmazonOrderId 跨站点全局唯一；即使撞值概率极低，爆炸半径是「静默丢单」，
--   而修的成本只是一个复合唯一键。按规范取 (shop_id, marketplace_id, amazon_order_id)。
--
-- 已知残留风险（诚实记录，不在本迁移声称已解决）：
--   marketplace_id 在本表可空，而 MySQL 唯一索引中 NULL 不参与去重，因此
--   「marketplace_id 为空 + shop_id/amazon_order_id 相同」的行仍可能重复写入。
--   影响面：仅当 SP-API 消息缺失 marketplaceId 时存在。SP-API 路径的
--   OrderUpsertMessage 恒定携带 marketplaceId，购物车下单路径的三列全为 NULL（应按豁免行为）。
--   彻底消除需要把「平台订单」与「本地购物车订单」拆表（规范 §3.1 订单事实表重建），
--   不在本次零真实数据窗口内的低风险迁移范围内。
--
-- 购物车下单路径不受影响：saveOrderInternal 写入的行 shop_id / marketplace_id /
--   amazon_order_id 均为 NULL，MySQL 唯一索引对 NULL 不去重，这类行仍可无限写入（与现状一致）。
--
-- 兼容性：MySQL 8.0（compose/k8s image: mysql:8.0）；不改写已执行的 V1/V2/V3，存量库随本迁移升级。
-- 回滚：见 docs/superpowers/runbooks/reference-key-convergence-rollback.md
--       （仅在零真实数据窗口内可逆；有真实数据后按本迁移方向继续，不再回退）。

ALTER TABLE amz_order
    DROP INDEX uk_amazon_order,
    DROP INDEX idx_shop,
    ADD UNIQUE KEY uk_shop_market_order (shop_id, marketplace_id, amazon_order_id),
    ADD INDEX idx_shop_purchase_date (shop_id, purchase_date);