# 模拟数据播种与清理 Runbook（无真实数据时的页面填充 / 压测基线）

> 定位：这是**演示、压测、页面联调用的模拟数据集**，不是 SP-API 联调证据，也不能用来证明"已接通亚马逊"。
> 数据集全部打 `SYNTHETIC` 标记、使用保留 ID 段，并在库内登记到 `amz_ops.amz_synthetic_dataset_registry`。
> **永远不要指向生产库**：本机的清理语句会删除数据，且 `--truncate-first` / `--mode truncate` 会清空整表。

## 0. 本次（第 89 轮）实际落地的东西

| 能力 | 状态 | 证据 |
|---|---|---|
| 生成器（确定性、113 张表） | 已有并本轮重跑 | `python generate.py --tier demo --reset` → 113/113 表、225,734 行；ci 档 25,484 行 |
| 结构 / 引用 / 标记 / 确定性 / manifest 校验 | 通过 | `verify.py --tier demo` → `structure/references/id types/markers/determinism/manifest/snapshot OK` |
| DDL 漂移门禁 | 本轮修复（此前 FAIL） | 重跑 `snapshot_schema.py` 后 42→49 个迁移文件被纳入，`snapshot OK`；已加进 CI |
| 可清理（cleanup.sql） | 本轮新增 | `purge.py --emit` → 113 条 DELETE；`verify_cleanup.py` 用 SQLite 实跑：删除 225,734 行、剩余 0 |
| 库内"这是 demo 数据"登记 | 本轮新增 | `registry.sql` → `amz_ops.amz_synthetic_dataset_registry`（含 `is_demo=1`、ID 段、行数） |
| 可重复加载 | 本轮新增 | `load.ps1`（远端/容器两种模式，`-DryRun` 可先演练） |
| 真实 schema 可加载性 | 本轮新增 | `verify_schema_load.py --tier demo`：从 `schema-snapshot.json` 重建 14 库 / 113 表，225,734/225,734 行灌入，0 错误；`--cleanup` 后 113 DELETE / 0 剩余 |
| 真实 MySQL 8 导入 | P1 实测 | 本机一次性 MySQL 8.0.39（端口 3399）：ci 25,574/25,574、demo 225,824/225,824（含 90 行迁移种子）；cleanup 删除 25,484 / 225,734 行，剩余回到基线。见 §8 |

### 本轮修掉的真实缺陷（不是装饰）

1. **schema 快照漂移**：`verify.py` 报 `snapshot FAIL`，因为新增迁移（spapi V9 凭证版本、order V5 order_item、ad V7 等）没有进快照 —— 意味着生成的数据集会缺少新列。已重生成快照并加入 CI 门禁。
2. **清理语句的标记守卫写死**：原实现输出 `AND source = 'SYNTHETIC'`，而生成器实际写的是 `SYN-source-000000`，导致 6 张表（`amz_email_task`、`amz_settlement_detail`、`amz_logistics_quote`、`amz_inbound_order`、`amz_profit_snapshot`、`amz_purchase_plan`）**一行都删不掉**，属于"静默漏删"。已改为从数据里推导前缀（`LIKE 'SYN-source-%'`），并用 SQLite 离线实跑验证剩余 0 行。

## 1. 前置条件

1. 一次性/可丢弃的 MySQL 8 库（14 个 schema：`amz_ad`…`amz_user`），schema 由 Flyway 迁移或 `docker/init-sql*` 建好。
2. Python 3.11+（`generate.py` / `purge.py`），`pip install sqlglot` 仅 `verify_cleanup.py` 需要（缺失时该步自动跳过）。
3. 备份：哪怕是一次性库，也先 `mysqldump`，因为清理脚本会 DELETE/TRUNCATE。
4. **不要**在有任何真实数据的库上使用 `--truncate-first` 或 `--mode truncate`。

## 2. 生成（离线，不需要任何凭证与网络）

```powershell
cd C:\Users\Administrator\Desktop\AmazonERP\tools\synthetic-data

# 演示档：9 店铺 / 3 marketplace / 10,000 订单，225,734 行
python generate.py --tier demo --reset

# CI 档（快）：4 店铺 / 2 marketplace / 1,000 订单，25,484 行
python generate.py --tier ci --reset

# 自定义规模（压测用）
python generate.py --tier demo --orders 50000 --out out/perf50k

# 可重复加载：生成带 TRUNCATE 前缀的 load-all.sql（仅限一次性库）
python generate.py --tier ci --truncate-first --reset --out out/ci-reload
```

产物：`out/<tier>/sql/*.sql`（每表一个）、`jsonl/*.jsonl`、`manifest.json`、`load-all.sql`。
输出是确定性的：同一 `(dataset_id, seed, tier, 覆盖参数)` 字节一致（verify 每轮都复算两遍比对）。

## 3. 加载

```powershell
# 先演练（只打印将要执行的命令）
powershell -ExecutionPolicy Bypass -File .\load.ps1 -Tier ci -DryRun

# 远端 MySQL
powershell -ExecutionPolicy Bypass -File .\load.ps1 -Tier demo -Server 127.0.0.1 -Port 3306 -User root -Password <pwd>

# docker 容器内的 MySQL
powershell -ExecutionPolicy Bypass -File .\load.ps1 -Tier demo -Container amz-mysql -User root -Password <pwd>

# 带 TRUNCATE 的可重复加载（需要显式确认）
powershell -ExecutionPolicy Bypass -File .\load.ps1 -Dataset out\ci-reload -Container amz-mysql -User root -Password <pwd> -AllowTruncate
```

`load.ps1` 会：先写 `registry.sql`（登记本次加载），再按 manifest 顺序逐表 `--database <db>` 灌入（不依赖 `SOURCE` 相对路径），失败即停并打印 `mysql` 的 stderr。
密码通过 `MYSQL_PWD` 传给子进程，不出现在命令行参数里。

## 4. 标记与"这是模拟数据"的识别

schema 里**没有** `is_demo` 列（113 张表只有 6 张有 `source/origin/data_source`），所以采用三层标记，而不是改 113 张表：

1. **保留 ID 段**：绝大多数表 `id >= 900000000000000000`；`amz_user` 用 `100000001+`、`amz_product` 用 `200000001+`（这两个是 INT 列限制下的小段）。
2. **文本标记**：姓名/邮箱/密钥/单号全部 `SYNTHETIC…` / `SYN-…` / `1ZSYN…`，邮箱用 `example.invalid`，电话 555 段。
3. **库内登记表**（本轮新增）：

```sql
SELECT dataset_id, seed, tier, loaded_at, `tables`, `rows`, band_min, band_max, is_demo
FROM amz_ops.amz_synthetic_dataset_registry;
-- 有行 => 这个库含模拟数据
```

统计口径排除模拟数据（示例）：

```sql
SELECT COUNT(*) FROM amz_order.amz_order
WHERE `id` NOT BETWEEN 900000000000700000 AND 900000000000709999;
```

更稳的做法：**演示库与真实库物理分离**，只在演示库加载；混库时用上面的 ID 段排除。

## 5. 清理

```powershell
# 干跑：只出报告（cleanup-report.json 含每张表的行数/ID 段/校验 SELECT）
python purge.py --tier demo

# 生成 cleanup.sql（113 条 DELETE，按 ID 段 + 数据推导的标记前缀）
python purge.py --tier demo --emit --registry

# 离线证明它能删干净（SQLite 内实跑 cleanup.sql，不需要 MySQL）
pip install sqlglot
python verify_cleanup.py --tier demo
# 期望：[verify-cleanup] OK: 113 DELETE statements, 225734 rows removed, 0 rows left in 113 tables
```

删除顺序为子表优先（反向 manifest 顺序）。安全规则（fail-closed）：

- 只有单列整型主键才自动生成范围；复合/非整型主键标 `MANUAL_*`，**不输出**并以退出码 1 报警。
- 落在保留小段（user/product）之外的低位 ID 段标 `LOW_BAND_UNKNOWN`，默认**不输出**，必须显式 `--allow-low-band`。
- `--mode truncate`（清空整表，含真实数据）必须显式 `--allow-destructive`，否则退出码 2。
- 执行一次库专用的一次性清理：先 `python purge.py --mode truncate --allow-destructive --emit --out out\demo\_truncate.sql`。

## 6. 登录与页面验证

- 合成用户（id `100000001+`）的 `password` 是 `SYNTHETIC-NOT-A-REAL-SECRET-000000` 这类标记串，**不能登录**。
- 用既有的 bootstrap 账号登录（`docker/init-sql-legacy/02-init-tables-user.sql` 里的 `testuser`，id=1，`19-init-tables-field-permission.sql` 把它提升为 `ADMIN`）。
- 因此**不要**对 `amz_user` 用 truncate 模式清理，否则会把 bootstrap 账号一起清掉。
- 需要多角色演示时，走应用层创建用户/店铺绑定（校验与审计生效），不要把合成用户当账号用。
- 页面验收（后端返回种子数据，而非前端降级样例）：Dashboard / Orders / Inventory / Finance / Ads / Logistics / Warehouse / Connectors / Notifications 列表非空且分页正常；前端"示例数据"角标只在后端不可达或非 200 时出现，出现即说明没读到种子数据。

## 7. 可加载性验证：在重建的真实 schema 上灌数

`verify_cleanup.py` 用的是「从 JSONL 推断出来的表结构」——列在 DDL 里根本不存在、值超过
`VARCHAR(n)`、NOT NULL 列从未被写入，它都发现不了。`verify_schema_load.py` 补上这一层：
它从 `schema-snapshot.json`（由 Flyway 迁移推导的真实 DDL）重建 14 个库 / 113 张表，再把
JSONL 灌进去，并在 Python 侧执行 MySQL 8 STRICT 模式才会做的约束检查。

```powershell
# 只灌数（不需要 sqlglot）
python verify_schema_load.py --tier ci

# 灌数 + 跑 cleanup.sql + 出报告（需要 sqlglot）
pip install sqlglot
python verify_schema_load.py --tier demo --cleanup --report out/demo/schema-load-report.json
# 期望：[schema-load] OK: 113 tables / 14 databases ... 225734/225734 rows loaded
#       [schema-load]    cleanup: 113 DELETE, 0 failure(s), 0 table(s) with rows left
```

覆盖的检查：

- JSONL 的每一列都必须在 DDL 中存在（否则直接报错，不会静默写错列）
- NOT NULL 且无默认值、非自增、非生成列的字段，必须被数据集写入
- 整型列必须是整数，且落在有符号 / 无符号区间内（如 `TINYINT` 不能放 500）
- `DECIMAL(p,s)` 不超精度；超小数位只告警（MySQL 会四舍五入）
- `VARCHAR(n)` / `CHAR(n)` 不超长（MySQL STRICT 下超长是 ERROR，不是警告）
- `DATE` / `DATETIME` 必须是真实日历日期（`2026-02-30` 会被抓出来）
- `JSON` 列必须是合法 JSON
- 主键 / 唯一键重复由 schema 真实拒绝（MySQL 同样会拒绝）

`test_schema_load.py`（11 项单测）用一份故意造坏的小数据集证明上述每一条**真的会失败**，
否则「全绿」没有意义：`python -m unittest test_schema_load -v`。

**已知偏差**：SQLite 没有 ENUM、无符号整型、`ON UPDATE CURRENT_TIMESTAMP`、生成列语义，
自身也不做严格类型检查，所以上面的约束是 Python 侧执行的。它把证据从「SQL 文本合法」
提升到「数据符合真实列定义」，**仍然不等于 mysqld 真的接受了这份 dump**；真 MySQL 导入
必须按第 3 节实跑。另外 demo 档有 386,570 个值（217 个 DECIMAL 列）以字符串形式写入，
MySQL 会做隐式转换（合法），JSONL 保留字符串是为了避免浮点舍入，工具记为 info 而非错误。

## 8. 真实 MySQL 8 导入验证（本机一次性实例）

`verify_schema_load.py` 是 SQLite 仿真，不等于 mysqld 真的接受了这份数据。P1 在本机起了一个
一次性 MySQL 8.0.39（实例在仓库外 `C:\tools\mysql8`，端口 3399，**不属于任何部署拓扑**），用
`apply_migrations.py` 应用 49 个 Flyway 迁移建出 14 个库，再实跑导入与清理。
基线本身也是可复现的：`--reset` 重建后重新采集，113 张表的种子行数与之前**逐表一致**（90 行）。

```powershell
# 1) 建库 + 应用 Flyway 迁移（14 库 / 49 迁移，failed=0）
#    apply_migrations.py 现在就在 tools/synthetic-data/ 里，库名从各服务 application.yml 推导
python apply_migrations.py --host 127.0.0.1 --port 3399 --user amz --reset
python apply_migrations.py --dry-run    # 只想看会跑哪些迁移时用这个

# 2) 采集「迁移自带的种子数据」基线：16 张表共 90 行
#    （bootstrap 用户、类目费率、模板、字段权限等），manifest 不含这些行
python verify_import.py --tier ci --host 127.0.0.1 --port 3399 --user amz `
  --mysql-path 'C:\tools\mysql8\mysql-8.0.39-winx64\bin\mysql.exe' `
  --save-baseline out/ci/baseline.json

# 3) 灌数（密码走 -Password 或 MYSQL_PWD，不要写进脚本）
powershell -ExecutionPolicy Bypass -File .\load.ps1 -Tier ci -Server 127.0.0.1 -Port 3399 -User amz -Password '***' -MysqlPath '...\mysql.exe'

# 4) 核对行数（baseline + manifest）+ 读登记表 + 可选 cleanup 闭环
python verify_import.py --tier ci --host 127.0.0.1 --port 3399 --user amz `
  --mysql-path '...\mysql.exe' --baseline out/ci/baseline.json --cleanup `
  --report out/ci/import-cleanup-report.json
```

实测（MySQL 8.0.39，`sql_mode` 含 `STRICT_TRANS_TABLES`）：

| 档位 | 行数核对 | cleanup 闭环 |
|---|---|---|
| ci | 25,574/25,574（25,484 合成 + 90 种子） | 删除 25,484 行，113 张表剩余回到基线 |
| demo | 225,824/225,824（225,734 合成 + 90 种子） | 删除 225,734 行，113 张表剩余回到基线 |

耗时参考：ci 档约 80s，demo 档约 7min（单连接、逐表 `SOURCE`）。

**为什么必须带 `--baseline`**：Flyway 迁移自带引导数据，直接拿 manifest 行数比对会假失败
（例如 `amz_user` 期望 8 行、实际 9 行，多出来的是 bootstrap 账号）。

本轮因此修掉的真实缺陷（都不是装饰）：

1. `verify_import.py` 的登记表查询用了 MySQL 8 保留字 `rows`（未加反引号），查询失败被
   `try/except` 静默吞掉，报告里 `registry` 恒为 `null`。已加反引号。
2. `registry.sql` 的 upsert 唯一键是 `(dataset_id, seed)` 不含 `tier`，而 `ON DUPLICATE KEY UPDATE`
   又没有刷新 `tier`：先灌 ci 再灌 demo 后，登记行是 `tier=ci` 却带着 `rows=225734` 的 demo 行数，
   审计记录自相矛盾。已把 `tier` / `generated_at` / `is_demo` 一并刷新，并补回归断言。
3. 同一 upsert 用 `VALUES(col)`，MySQL 8.0.20+ 每次加载会报 6~7 条 deprecation（warning 1287）。
   已改为 MySQL 8.0.19+ 的行别名语法 `VALUES (...) AS new ... = new.col`。

范围声明：这是**本机一次性实例的导入验证，不是生产环境验证**。实例无主从、无真实流量、
没有接入任何亚马逊凭证，也没有跑过应用层写入。

### 8.1 `apply_migrations.py` 现在会补 Flyway baseline（重要，2026-09-28 起）

`apply_migrations.py` 用 mysql 客户端裸跑每个 `V*.sql`，这条路径**绕过 Flyway**，建出来的库里没有
`flyway_schema_history`。而 14 个服务配的是 `baseline-on-migrate: false` + `baseline-version: 1`
（2026-09-30 由 `true` 改为 `false`）：服务首次启动会在执行**任何** DDL 之前直接拒绝启动，报错
`Found non-empty schema(s) ... but no schema history table`。改成 `false` 之前的 `true` 更危险 ——
先在 v1 打基线再重放 V2..Vn，撞上已存在的对象才失败，实测 `amz_ad` 报
`SQL State 42000 / Error 1061 Duplicate key name 'uk_shop_campaign'`，且 MySQL DDL 非事务，
会留下半迁移的库 + 一条失败的 `flyway_schema_history` 行。两条路都起不来，区别是 `false` 零副作用。

因此脚本在裸 SQL 之后会调用 `ensure_flyway_baseline()`，补一条 `type='BASELINE'` 的历史行，
版本打到**该模块最大版本**（ad=7、spapi=9 …），并打印 `flyway_schema_history baselined @ vN`。
该操作幂等（已有历史表则不动），正常执行后每个库应看到：

```sql
SELECT installed_rank, version, type, success FROM <db>.flyway_schema_history;
-- 期望：1 行，type=BASELINE，version=该模块最大迁移版本，success=1
```

守卫：`BareSqlBuiltSchemaFlywayStartIT`（`amz-service/amz-service-spapi`）会在 `_bsit` 库上复现整条路径，
并断言 Flyway 启动时 `migrationsExecuted == 0`、`currentVersion == maxVersion`；
同时源码级断言 `apply_migrations.py` 仍包含 `ensure_flyway_baseline` 与 `<< Flyway Baseline >>`
——**不要删掉这段逻辑**，否则合成数据环境会变成「起不来的库」。

注意这是**本地合成数据环境的便利做法**；生产正确路径是 `docker/init-sql` 只建 14 个空库 + Flyway 全量应用。

## 9. 已知限制（不要当成已经做完）

1. **实机导入范围有限**：已在**本机一次性 MySQL 8.0.39 实例**（端口 3399，见 §8）完成 ci / demo 两档灌入、行数核对与 cleanup 闭环。这不是生产环境验证——实例无主从、无真实流量、未接入任何亚马逊凭证。生产库首次导入仍需在预发环境按 §3 + §8 实跑并保留 `mysql` 输出。
2. **走的是 SQL 批量灌数，不是应用 API 写入**：绕过了应用层校验/审计/租户钩子。小批量冒烟建议走 API；大批量压测才用本工具。
3. `is_demo` 不是列级标记，而是"ID 段 + 文本标记 + 库级登记表"三层；任何新表若使用非保留段 ID，需要同步更新 `RESERVED_LOW_BANDS` 与本报告。
4. 合成数据不校验业务一致性（例如财务结算与订单的金额对账），只保证结构/引用/类型/标记合法；做经营分析前需自行构造对账用例。
5. `cleanup.sql` 只删 113 张业务表的合成行，`amz_ops.amz_synthetic_dataset_registry` 的登记行**按设计保留**（它是「此库装过模拟数据」的审计痕迹）。要彻底清空需额外执行：`DELETE FROM amz_ops.amz_synthetic_dataset_registry WHERE dataset_id='synthetic-amazon-erp-v1';`
