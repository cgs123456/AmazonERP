# amz-service-order V4 索引迁移：失败模式与修复手册

适用范围：`amz-service/amz-service-order/src/main/resources/db/migration/V4__order_shop_scoped_identity.sql`。
这份手册只讲一件事——**V4 不可重入，失败后怎么救**，不讲为什么要有这个迁移（原因写在 V4 文件头的注释里）。

## 1. V4 到底做了什么

```sql
ALTER TABLE amz_order
    DROP INDEX uk_amazon_order,
    DROP INDEX idx_shop,
    ADD UNIQUE KEY uk_shop_market_order (shop_id, marketplace_id, amazon_order_id),
    ADD INDEX idx_shop_purchase_date (shop_id, purchase_date);
```

MySQL 8 **没有** `DROP INDEX IF EXISTS`（条件 DDL 只存在于 MariaDB），所以这四条子句要么全成，要么全败。
没有任何一种写法能让它「索引不存在时跳过」。

## 2. 实测结论（MySQL 8.0.46）

证据与原始输出见 `docs/superpowers/evidence/2026-09-30-p1-3-risk2-order-v4-drop-index.md`。

| 场景 | 结果 |
|---|---|
| 正常路径：V1→V3 之后首次执行 V4 | **rc=0**，旧索引消失、新索引建立 |
| 重跑 V4（同一条 ALTER 再执行一次） | **ERROR 1091** `Can't DROP 'uk_amazon_order'; check that column/key exists` |
| 半迁移状态：旧索引在、新唯一键 `uk_shop_market_order` 也已在 | **ERROR 1061** `Duplicate key name 'uk_shop_market_order'`；**原子 DDL 把两个 DROP 一起回滚了**，旧索引仍在 |
| 上一种状态手工删掉冲突的新索引后重跑 V4 | **rc=0** |
| 担心「新唯一键会撞已有重复数据」 | 只要 `uk_amazon_order` 还在，就不会：旧键约束 `amazon_order_id` 全局唯一，是比 `(shop_id, marketplace_id, amazon_order_id)` **更严**的约束，所以能插入的数据必然满足新键。1062 只在旧唯一键已不存在的库上才可能出现 |

关键推论：**在 MySQL 8.0 上，V4 失败不会留下「索引被 DROP 掉一半」的残局**（原子 DDL 回滚）。
真正会卡死的是这两种情况：

1. 有人在库上手工执行过 V4 的 DDL（或等价变更），然后 Flyway 又要跑一次 V4 → 1091。
2. 从「已经跑过 V4」的备份恢复，却把 `flyway_schema_history` 里 V4 那一行删掉/回退了，再让服务启动 → 1091。

第 1 种也可能表现为 1061（手工只 ADD 了新键、没 DROP 旧键）。

## 3. 先判断库处于哪种状态

```sql
SELECT index_name, non_unique,
       GROUP_CONCAT(column_name ORDER BY seq_in_index) AS cols
  FROM information_schema.statistics
 WHERE table_schema = 'amz_order' AND table_name = 'amz_order'
 GROUP BY index_name, non_unique
 ORDER BY index_name;

SELECT installed_rank, version, script, checksum, success
  FROM flyway_schema_history
 WHERE version IN ('3', '4', '5')
 ORDER BY installed_rank;
```

判读：

| 索引现状 | Flyway history | 状态 | 处置 |
|---|---|---|---|
| 只有 `uk_amazon_order` + `idx_shop` | 有 V3、无 V4 | 正常待迁移 | 什么都不用做，服务正常跑 V4 |
| 只有 `uk_shop_market_order` + `idx_shop_purchase_date` | 有 V4 success=1 | 已完成 | 正常 |
| 只有新键（或新旧都有） | 无 V4 / V4 success=0 | 手工改过或半迁移 | 走 §4.A（首选）或 §4.B |
| 两个旧索引都不在、新键也不在 | 无 V4 | 残局（极少见） | 走 §4.A，先把旧索引补回来 |

## 4. 修复路径

### 4.A 把库恢复成「V4 还没跑」的样子（首选）

让 Flyway 自己跑 V4，checksum 由 Flyway 自己写，最不容易出错。

```sql
-- 只补不删：哪条不存在就补哪条
ALTER TABLE amz_order ADD UNIQUE KEY uk_amazon_order (amazon_order_id);
ALTER TABLE amz_order ADD INDEX idx_shop (shop_id);
-- 若新键已存在，先撤掉，否则 V4 会撞 1061
ALTER TABLE amz_order DROP INDEX uk_shop_market_order;
ALTER TABLE amz_order DROP INDEX idx_shop_purchase_date;
-- 清掉失败记录（V4 success=0 的那一行），让 Flyway 重新执行
DELETE FROM flyway_schema_history WHERE version = '4';
```

然后重启 `amz-service-order`，观察 V4 以 success=1 写入。

> 前提：这两条 DDL 在**有数据**的库上可能失败（1062 重复 / 176 键长）。失败说明库里已经有
> 「同 amazon_order_id 多行」或「新键已生效而旧键被撤」，此时不要再手动折腾，走 §4.B。

### 4.B 目标态已达成，手工补一条 history 行（次选）

当库里已经是 `uk_shop_market_order` + `idx_shop_purchase_date`（即 V4 的目标态），
而 history 里没有 V4 的 success 行时，可以补一行。**必须连带 V5 一起判断**：V5 之后还有
`V5__order_item_table.sql`，它是否已在库里落地要一并确认。

```sql
INSERT INTO flyway_schema_history
    (installed_rank, version, description, type, script, checksum,
     installed_by, installed_on, execution_time, success)
VALUES
    (4, '4', 'order shop scoped identity', 'SQL',
     'V4__order_shop_scoped_identity.sql', -1483237947,
     'manual-repair', NOW(), 0, 1);
```

`checksum = -1483237947` 是本仓库 **Flyway 10.20.0** 对当前 V4 文件算出的 CRC32，取自
真实执行记录（`amz_order_fwit.flyway_schema_history`，AllModules IT 于 2026-09-29 跑出）。
**它不是我手算的**：如果 V4 文件被改动，这个值立即失效，Flyway 下次启动会报 checksum 校验失败。
改动过 V4 就必须重新取一次真实值（在一个空库上完整跑一次 Flyway，再读那一行）。

`installed_rank` 不能与已有行冲突，上面的 `4` 是按「V1-V3 已占 1-3」写的，实际请先查最大值。
`flyway repair` 只会清掉失败行并校正 checksum，**不会**替你插入这条成功记录。

### 4.C 验证

```sql
-- 目标态必须同时满足
SELECT COUNT(*) AS new_keys
  FROM information_schema.statistics
 WHERE table_schema = 'amz_order' AND table_name = 'amz_order'
   AND index_name IN ('uk_shop_market_order', 'idx_shop_purchase_date');   -- 期望 >= 2

SELECT version, success FROM flyway_schema_history WHERE version = '4';      -- 期望 1 行，success=1
```

然后重启服务，确认日志里没有 `Validate failed` / `Migration checksum mismatch`。

## 5. 禁止事项

1. **不要改 V4 文件**让它「可重入」。`uk_amazon_order`/`idx_shop` 的 DROP 是 V4 语义的一部分；
   改文件会改变 Flyway checksum，所有**已迁移**的库下次启动直接 `Migration checksum mismatch` 拒绝启动。
   真要改，必须同时做「已迁移库 checksum 校正」的发布方案，不是顺手 edit 一下的事。
2. **不要新增一个 V6 去做「条件 DROP」**。V6 在 V4 **之后**执行，V4 失败时 V6 根本跑不到，
   救不了这个失败；MySQL 8 也没有 `DROP INDEX IF EXISTS`，所谓「条件删除」要靠
   `information_schema` + `PREPARE` 拼 DDL，复杂度高而收益为零。
3. **不要用 `flyway repair` 掩盖问题**：它只删失败记录、校正 checksum，不补 DDL。
   删掉失败行后 V4 会再跑一次——如果旧索引已经不在，你只是把 1091 推迟到服务启动的那一刻。

## 6. 预防

- 任何「手工在库上补 DDL」的动作，必须同步在 `flyway_schema_history` 里留痕（§4.B），否则下次启动就会撞车。
- 备份恢复演练要连 `flyway_schema_history` 一起恢复，不要只恢复业务表。
- 契约测试 `OrderV4IndexMigrationContractTest` 冻结了「全仓只有 order/V4 一处 DROP INDEX」这个不变式，
  新增同类语句会让测试失败——那正是要你回来先读这份手册的意思。