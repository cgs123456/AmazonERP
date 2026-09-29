# P1 Flyway 全量审计与升级路径演练证据（2026-09-29）

## 1. 范围与结论

本证据覆盖交接文档 `docs/superpowers/evidence/2026-09-28-phase0-handoff.md` 的 P1 第 3 项：

- 审查全部服务模块的 Flyway `V*.sql`。
- 在 MySQL 8.0 上验证空库全量迁移路径。
- 在 MySQL 8.0 上验证已有旧版本数据时的升级路径。
- 验证裸 SQL 建库后补 Flyway baseline 的启动路径。
- 核对部署初始化脚本与迁移模块的数据库集合一致性。

结论：本地证据链已完整。14 个模块、49 个迁移脚本、113 张表由 Flyway 作为唯一建表入口；空库路径、baseline 路径和 8 个多版本库升级路径均在真实 MySQL 8.0 上通过。该结论不等于生产规模验证、远端 CI 验证或真实 SP-API 联调；分支尚未合并、推送或打标签。

## 2. 证据摘要

| 检查项 | 命令或证据 | 结果 | 状态 |
|---|---|---|---|
| 整仓 Maven 测试 | `mvn -B -ntp test -fae` | 233 个 suite / 1537 tests / 0 failures / 0 errors / 2 skipped；BUILD SUCCESS | VERIFIED |
| Flyway 迁移清点 | 扫描 14 个 `src/main/resources/db/migration` | 49 个 SQL；0 个 `R__` repeatable 迁移 | VERIFIED |
| 空库全量迁移 | `AllModulesFlywayMySqlIT` | 14 库 x 49 迁移，1 test，0 failure/error | VERIFIED |
| 裸 SQL baseline 启动 | `BareSqlBuiltSchemaFlywayStartIT` | 14 库，1 test，0 failure/error | VERIFIED |
| 多版本升级路径 | `AllModulesUpgradeFlywayMySqlIT` | 8 库，8 tests，0 failure/error | VERIFIED |
| AD 专项升级 | `AdMigrationMySqlIT` | V1 -> V7，1 test，0 failure/error | VERIFIED |
| Flyway 契约 | `FlywayBaselineContractTest` | 8 tests，0 failure/error | VERIFIED |
| 初始化库集合 | `docker/init-sql/01-init-databases.sql` 与迁移模块库名集合比对 | 14/14，双向差集为空 | VERIFIED |
| 破坏性语句扫描 | 顶层可执行语句扫描 | 0 个 `DROP TABLE` / `DROP COLUMN` / `DROP DATABASE` / `TRUNCATE` | VERIFIED |

2 个 skipped 均为 `amz-service-spapi` 的 `SpApiIntegrationTest`，原因是当前环境没有真实 SP-API 凭证。它们是预期跳过，不构成迁移失败。

## 3. 测试环境与边界

- 验证分支：`codex/p1-db-migration-audit`。
- 验证基线：`136cec0`，加本次新增的 `AllModulesUpgradeFlywayMySqlIT.java`；验证时新增测试尚未提交。
- 数据库：`mysql:8.0`。
- Maven：`maven:3.9-eclipse-temurin-17`。
- JDBC 主机：容器网络中的 MySQL 服务名；未使用宿主机端口转发地址。
- 测试库后缀：空库 `_fwit`，baseline `_bsit`，升级 `_upit`；测试自身强制校验后缀，避免触碰业务库。
- 仅验证 MySQL 8.0；未验证 MySQL 5.7、MariaDB 或其他数据库。

## 4. 静态审计

### 4.1 迁移清点

迁移扫描范围为 `amz-service/amz-service-*/src/main/resources/db/migration/V*.sql`，不包含 `target/` 下的构建副本。

| 模块 | SQL 数 |
|---|---:|
| amz-service-ad | 7 |
| amz-service-ai | 2 |
| amz-service-customer | 1 |
| amz-service-finance | 7 |
| amz-service-logistics | 5 |
| amz-service-multiplatform | 3 |
| amz-service-ops | 1 |
| amz-service-order | 5 |
| amz-service-procurement | 2 |
| amz-service-product | 4 |
| amz-service-report | 1 |
| amz-service-search | 1 |
| amz-service-spapi | 9 |
| amz-service-user | 1 |
| **合计** | **49** |

迁移目录数为 14，`R__` repeatable 迁移数为 0。

### 4.2 初始化库集合

`docker/init-sql/01-init-databases.sql` 只创建数据库，不创建表。脚本中的 `CREATE DATABASE` 集合与带迁移模块的数据库集合完全一致：

- 共 14 个库。
- 仅存在于初始化脚本的库：空集。
- 仅存在于迁移模块的库：空集。
- 集合相等：是。

### 4.3 顶层可执行语句计数

扫描方法：先剥离 `--`、`#`、`/* ... */` 注释并保留字符串字面量，再按顶层分号切分语句，最后按语句首关键字分类。此口径只统计真正可执行的顶层语句，不把动态 SQL 字符串中的文本计为独立语句。

| 语句 | 数量 |
|---|---:|
| CREATE TABLE | 113 |
| ALTER TABLE | 30 |
| CREATE INDEX | 5 |
| DROP INDEX | 0（顶层语句） |
| DROP TABLE | 0 |
| DROP COLUMN | 0 |
| DROP DATABASE | 0 |
| TRUNCATE | 0 |
| DELETE | 4 |
| UPDATE | 13 |

### 4.4 文本匹配口径对照

直接对 SQL 原文做关键词匹配会显著高估，原因是 logistics V2-V5 使用动态 SQL 字符串，ad V7 包含大量字符串和注释，且 `CHANGE COLUMN`、`MODIFY COLUMN` 是 `ALTER TABLE` 内部子句，不是顶层语句。

| 文本模式 | 文本匹配数 | 说明 |
|---|---:|---|
| ALTER TABLE | 33 | 其中 3 处在 logistics V2/V3 动态 SQL 字符串中 |
| CREATE INDEX | 9 | 其中 4 处在 logistics V2/V3/V4/V5 动态 SQL 字符串中 |
| DELETE | 5 | spapi V2 的 HTTP 方法文本造成 1 次误报；真实顶层 DELETE 为 4 |
| UPDATE | 66 | 包含字符串、动态 SQL 和注释噪声；真实顶层 UPDATE 为 13 |
| CHANGE COLUMN | 4 | 均为 `ALTER TABLE` 内部子句 |
| MODIFY COLUMN | 12 | 均为 `ALTER TABLE` 内部子句 |

证据文档和后续自动化不得把文本匹配数写成可执行语句数。

### 4.5 破坏性操作与索引替换

- `DROP INDEX` 顶层语句为 0。
- `order` 的 `V4__order_shop_scoped_identity.sql` 在一条 `ALTER TABLE` 内包含 2 个 `DROP INDEX` 子句：`uk_amazon_order`、`idx_shop`；同一语句新增 `uk_shop_market_order` 和 `idx_shop_purchase_date`。
- `DELETE` 共 4 条：ad V2 1 条；ad V7 3 条。它们用于按业务键保留 canonical/latest 记录并删除重复行，属于设计内破坏性归并。
- `UPDATE` 共 13 条；其中 6 条没有 `WHERE`，全部位于 ad V7，涉及 `amz_ad_keyword`、`amz_ad_converting_terms`、`amz_ad_asin_keyword` 的规范化或去重合并。
- 未发现 `DROP TABLE`、`DROP COLUMN`、`DROP DATABASE`、`TRUNCATE`。

ad V2/V7 的删除和全表更新必须按 `docs/superpowers/runbooks/ad-business-uniqueness-migration.md` 执行预检、备份、恢复演练和维护窗口控制。

## 5. 动态验证覆盖

### 5.1 空库全量路径

`AllModulesFlywayMySqlIT` 在 `_fwit` 专用库上执行：

- 从部署脚本解析出的库集合必须等于带迁移模块的库集合。
- 每个模块的 `application.yml` 必须包含 `baseline-on-migrate: true`。
- 每个空库必须实际执行该模块全部迁移文件。
- `flyway_schema_history` 行数必须等于迁移文件数。
- 不允许存在 `success = 0` 的历史行。
- 迁移后每个库必须至少有 1 张表。
- 全部模块执行总数必须大于等于 49。

该测试覆盖 14 库 x 49 迁移，实际结果 1 test / 0 failure / 0 error / 0 skipped。

### 5.2 裸 SQL baseline 路径

`BareSqlBuiltSchemaFlywayStartIT` 在 `_bsit` 专用库上执行：

- 用裸 SQL 依次执行模块全部迁移。
- 按 `tools/synthetic-data/apply_migrations.py` 的格式补写 Flyway baseline 行，版本为模块最大版本。
- 再启动 Flyway，断言 `migrationsExecuted = 0`。
- 断言当前版本等于最大版本，且没有失败历史行。
- 源码守卫要求 `apply_migrations.py` 仍包含 `ensure_flyway_baseline` 和 `<< Flyway Baseline >>`。

实际结果 1 test / 0 failure / 0 error / 0 skipped。

### 5.3 多版本升级路径

`AllModulesUpgradeFlywayMySqlIT` 在 `_upit` 专用库上先执行到指定旧版本、插入旧数据，再迁移到当前版本，覆盖 8 个有后续版本的库：

| 模块 | 升级路径 | 主要验证 |
|---|---|---|
| order | V1 -> V5 | 宽主键、复合订单身份、订单明细、旧索引替换 |
| ai | V1 -> V2 | 新增 Agent 评估日志表且保留旧数据 |
| finance | V1 -> V7 | 结算/回款订单号列改名并保留旧值 |
| procurement | V1 -> V2 | 旧批次保留且 FBA 签收键唯一 |
| product | V1 -> V4 | `productType`、轮询回填和 `PARTIAL` 状态 |
| logistics | V1 -> V5 | 轨迹来源、同步时间与查询索引升级 |
| multiplatform | V1 -> V3 | 明细 JSON 与平台订单号列改名 |
| spapi | V1 -> V9 | 旧 512 字符凭证、旧 outbox 回填与通知表 |

覆盖的断言包括数据保留、列改名、回填、唯一键列数、索引替换、旧对象消失和重复键拒绝。实际结果 8 tests / 0 failure / 0 error / 0 skipped。

只有 V1 的 customer、ops、report、search、user 没有后续升级点，由空库路径和 baseline 路径覆盖。

### 5.4 AD 专项升级

`AdMigrationMySqlIT` 在 `amz_ad_it` 上执行 V1 -> V7，覆盖：

- 租户字段回填。
- 广告关键词 4 列唯一键。
- converting term 3 列唯一键。
- ASIN keyword 3 列唯一键。
- 规范化、重复行归并、`campaign_id NOT NULL`。
- Mapper 原子 upsert。

实际结果 1 test / 0 failure / 0 error / 0 skipped。

### 5.5 Flyway 契约

`FlywayBaselineContractTest` 共 8 项，覆盖：

- 14 个数据库模块显式配置 Flyway baseline。
- report 模块有独立 datasource。
- Flyway 是唯一建表入口，唯一表集合为 113 张，建表语句总数也为 113。
- Compose 与 k8s 建库集合完全一致，均为 14 个。
- 迁移不使用 MySQL 8 不支持的条件 DDL。
- `rank` 保留字改为 `rank_position`，实体映射同步。
- inventory alert 的店铺级规则允许 `sku NULL`。
- upgrade 列直接进入初始建表语句。

实际结果 8 tests / 0 failure / 0 error / 0 skipped。

### 5.6 CI 接线

根 `pom.xml` 的 Surefire 配置额外包含 `**/*IT.java`，因此整仓 `mvn test` 会执行这些环境守卫型 IT。`.github/workflows/ci.yml` 已注入：

- `AD_MYSQL_IT_*`：执行 AD V1 -> V7 与 Mapper 集成测试。
- `FLYWAY_ALL_IT_*`：执行空库、baseline 和升级路径。
- CI 使用整仓 `mvn -B test -fae`，未使用 `-Dtest=` 收窄测试范围。

## 6. 残余风险与未验证边界

1. 没有生产数据规模验证。升级 IT 使用手工构造的小数据集，不能证明大表上的 DDL 时长、undo/log 增长、复制延迟或锁等待可接受。
2. DDL 锁与长事务风险仍存在：共有 30 条顶层 `ALTER TABLE`，ad V7 另有 6 条无 `WHERE` 的全表 `UPDATE`。生产执行必须按维护窗口和 runbook 控制。
3. order V4 的 `marketplace_id` 可空；MySQL 唯一索引不会用 NULL 做去重，因此“`marketplace_id` 为空且 `shop_id`/`amazon_order_id` 相同”的行仍可能重复。购物车行三列均为 NULL 属于设计内豁免。彻底消除需要拆分平台订单与本地购物车订单，当前迁移未做。
4. SPAPI 旧 512 字符 schema 由升级 IT 手工构造；仓库内不存在该旧 `db/schema.sql`。不得把该测试描述为对仓库中已有旧 schema 文件的验证。
5. 连接器仍是 API-Ready，未使用真实 SP-API 凭证联调；本证据不证明 SP-API 已接通。
6. ad V2/V7 的 DELETE 去重是设计内破坏性操作；有真实数据后必须按 runbook 预检、备份并在独立库验证恢复。
7. 仅在 MySQL 8.0 验证；未覆盖 MySQL 5.7、MariaDB 或云数据库兼容模式。
8. 本次未执行远端 CI，未修改或重打 `v0.1.2` tag，未合并分支。任何未执行的工作流或环境不得标为 VERIFIED。

## 7. 复现说明

整仓 Maven 测试在容器内执行，环境变量指向 MySQL 8.0 测试实例：

```powershell
docker exec -w /workspace `
  -e FLYWAY_ALL_IT_URL='jdbc:mysql://<mysql-host>:3306/<database>?allowMultiQueries=true&useSSL=false&allowPublicKeyRetrieval=true' `
  -e FLYWAY_ALL_IT_USER=root `
  -e FLYWAY_ALL_IT_PASSWORD='<test-password>' `
  -e AD_MYSQL_IT_URL='jdbc:mysql://<mysql-host>:3306/amz_ad_it?allowMultiQueries=true&useSSL=false&allowPublicKeyRetrieval=true' `
  -e AD_MYSQL_IT_USER=root `
  -e AD_MYSQL_IT_PASSWORD='<test-password>' `
  -e AD_MYSQL_IT_RESET=true `
  amazonerp-p1-maven mvn -B -ntp test -fae
```

静态审计使用一次性 Python 扫描器：剥离注释并保留字符串，按顶层分号切分，按首关键字分类；同时独立匹配 `DROP INDEX` 子句和数据库集合。本文档不提交扫描器临时脚本。