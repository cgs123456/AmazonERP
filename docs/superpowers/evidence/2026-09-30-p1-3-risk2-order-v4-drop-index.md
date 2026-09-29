# P1-3 随访：风险 #2 —— order/V4 的不可重入 DROP INDEX（2026-09-30）

> 结论先行：V4 的失败模式已经**量化**，处置写成手册
> `docs/superpowers/runbooks/order-v4-drop-index-repair.md`，并加了契约测试冻结不变式。
> **决定不新增 V6，也不改 V4**，理由在 §5——不是「懒得改」，是改了没有用甚至更糟。
> 风险 #2 从「未评估」变为「已取证 + 有处置手册」；在当前零真实数据窗口内**不需要代码改动**。

## 1. 风险是什么

`V4__order_shop_scoped_identity.sql:35-39` 是一条四合一的 `ALTER TABLE`：

```sql
ALTER TABLE amz_order
    DROP INDEX uk_amazon_order,
    DROP INDEX idx_shop,
    ADD UNIQUE KEY uk_shop_market_order (shop_id, marketplace_id, amazon_order_id),
    ADD INDEX idx_shop_purchase_date (shop_id, purchase_date);
```

MySQL 8 **没有** `DROP INDEX IF EXISTS`，所以这条语句**不可重入**：索引不在就报
`ERROR 1091 Can't DROP '...'; check that column/key exists`，整条 ALTER 失败。

## 2. 实测环境

| 项 | 值 |
|---|---|
| 数据库 | `amz-p13-drill2`（`mysql:8.0` → **MySQL 8.0.46**），`p13net` 内 `172.20.0.3:3306` |
| 客户端 | 容器 `amz-p13-py`（`mysql:8.0` 镜像，`mysql Ver 8.0.46` 客户端） |
| 迁移文件 | `amz-service/amz-service-order/src/main/resources/db/migration/`（V1–V5，共 5 个） |
| 演练库 | `amz_order_pf`（正常路径）、`amz_order_part`（半迁移路径），均由 V1–V3 原始 SQL 建起 |

## 3. 实测结果

### 3.1 正常路径：V1→V3 后执行 V4 —— 成功

```
== V1..V3 后 amz_order 的索引
idx_shop / idx_status / PRIMARY / uk_amazon_order
== 首次执行 V4
rc=0
== V4 后索引
idx_shop_purchase_date / idx_status / PRIMARY / uk_shop_market_order
```

### 3.2 重跑 V4 —— 1091，不可重入坐实

```
ERROR 1091 (42000) at line 35 in file: '/ordsql/V4__order_shop_scoped_identity.sql':
Can't DROP 'uk_amazon_order'; check that column/key exists
```

### 3.3 半迁移状态：旧索引在、新唯一键也在 —— 1061，但原子 DDL 完整回滚

```
== 手工 ADD uk_shop_market_order 之后
idx_shop / idx_status / PRIMARY / uk_amazon_order / uk_shop_market_order
== 执行 V4
ERROR 1061 (42000) at line 35: Duplicate key name 'uk_shop_market_order'
rc=1
== 失败后索引（两个 DROP 被回滚，旧索引仍在）
idx_shop / idx_status / PRIMARY / uk_amazon_order / uk_shop_market_order
== 手工删掉多余的新索引后重跑 V4
rc=0
== 最终索引
idx_shop_purchase_date / idx_status / PRIMARY / uk_shop_market_order
```

**这是本轮最有价值的一条**：MySQL 8.0 的原子 DDL 让 V4 失败时**不会**留下「旧索引被 DROP 掉、
新索引没建成」的残局。所以「失败后修好数据再重跑」这条路是通的——只要没人手工去动索引。

### 3.4 「新唯一键会撞已有重复数据」—— 在旧唯一键还在的库上不可能

原本想造「两条 `(shop_id, marketplace_id, amazon_order_id)` 相同」的数据来触发 1062，结果
**INSERT 先被旧唯一键 `uk_amazon_order` 挡住了**（`Duplicate entry '111-222-333' for key
'amz_order.uk_amazon_order'`）。这本身就是一个结论：

> 只要 `uk_amazon_order` 存在，`amazon_order_id` 全局唯一，是比
> `(shop_id, marketplace_id, amazon_order_id)` **更严**的约束，
> 所以能通过旧约束的数据必然通过新约束，V4 的 `ADD UNIQUE KEY` 不会因数据重复而失败。

1062 只可能出现在「旧唯一键已经不存在」的库上——而那种库 V4 会先在 DROP 那一步 1091 失败。

### 3.5 真实 Flyway 记录（用于手册里的手工修复）

`amz_order_fwit`（AllModules IT 于 2026-09-29 真实跑出的 history）：

| version | script | checksum | success |
|---|---|---|---|
| 1 | `V1__init.sql` | -2089828925 | 1 |
| 2 | `V2__order_reference_key_types.sql` | 1565155136 | 1 |
| 3 | `V3__order_coupon_key_type.sql` | 1261394726 | 1 |
| 4 | `V4__order_shop_scoped_identity.sql` | **-1483237947** | 1 |
| 5 | `V5__order_item_table.sql` | 785591009 | 1 |

手册 §4.B 里「手工补一条 history 行」用的 `-1483237947` 就来自这里，**不是估算值**。

## 4. 本轮产出

| 路径 | 说明 |
|---|---|
| `docs/superpowers/runbooks/order-v4-drop-index-repair.md` | 失败模式、状态判定 SQL、修复路径 A/B、禁止事项 |
| `amz-service/amz-service-spapi/src/test/java/com/amz/deploy/OrderV4IndexMigrationContractTest.java` | 契约测试：冻结「全仓只有 order/V4 一处 `DROP INDEX`」+ V4 内容不变式 + 手册必须存在 |

契约测试三条断言：

1. 全仓 14 个模块的 migration 目录里，出现 `DROP INDEX` 的文件**恰好只有** `order/V4`；
   新增同类语句会失败，逼你先读手册。
2. V4 必须同时 DROP `uk_amazon_order` 与 `idx_shop`、必须 ADD `uk_shop_market_order`
   与 `idx_shop_purchase_date`，且不含 `IF EXISTS`（MySQL 8 不支持，写了只会让人误以为可重入）。
   断言失败信息里写明「改 V4 会让已迁移库 checksum 校验失败」。
3. 修复手册必须存在且包含 `1091` / 两个旧索引名（防止手册被删或改空后没人发现）。

## 5. 为什么不修 V4，也不新增 V6

1. **改 V4 会立刻制造一个更大的问题**：49/49 迁移 blob 当前零 drift，已迁移库（如
   `amz_order_fwit`）记录的 checksum 是 `-1483237947`。改一个字符，这些库下次启动就
   `Migration checksum mismatch` 直接拒绝启动。改 V4 不是「顺手 edit」，是要配一套
   已迁移库 checksum 校正发布方案。
2. **新增 V6 救不了 V4**：V6 版本号大于 V4，**在 V4 之后**执行。V4 失败时 Flyway 停在那里，
   V6 根本跑不到。想让「条件 DROP」生效，它必须排在 V4 之前——那就等于重排版本号，
   等价于改 V4 的部署语义。
3. **MySQL 8 没有 `DROP INDEX IF EXISTS`**，所谓条件删除只能靠查 `information_schema.statistics`
   再 `PREPARE`/`EXECUTE` 拼 DDL。为了一个「只要不手工乱改索引就不会发生」的场景，
   引入动态 DDL 得不偿失。
4. **真实发生条件是人为的**：触发 1091 的前提是「有人手工改过索引」或「恢复备份时弄丢了
   history 行」。这类问题的正确处置是**手册 + 门禁**，不是再加一条迁移。

## 6. 边界：本轮没有证明什么

- 没有在**有真实数据量**的 `amz_order` 上跑过 V4（演练库是空表）。大表上这个 ALTER 的耗时、
  锁与磁盘占用**未测**，生产执行前要单独评估（`ALGORITHM`/`LOCK` 与 pt-osc/gh-ost 的取舍）。
- `amz_order` 库在演练环境里**没有** `flyway_schema_history`（`ERROR 1146`），它属于「存量库」，
  在 `baseline-on-migrate=false` 之后会 fail-fast 拒绝启动——这是风险 #3 的修复生效的表现，
  与 V4 的可重入性是两件事，别混在一起。
- 契约测试跑的是静态断言，不连数据库；真实行为证据来自本文 §3 的实跑。