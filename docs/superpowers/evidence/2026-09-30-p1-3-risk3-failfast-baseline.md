# P1-3 随访：风险 #3 —— 存量库直接起服务的 fail-fast 修复（2026-09-30）

> 结论先行：**14 个服务的 `spring.flyway.baseline-on-migrate` 已由 `true` 翻为 `false`**，
> 存量/裸 SQL 预建的库在没有 `flyway_schema_history` 时，Flyway 会在**执行任何 DDL 之前**直接拒绝启动
> （fail-fast），不再出现「先 baseline@v1、再重放 V2..Vn 撞 Duplicate key、留下半迁移库 + 一条失败 history 行」。
> 生产空库路径**不受影响**（14/14 实测照常执行 V1..Vn）。
> 本轮共改 22 个文件；契约测试 8/8 绿、两个 Flyway IT 双绿、`AdMigrationMySqlIT` 绿、spapi 全模块 622 测试绿。
> **风险 #1（ad/V7 迁移内删+改写数据）仍 OPEN，但已交付可执行预检**：`tools/db-migration/ad_v7_preflight.py`
> 只读、命中 STOP 即退出非 0；单测 15/15、真实 MySQL 8.0.46 上 rc=0/2/1 三类退出码均实测兑现。
> **它不替代备份、维护窗、审批与恢复演练**（Runbook §4/§5），因此 #1 仍未闭环 —— 见
> `2026-09-30-p1-3-risk1-ad-v7-preflight.md`。风险 #2（order/V4 不可重入 DROP INDEX）已决策处置：不改 V4、不新增 V6，改为修复手册 + 契约测试冻结，实测证据见 `2026-09-30-p1-3-risk2-order-v4-drop-index.md`；大表耗时/并发已实测（5,000,000 行：V4 85.5–129.5 s、并发 DML 未阻塞、半迁移 1061 瞬间返回），见 `2026-09-30-p1-3-risk2-large-table-alter-timing.md`（生产行数无数据来源，5M 为自设压力档位，不可单点外推）。
> **风险 #4（init-sql-legacy）已于 2026-09-30 闭环：实测 9/31 脚本在 MySQL 8.0.46 上不可执行、确认不在初始化路径、已有门禁 + 新增冻结门禁，文件保留未删 —— 见 `2026-09-30-p1-3-risk4-init-sql-legacy.md`。**

## 1. 为什么必须改：原注释与事实相反

14 个 `application.yml` 的 `flyway:` 块上方原本写着：

```
# baseline-on-migrate=true 是必需的：Flyway 10 默认为 false，非空库且无 flyway_schema_history 时
# 会直接抛错并让应用拒绝启动（历史环境 14 个服务会同时起不来）。空库仍照常执行 V1。
```

这句话对 `true` / `false` 两个值的后果**完全说反了**。实测（Flyway 10.20.0 / MySQL 8.0.46）：

| 配置 | 空库（生产路径） | 存量库 / 裸 SQL 预建、无 `flyway_schema_history` |
|------|------------------|--------------------------------------------------|
| `true`（原值） | V1..Vn 全部执行 ✅ | 先写入 `1:1:BASELINE:1`，再重放 V2..Vn → 撞已存在对象 → **半途崩溃**；MySQL DDL 非事务，库留在**半迁移状态**并留下一条 `success=0` 的 history 行 ❌ |
| `false`（现值） | V1..Vn 全部执行 ✅ | **执行任何 DDL 之前**就拒绝：``Found non-empty schema(s) `X` but no schema history table. Use baseline() or set baselineOnMigrate to true...`` ❌（但零副作用，报错明确，运维可显式 baseline 纳管） |

两条路存量库都**起不来**，所以这不是「让存量库能起」的开关；区别只在于**失败时机与破坏性**。
`false` 严格优于 `true`：同样拒绝启动，但不写任何 DDL、不留脏 history。

## 2. 取证：翻转对照实验（14/14 模块）

实验程序 `%TEMP%\FlipCheck.java`，日志 `%TEMP%\p13-flipcheck.log`。
对每个模块用 `baselineOnMigrate(false)` 跑两条路径：**EMPTY**（DROP+CREATE 空库）与
**ADOPTED**（裸 SQL 预建全部表、无 history）。

| 模块 | 库 | 迁移数 | EMPTY + false | ADOPTED + false（表数 before→after / history 表是否新建） |
|------|----|--------|----------------|----------------------------------------------------------|
| ad | amz_ad | 7 | OK executed=7 | REFUSED 12→12，historyTableCreated=false |
| ai | amz_ai | 2 | OK executed=2 | REFUSED 6→6，false |
| customer | amz_customer | 1 | OK executed=1 | REFUSED 7→7，false |
| finance | amz_finance | 7 | OK executed=7 | REFUSED 5→5，false |
| logistics | amz_logistics | 5 | OK executed=5 | REFUSED 12→12，false |
| multiplatform | amz_multiplatform | 3 | OK executed=3 | REFUSED 8→8，false |
| ops | amz_ops | 1 | OK executed=1 | REFUSED 5→5，false |
| order | amz_order | 5 | OK executed=5 | REFUSED 11→11，false |
| procurement | amz_procurement | 2 | OK executed=2 | REFUSED 10→10，false |
| product | amz_product | 4 | OK executed=4 | REFUSED 12→12，false |
| report | amz_report | 1 | OK executed=1 | REFUSED 6→6，false |
| search | amz_search | 1 | OK executed=1 | REFUSED 1→1，false |
| spapi | amz_spapi | 9 | OK executed=9 | REFUSED 13→13，false |
| user | amz_user | 1 | OK executed=1 | REFUSED 6→6，false |

合计 49 个迁移（与 P1-3 审计的 49 迁移 / 14 服务一致）。
**`tablesBefore == tablesAfter` 且 `historyTableCreated=false` 是本次决定采用 `false` 的关键证据**：
拒绝发生在任何 DDL 之前，不留半迁移状态。

对照（原值 `true` 的实测崩溃）见 `%TEMP%\p13-naive-ad.log`、`%TEMP%\p13-naive-order-spapi.log`：
naive baseline@1 在 ad / order / spapi 三模块必炸，原始报错已留存。

## 3. 本轮改动（22 个文件）

| # | 文件 | 改动 |
|---|------|------|
| 1 | 14 × `amz-service/amz-service-<mod>/src/main/resources/application.yml` | `baseline-on-migrate: true` → `false`；**重写那段与事实相反的注释**（改为说明 fail-fast 语义、`true` 的半迁移后果、纳管存量库需显式 baseline）。`baseline-version: 1`、`locations: classpath:db/migration` 保留不动 |
| 2 | `.start-backend-final.bat:9` | 删除 `--spring.flyway.baseline-on-migrate=true`。这是**唯一一处会用命令行把 `true` 覆盖回去的地方**（且该参数被误放在名为 `JWT` 的变量里）；Compose 与 k8s 均无同类覆盖，已 grep 确认 |
| 3 | `FlywayBaselineContractTest.java`（spapi） | 断言 `baseline-on-migrate: true` → `false`，失败信息改为说明 fail-fast 语义 |
| 4 | `AllModulesFlywayMySqlIT.java`（spapi） | **两处**：① `:79` 对 `application.yml` 的 `baseline-on-migrate: true` 断言 → `false`（交接文档此前漏记这一处，不改会直接红）；② `:86` 的 `.baselineOnMigrate(true)` → `false`，使 IT 跑生产配置 |
| 5 | `BareSqlBuiltSchemaFlywayStartIT.java`（spapi） | 类注释里「服务都配 `baseline-on-migrate: true`」的事实描述改写为 `false` 及其后果；`.baselineOnMigrate(true)` → `false`（baseline 行已存在时 true/false 行为等价，但 IT 必须跑生产配置否则检测不到服务侧翻转） |
| 6 | `AdMigrationMySqlIT.java`（ad） | `.baselineOnMigrate(true)` → `false`。该 IT 先 DROP+CREATE 库，走的是生产空库路径，两者等价；改后与 ad 的 `application.yml` 一致 |
| 7 | `README.md:244` | 「显式 baseline-on-migrate 兼容存量库」→ 准确表述（`false` + fail-fast，不自动打基线重放 V2..Vn） |
| 8 | `tools/synthetic-data/apply_migrations.py` | 模块 docstring 与 `ensure_flyway_baseline()` docstring 里的 `true` 描述改为 `false` 语义。**未删** `ensure_flyway_baseline` 与 `<< Flyway Baseline >>`（`BareSqlBuiltSchemaFlywayStartIT` 有源码级断言），合成数据环境仍会被 Flyway 干净接受 |
| 9 | `docs/superpowers/runbooks/mock-data-seed-and-cleanup-runbook.md:219` | 「14 个服务配的是 `baseline-on-migrate: true`」→ `false`，并补 `true` 时代的实测破坏证据 |

未改动：任何已发布的 `V*.sql`（保零 drift）、`docker/init-sql/`、`k8s/`、Compose。

## 4. 验证（evidence before assertions）

| 检查 | 结果 | 来源 |
|------|------|------|
| `FlywayBaselineContractTest` | `Tests run: 8, Failures: 0, Errors: 0` + `BUILD SUCCESS` | 宿主 `mvn -o -B -ntp test -pl amz-service/amz-service-spapi -Dtest=FlywayBaselineContractTest` |
| `AllModulesFlywayMySqlIT` | `Tests run: 1, Failures: 0` (25.84 s) | 容器 `amz-p13-runner`（MySQL 8.0.46 @ 172.20.0.3），`%TEMP%\p13-ctr3.log` |
| `BareSqlBuiltSchemaFlywayStartIT` | `Tests run: 1, Failures: 0` (20.32 s) | 同上，`%TEMP%\p13-ctr3.log` |
| 两个 IT 合计 | `Tests run: 2, Failures: 0, Errors: 0` + `BUILD SUCCESS` | 同上 |
| `AdMigrationMySqlIT` | `Tests run: 1, Failures: 0` (8.80 s) + `BUILD SUCCESS` | 容器，`%TEMP%\p13-ad3.log` |
| spapi 模块全量 | `Tests run: 622, Failures: 0, Errors: 0, Skipped: 4` + `BUILD SUCCESS` | 宿主 `mvn -o -B -ntp test -pl amz-service/amz-service-spapi` |
| 14 个 yml 语义校验 | 全部可解析（`yaml.safe_load_all`），`spring.flyway.baseline-on-migrate is False`、`baseline-version == 1`、`locations == classpath:db/migration` | 一次性脚本校验 |
| 覆盖残留扫描 | `git grep 'baseline-on-migrate: true\|baselineOnMigrate(true)\|baseline-on-migrate=true'` 在**代码/脚本/测试**中 0 处命中；仅历史 plan 文档仍有旧表述（见 §6） | `git grep` |

> 环境注意：Windows 宿主经 `127.0.0.1:<port>` 连 MySQL 存在间歇性冷启动超时（环境 artifact，非仓库缺陷），
> 因此所有连 MySQL 的验证都走 `p13net` 容器内网（`amz-p13-drill2` @ `172.20.0.3:3306`）。

## 5. 仍未闭环（OPEN，等决策）

| 项 | 内容 | 建议 |
|----|------|------|
| **#1** | `amz-service-ad` V7 在迁移内做删除 + 改写数据 | **部分**：已交付可执行预检 `tools/db-migration/ad_v7_preflight.py`（只读，STOP 即 rc=2；15/15 单测 + MySQL 8.0.46 上 rc=0/2/1 实测）。**仍缺**：生产/预发真实预检、§4 备份与恢复验证、维护窗、审批签字、接入 CI —— 见 `2026-09-30-p1-3-risk1-ad-v7-preflight.md` |
| **#2** | `amz-service-order` V4 是不可重入的 `DROP INDEX`（全仓唯一） | **已决策并落地处置**：不改 V4（改则已迁移库 checksum 校验失败）、不新增 V6（排在 V4 之后救不了 V4，且 MySQL 8 无 `DROP INDEX IF EXISTS`）。改为 `docs/superpowers/runbooks/order-v4-drop-index-repair.md`（1091/1061 处置 + 手工补 history 用真实 checksum `-1483237947`）+ 契约测试 `OrderV4IndexMigrationContractTest` 冻结。实测：正常 rc=0、重跑 1091、半迁移 1061 且原子 DDL 回滚。大表耗时/并发已实测（5M 行：V4 85.5–129.5 s、`LOCK=NONE` 被接受、DML 未阻塞、1061 <1 s 返回），见 `2026-09-30-p1-3-risk2-large-table-alter-timing.md`；**仍缺**：生产行数无数据来源（本地 `amz_order` 为空表）、复制延迟与云盘 IO 未测 |
| **#4** | `docker/init-sql-legacy/` 31 个脚本：实测 **9/31** 在 MySQL 8.0.46 上不可执行，不在初始化路径 | **已闭环**：保留不删（删会丢 17 条 seed 语句），已有门禁 + 新增 `LegacyInitSqlArchiveContractTest`。见 `2026-09-30-p1-3-risk4-init-sql-legacy.md` |
| 治理 | `master` 分支保护 API 仍 404；`production` environment 仍单用户自审 | 需用户决策，不在本轮范围 |

## 6. 本轮顺带发现、未修的数字不一致（独立缺口）

同一事实源出现三个互斥数字，本轮**未改数字**，仅记录：

- `README.md:244` → **106** 张表
- `FlywayBaselineContractTest` 断言 → **113** 张（注释写明「112 基线 + V5 `amz_order_item`」）
- `docs/superpowers/plans/2026-09-24-connector-api-ready-phase0.md:694` → **109** 张（自称「第 71 轮实测修正」）

契约测试的 113 是有断言守着的当前真值；README 的 106 与 plan 的 109 至少有一个是过期的。
修数字前必须先确认口径（「迁移文件里出现的 `CREATE TABLE` 名去重集合」≠「线上实际存在的表」），
否则是在掩盖而不是修缺口。此外该 plan 文档 §18 / §512 / §524 / §541 / §545 / §694 / §715 仍写 `baseline-on-migrate: true`，
属于 2026-09-24 的历史计划文本，**本轮未改写历史记录**，但读者需以本文件与代码为准。
