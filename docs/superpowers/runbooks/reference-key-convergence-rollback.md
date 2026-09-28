# 引用键收敛迁移：应用与回滚 Runbook

## 1. 适用范围

本 Runbook 覆盖 6 个**引用键 / 订单身份收敛**迁移（跨 4 个库/服务，Flyway 版本序）：

| 迁移 | 库 / 服务 | 动作 | 类别 |
|---|---|---|---|
| `V2__order_reference_key_types.sql` | `amz_order` / amz-service-order | `amz_order.product_id` INT→BIGINT；`amz_order.user_id` INT→BIGINT；`amz_shipment_routing.order_id`→`amazon_order_id`（+ `RENAME INDEX idx_order→idx_amazon_order`） | 2 处加宽 + 1 处重命名 |
| `V7__order_number_column_rename.sql` | `amz_finance` / amz-service-finance | `amz_settlement_detail.order_id`→`amazon_order_id`（+ `RENAME INDEX idx_shop_order→idx_shop_amazon_order`）；`amz_payment_collection.order_id`→`amazon_order_id`（+ `RENAME INDEX uk_shop_order→uk_shop_amazon_order`） | 2 处重命名 |
| `V3__platform_message_order_no.sql` | `amz_multiplatform` / amz-service-multiplatform | `amz_platform_message.order_id`→`platform_order_no` | 1 处重命名 |
| `V4__search_term_keyword_key_type.sql` | `amz_ad` / amz-service-ad | `amz_ad_search_term.keyword_id` VARCHAR(50)→BIGINT | 1 处加宽 |
| `V3__order_coupon_key_type.sql` | `amz_order` / amz-service-order | `amz_order.coupon_id` INT→BIGINT（门禁补漏后才暴露） | 1 处加宽 |
| `V4__order_shop_scoped_identity.sql` | `amz_order` / amz-service-order | `uk_amazon_order (amazon_order_id)` → `uk_shop_market_order (shop_id, marketplace_id, amazon_order_id)`；`idx_shop` → `idx_shop_purchase_date (shop_id, purchase_date)` | 订单身份收敛（spec P0-40） |

**配套的另一个迁移不在本 Runbook 范围内**（结构性新增，不是引用键收敛）：
`V5__order_item_table.sql`（`amz_order` / amz-service-order）新增 `amz_order_item` 订单明细行表。
它修的是同一根因的另一半：`amz_order` 只有一组单商品列，存不下多商品订单，而同库的
`amz_profit_report` 唯一键已经是 `(shop_id, amazon_order_id, sku)`（利润层按订单 + SKU 建模）。

**关键前提（不可跳过）**：被重命名的 4 列**不是**类型缺陷，存的是平台/外部订单号字符串
（Amazon 订单号形如 `111-0000000-7599439`；结算报表 `order-id`；外部平台订单号）。
**不得**把它们改成 BIGINT —— 含连字符的真实结算/平台消息数据将直接无法落库。
修的是**列名歧义**（`order_id` 在本仓库其它表里均指 `amz_order.id` BIGINT），不是类型。

## 2. 应用前检查

1. 确认目标库没有真实业务数据，或已取得变更窗口授权。
2. `amz_ad_search_term.keyword_id` 加宽前：存量行必须全部可转为数值。当前无真实数据，
   该检查为「0 行待清洗」；**有真实数据后**必须先跑
   `SELECT COUNT(*) FROM amz_ad_search_term WHERE keyword_id IS NOT NULL AND keyword_id REGEXP '^[0-9]+$' = 0;`
   结果为 0 才能执行。
3. 备份：至少保留逻辑备份（`mysqldump --single-transaction`）+ 迁移前 `SHOW CREATE TABLE` 快照。

## 3. 应用后验证

```bash
# DDL 与仓库声明一致（112 张表 / 14 个库，快照不得漂移）
python tools/synthetic-data/snapshot_schema.py --check

# 引用列与父键类型必须一致（期望 0）
python tools/synthetic-data/verify.py --tier ci
# 期望输出：id types   OK (0 columns reference a parent id with a different DDL type)

# 重命名已生效
SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA IN ('amz_order','amz_finance','amz_multiplatform','amz_ad')
   AND COLUMN_NAME IN ('amazon_order_id','platform_order_no');
```

Java 侧同步改名（与迁移配套，缺一不可）：
`PaymentCollection/SettlementDetail/SettlementRow.amazonOrderId`、`ShipmentRouting.amazonOrderId`、
`PlatformMessage.platformOrderNo`、`AdSearchTerm.keywordId` 由 `String` 改 `Long`。
`SettlementParser.COL_ORDER_ID` 常量保持 `"order-id"`（结算报表列名，不随 DB 列名变化）。

## 4. 回滚边界

- **零真实数据窗口内（当前状态）**：可反向执行第 5 节的反向 SQL，风险为 0。
- **有真实数据后**：**禁止回退 4 处重命名**。理由：重命名后应用代码只写新列名，
  回退列名会让新写入的数据再次落到 `order_id`，而结算对账/平台消息关联已按新列名建立，
  回退即**静默断链**（不报错，只是对不上账）。此时只能**向前修**（修代码/修数据），不能向后退。
- **4 处加宽（BIGINT）**在有真实数据后同样不建议回退：BIGINT→INT 可能溢出报错或截断。

## 5. 反向 SQL（仅限零真实数据窗口）

```sql
-- amz_order
ALTER TABLE amz_order MODIFY COLUMN product_id INT NULL COMMENT '商品ID';
-- V4 反向：订单身份回退为「全局订单号唯一」（仅限零真实数据窗口）
ALTER TABLE amz_order
    DROP INDEX uk_shop_market_order,
    DROP INDEX idx_shop_purchase_date,
    ADD UNIQUE KEY uk_amazon_order (amazon_order_id),
    ADD INDEX idx_shop (shop_id);
-- V5 反向：删掉明细行表（零真实数据窗口内安全；有数据后不可回退）
DROP TABLE IF EXISTS amz_order_item;
ALTER TABLE amz_order MODIFY COLUMN user_id INT NULL COMMENT '下单用户ID';
ALTER TABLE amz_order MODIFY COLUMN coupon_id INT NULL COMMENT '优惠券ID';
ALTER TABLE amz_shipment_routing RENAME INDEX idx_amazon_order TO idx_order;
ALTER TABLE amz_shipment_routing CHANGE COLUMN amazon_order_id order_id VARCHAR(64) NOT NULL COMMENT '订单号';

-- amz_finance
ALTER TABLE amz_settlement_detail RENAME INDEX idx_shop_amazon_order TO idx_shop_order;
ALTER TABLE amz_settlement_detail CHANGE COLUMN amazon_order_id order_id VARCHAR(64) NULL COMMENT '订单号';
ALTER TABLE amz_payment_collection RENAME INDEX uk_shop_amazon_order TO uk_shop_order;
ALTER TABLE amz_payment_collection CHANGE COLUMN amazon_order_id order_id VARCHAR(64) NOT NULL COMMENT '订单号';

-- amz_multiplatform
ALTER TABLE amz_platform_message
    CHANGE COLUMN platform_order_no order_id VARCHAR(100) NULL COMMENT '订单号';

-- amz_ad
ALTER TABLE amz_ad_search_term
    MODIFY COLUMN keyword_id VARCHAR(50) NULL COMMENT '关键词ID';
```

执行反向 SQL 后必须**同步回退 Java 字段改名**，否则应用启动即报错（列不存在）。

## 6. 未闭环项（不属于本 Runbook 可关闭范围）

- ~~`amz_order.uk_amazon_order` 唯一键只含 `amazon_order_id`~~ —— **已由 V4 关闭**：唯一键改为
  `uk_shop_market_order (shop_id, marketplace_id, amazon_order_id)`，`KNOWN_SCHEMA_CONFLICTS` 中的
  P0-40 条目已同步摘除。由此产生的新未闭环项见下面两条。
- **V4 的残留风险（未在本次关闭）**：`marketplace_id` 在本表可空，而 MySQL 唯一索引中 NULL 不参与
  去重，因此「`marketplace_id` 为空 + `shop_id`/`amazon_order_id` 相同」的行仍可能重复写入。
  彻底消除需要把「平台订单」与「本地购物车订单」拆表（规范 §3.1），不在本次零数据窗口迁移范围内。
- **`amz_order_item` 尚无写入方**：V5 只建了表与合成数据，`OrderServiceImpl` / SP-API
  `getOrderItems` 还没有落库路径。也就是说明细表目前是「schema 就位、代码未接」，
  在接上写入方之前它不产生业务价值，也不构成风险。
- 跨店/跨站点同号订单**现在可以被表达，但还没有被生成**：生成器仍然产出全局唯一的
  `amazon_order_id`，所以新的复合唯一键并未被「真有撞值」的数据验证过。要验证需让生成器
  在受控比例下复用订单号（下一轮）。
- 门禁覆盖率本身是风险点：`verify.py` 的 `FK_TARGETS` 原缺 `coupon_id`、`platform_account_id`，导致当时的 `id types OK (0)` 是假阴性；现已补齐。新增引用池时必须同步加入 `FK_TARGETS`，否则会重演。
- 生成器原有静默降级：`generate.py` 的 `fit()` 对超出窄整型上界的值执行 `abs(n) % 2000000000 + 1`，把 BIGINT 父键变成“看起来合理”的假引用；现已改为对 `REF_COLUMNS` 内的引用列直接报错。
- 反向 SQL 未在真实库上执行验证（当前无真实数据），证据等级为静态推演，非 E4/E5。

## 7. 相关事实源

- DDL 快照与门禁：`tools/synthetic-data/schema/SCHEMA_REPORT.md`
- 迁移文件：`amz-service/*/src/main/resources/db/migration/`
- SP-API 首次部署：[`first-deploy-bootstrap-runbook.md`](first-deploy-bootstrap-runbook.md)
- 生产设计事实源：[`../specs/2026-09-24-amazon-erp-production-design.md`](../specs/2026-09-24-amazon-erp-production-design.md)
