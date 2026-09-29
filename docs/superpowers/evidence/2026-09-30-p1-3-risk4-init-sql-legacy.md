# P1-3 风险 #4：`docker/init-sql-legacy` 归档脚本 —— 取证与门禁

- 日期：2026-09-30
- 前一环：`2026-09-30-p1-3-risk3-failfast-baseline.md`（风险 #3 已闭环，HEAD `28f00a3`）
- 风险原文：`2026-09-30-p1-3-migration-audit.md` §9 第 4 条 —— “`init-sql-legacy` 31 个脚本仍是 MySQL 8.0 不可执行的定时炸弹（含 MariaDB 语法）：是否删除或移出仓库 / 加 CI 门禁禁止其被执行？”

## 0. 结论（先给答案）

| 项 | 结论 |
|----|------|
| 是否已在初始化路径 | **否**。Compose 只挂 `docker/init-sql/01-init-databases.sql`（`docker-compose.yml:41`），k8s 走 `k8s/infra/mysql-init-job.yaml`（只建库） |
| 是否已有门禁 | **是**，且已覆盖三条回潮路径：`DeploymentSchemaBootstrapContractTest` 断言 `docker/init-sql` 只有 `01-init-databases.sql`、Compose 不挂 legacy 目录、k8s Job 只建库 |
| 到底有几个脚本不可执行 | **9/31**（实测，MySQL 8.0.46）。交接文档此前记的「2/31」只覆盖了 MariaDB 语法一类，**不完整** |
| 建议 | **保留并冻结，不删除**（理由见 §5：删除会丢 17 条 seed 语句且被 runbook 引用）；本轮新增一条「已知不可执行集合」冻结门禁（§4） |
| 本轮是否删文件 | **否**。用户从未批准删除这 31 个文件，本轮只加标记与门禁 |

## 1. 取证方法（可复现）

1. 起一次性容器 `amz-p13-legacy`（镜像 `mysql:8.0`，实测 `SELECT VERSION()` = **8.0.46**，root/`Legacy_pw1`），不碰 `amz-mysql` 业务库，也不碰演练库 `amz-p13-drill2`。
2. 用仓库自己的 `docker/init-sql/01-init-databases.sql` 建 14 个空库。
3. **逐个**执行 31 个归档脚本；每个脚本执行前 drop+create 全部 14 个库，保证互相隔离。
4. `mysql` 客户端默认遇错即停，记录退出码与第一条 ERROR。

扫描命令（宿主机）：`docker exec amz-p13-legacy bash /tmp/legacy_scan.sh`，原始输出：`%TEMP%\p13-legacy-scan.log`。

## 2. 实测结果：9/31 执行失败

| 脚本 | rc | 第一条错误 | 根因 |
|------|----|-----------|------|
| `14-init-tables-ops.sql` | 1 | ERROR 1064（语句起始行 44，`rank INT DEFAULT NULL …`，文件第 49 行） | MySQL 8.0 起 **RANK 是保留字**（窗口函数），不能作未加引号列名 |
| `19-init-tables-field-permission.sql` | 1 | ERROR 1064（语句起始行 43） | MariaDB 专有语法 `ADD COLUMN IF NOT EXISTS` |
| `23-init-tables-procurement-upgrade.sql` | 1 | ERROR 1064（语句起始行 132） | 同上，6 处 |
| `28-init-tables-p1-listing-monitor.sql` | 1 | ERROR 1046 No database selected | 脚本内**没有 `USE` 语句** |
| `29-init-tables-p1-order-audit.sql` | 1 | ERROR 1046 | 同上 |
| `30-init-tables-p1-realtime-profit.sql` | 1 | ERROR 1046 | 同上 |
| `31-init-tables-p1-multi-warehouse.sql` | 1 | ERROR 1046 | 同上 |
| `32-init-tables-p2-multiplatform.sql` | 1 | ERROR 1046 | 同上 |
| `33-init-tables-p2-ai-tools.sql` | 1 | ERROR 1046 | 同上 |

其余 **22/31 执行成功**（rc=0）。

分三类看：

- **MySQL 8 语法致命（3 个）**：14（`rank` 保留字）、19、23（`ADD COLUMN IF NOT EXISTS`）。其中 19/23 此前已带 `[ARCHIVE ONLY]` 头，**14 是本轮新发现并补上标记的**。
- **不自带库上下文（6 个）**：28~33，P1/P2 阶段脚本，缺 `USE`。挂载进 `/docker-entrypoint-initdb.d` 会直接 ERROR 1046 让容器初始化失败。
- 迁移侧没有同类问题：`amz-service-ops` 的 `V1__init.sql:44` 用的是 `rank_position`；全仓迁移中不存在裸 `rank` 列（`git grep -nE '^\s*`?rank`?\s+(INT|BIGINT|…)' -- '*/db/migration/*'` 无命中）。

## 3. 为什么它进不了初始化路径（已有门禁）

- `docker-compose.yml:41`：`./docker/init-sql/01-init-databases.sql:/docker-entrypoint-initdb.d/01-init-databases.sql:ro`，**整目录不挂**。
- `DeploymentSchemaBootstrapContractTest.java`
  - `:54-60` `composeInitDirectoryContainsOnlySchemaBootstrap`：`docker/init-sql` 必须恰好等于 `["01-init-databases.sql"]` —— 谁把 legacy 文件拷进去都会红。
  - `:69-76` `composeMountsOnlySchemaBootstrap`：断言 Compose 不含 `init-sql-legacy:/docker-entrypoint-initdb.d`。
  - `:78+` `kubernetesJobCreatesExactlyTheSameSchemasBeforeServices`：k8s Job 只建 14 库、不得含表/数据 DDL。
- 全仓引用 `init-sql-legacy` 的位置只有：README 说明（`:396`）、上述门禁断言、19/23/14 自己的 `[ARCHIVE ONLY]` 注释、以及证据/runbook 文档。**没有任何 Compose / k8s / Dockerfile / CI 引用它**（扫描 37 个部署描述符：compose 1 + k8s yaml + `.github/**` + 2 个 Dockerfile）。

## 4. 本轮新增门禁

新增 `amz-service/amz-service-spapi/src/test/java/com/amz/deploy/LegacyInitSqlArchiveContractTest.java`（3 个用例，实测 `Tests run: 3, Failures: 0`）：

1. `noDeploymentDescriptorReferencesTheArchive` —— 扫 `docker-compose.yml` / `Dockerfile` / `amz-frontend/Dockerfile` / `k8s/**` / `.github/**`，任一出现 `init-sql-legacy` 即失败（把「回潮」从 Compose 单点扩到全部部署描述符）。
2. `incompatibleScriptsAreFrozenAndMarkedArchiveOnly` —— 冻结两类致命集合：条件 DDL（MariaDB 语法）== {19, 23}；裸 `rank` 列名 == {14}；并要求这三个文件必须带 `[ARCHIVE ONLY]` 标记。
3. `scriptsWithoutUseStatementAreFrozen` —— 冻结「缺 `USE` 语句」集合 == {28~33}。

冻结的意义：新增同类缺陷立刻红；谁真去修好 14/19/23，也必须回来改基线，不会静默变成“看起来能用”。

同时给 `docker/init-sql-legacy/14-init-tables-ops.sql` 头补上 `[ARCHIVE ONLY]` + MySQL 8 改写说明（与 19/23 同一惯例），本轮**未改动任何 SQL 逻辑、未删除任何文件**。

## 5. 删还是留：成本核算（之前没算过的一栏）

| 维度 | 数据 | 含义 |
|------|------|------|
| 表结构冗余 | legacy 定义 **105** 张表；在其中找不到对应 Flyway 迁移的表 = **0** | 删掉**不会**丢任何表结构定义 |
| seed 数据 | **16** 条 `INSERT [IGNORE] INTO` + **1** 条 `UPDATE`，分布在 12 个文件 | 删掉**会**丢：bootstrap 账号、字段权限、类目费率、FBA 费率表、季节性指数、促销日历等 |
| 文档引用 | `runbooks/mock-data-seed-and-cleanup-runbook.md:120` 明确引用 `02-init-tables-user.sql` 的 `testuser`(id=1) 与 `19-…` 的 ADMIN 提升 | 删除会让 runbook 指向不存在的路径 |

`testuser` 在 `amz-service-user/db/migration/V1__init.sql:33` 也有，`:95` 同样有 ADMIN 提升 —— 所以「登录账号」这条迁移已承接；但**字段权限、费率表、季节性指数等 seed 没有迁移承接**。

建议：**保留 + 冻结**（本轮做法）。若坚持要删，前置条件是先把这 17 条 seed 语句迁进对应模块的 `V*.sql`，并同步改 runbook：否则等于把「模拟数据/费率基线」的唯一副本删掉。

> 边界声明：上面的 105 张表是「legacy 脚本里出现的 `CREATE TABLE` 名字」与迁移文件做**文本**匹配得出的，不是线上库的实际表清单；用来判断「有没有迁移承接」足够，用来对线上表计数不够（这也是 §7 数字口径问题的来源）。

## 6. 对交接文档的两处更正

1. **文件数是 31，不是 33**：目录内编号从 02 到 33，缺 01 与 03，实际 `*.sql` 计数 = 31。
2. **「仅 2/31 含 MySQL8 不兼容条件 DDL」不完整**：条件 DDL 确实是 2 个（19/23），但**不可执行**的是 9 个 —— 另有 1 个保留字 `rank`（14）和 6 个缺 `USE`（28~33）。风险 #4 原文说的「定时炸弹」成立，只是数量被低估了 4.5 倍。

## 7. 仍未闭环 / 待决策

- **是否删除这 31 个文件**：未批准，未执行。建议保留（§5）。
- **数字口径不一致**（沿用 risk3 证据 §6）：README 说 106 张表、契约测试断言 113、plan 文档 109；本证据的 105 是「legacy 脚本里的 CREATE TABLE 去重集」，**不是**第四个数，别混用。要统一口径得先定「数什么」（迁移创建的表 / 线上实际表 / legacy 定义的表）。
- 风险 **#1（ad/V7 破坏性归并）** 与 **#2（order/V4 DROP INDEX）** 仍 OPEN，见 risk3 证据 §5；
  其中 #1 的「接入 CI」子项已于 2026-09-30 闭环（`ci.yml` 的 `ad-v7-preflight-gate`，远端 run `36632948323` 绿灯），
  剩余 4 项为需人/需生产环境的运营项，不能靠提交代码关闭。
