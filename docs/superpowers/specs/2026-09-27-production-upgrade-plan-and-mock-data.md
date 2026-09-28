# AmazonERP 生产化升级方案（API-Ready 边界 + 模拟数据填充）

生成时间：2026-09-27（Asia/Shanghai）
事实源：当前工作区 `C:\Users\Administrator\Desktop\AmazonERP`、本文件列出的实际命令输出、Amazon 官方 SP-API 文档、GitHub API 当前快照。
分支：`codex/api-ready-connectors`；本轮未提交、未清理其他既有改动。

## 0. 先纠正关键前提：有 API ≠ 可以直接生产使用

用户诉求：“暂时没有对接 API，但需要对接能力，有 API 就可以直接使用”。必须拆成两句，否则会误判上线风险：

- **成立的部分（代码级 API-Ready）**：仓库已具备凭证模型、加密存储、店铺隔离、RBAC、统一 SP-API 网关、LWA/RDT token source、Outbox/DLQ、官方限流表、分层连通性自检、连接器能力台账与前端连接器中心。真实凭证到位后，主要通过配置/授权生效，不需要重写业务层。
- **不成立的部分（生产可用）**：Amazon 不是“有凭证即插即用”。官方链路仍要求：应用注册/审批、卖家授权与角色、RDT/PII 权限、正确的 region/marketplace/endpoint、通知订阅（SQS 或 EventBridge）、沙箱联调再到生产试点。当前没有 E4/E5 真实联调证据，因此对外只能写“具备对接能力（未联调）”，不能写“已接通/生产可部署”。

## 1. 本轮已验证的离线证据（可复现）

### 1.1 后端 SP-API

- 定向契约测试：`ShopCredentialStoreFailClosedTest, ShopCredentialAdminServiceTest, ShopCredentialControllerContractTest, ShopCredentialMapperContractTest, CredentialSchemaContractTest, SpapiControllerShopScopeContractTest`
  - 结果：`Tests run: 45, Failures: 0, Errors: 0`。
- `ControllerFailureContractTest` 基线校准：
  - 旧预期 `57` 个 `Result.failure` 出口，当前实际 `62`；新增 5 个全部位于 `SpapiController`（shopId 缺失、越权、字段校验失败、CAS 冲突、非法请求），且都带 `ErrorSummary`。
  - 已把测试预期从 57 校准为 62；单独复跑 `Tests run: 1, Failures: 0`。
- 全量模块：`mvn -pl amz-service/amz-service-spapi -am test`
  - 结果：`Tests run: 608, Failures: 0, Errors: 0, Skipped: 2`，`BUILD SUCCESS`；2 skipped 为需要真实凭证的 `SpApiIntegrationTest`。

### 1.2 前端

- 类型检查 + 生产构建（`vue-tsc --noEmit`、`vite build`）：通过。
- 全量测试：`vitest run`
  - 结果：`Test Files 22 passed (22)`、`Tests 175 passed (175)`。
  - 日志中的 “使用降级数据/使用模拟回复” 是故意覆盖的降级路径测试，不是真实联调失败。

### 1.3 本轮代码收口（API-Ready 相关）

- `ShopCredentialStore` / `ShopCredentialAdminService` / `ShopCredentialMapper`：凭证写入改为数据库 CAS（`version = version + 1` + `WHERE version = expectedVersion`），冲突抛 `ShopCredentialConcurrentUpdateException`，管理服务最多 3 次重试并驱逐缓存后重读；普通 `put()` 走 `updateUnconditionally()`，insert/update 行数不等于 1 时 fail-closed。
- `SpapiController` 旧入口 `POST /spapi/credential`：保留兼容但 `@Deprecated`、仅 ADMIN、委托 CAS 管理服务，不再无条件覆盖；冲突用 `error.code=CONFLICT` 表达（项目 `Result.failure()` 固定 HTTP 业务码 400，不改 409）。
- 状态接口 `ShopCredentialStatus` 仍为 9 字段，不暴露 `version` 与任何秘密字段。

## 2. 外部调研（官方 + 开源同类）

### 2.1 Amazon 官方文档事实（用于纠正实现细节）

- 连接 SP-API：普通操作在 `x-amz-access-token` 中传 LWA access token；**受限数据操作必须改用 RDT**。每个请求必须带合规 `user-agent`（应用名/版本/语言，最大 500 字符）。
- 授权：公共应用需要卖家授权与年度/角色续授权；私有应用可自授权。受限数据需要 Restricted Data Token 授权。
- 限流：官方页面为 `Usage Plans and Rate Limits`（旧的 `/rate-limits` 已失效）。明确要求：读取 `x-amzn-RateLimit-Limit`、**不要硬编码定时器**、优先事件驱动而非循环轮询、按 `429` 处理动态限流。
- 限流优化策略：自动重试 + 指数退避 + **jitter**、最大重试与最大延迟上限、缓存高频数据、把请求排队错峰、减少不必要调用、设置错误监控告警。
- 通知：官方支持 Amazon SQS 工作流与 Amazon EventBridge 工作流；本仓库当前配置实现 `mock` 与 `sqs`，默认关闭，SQS 必须使用 standard 队列，顺序由业务层按 `eventTime` 处理。

### 2.2 GitHub 同类项目快照（GitHub API 当前结果，星数会变化）

- SDK/客户端类为主：`saleweaver/python-amazon-sp-api`、`jlevers/selling-partner-api`(PHP)、`lineofflight/peddler`(Ruby)、`jrl84/amazon-sp-api`(JS)、`abuzuhri/Amazon-SP-API-CSharp`(C#)、`amzn/selling-partner-api-samples`(Java)、`penghaiping/amazon-sp-api`(Java)。
- ERP/多平台类较少且规模更小：`jackspeng/shop`、`nplszfl/OmniTradeERP`(Java)、`hiscaler/tongtool`(Go) 等。
- 判断：开源生态里“SP-API 客户端/SDK”很成熟，但“可直接生产部署的多店铺亚马逊 ERP”很少。本仓库的差异化价值在业务闭环、租户隔离、Outbox/通知入站、限流治理和连接器自检；短板仍是真实联调证据、外部多平台签名校准和大规模数据性能验证。

## 3. 升级方案：按波次推进（目标 = 可直接生产部署）

### Wave 0：冻结口径与上线阻断项（REL）

1. 统一对外口径：只有 E4/E5 联调记录才能写“已接通”；其余一律“具备对接能力（未联调）”。连接器中心只展示后端 `reachable=true` 且证据达标的连接器。
2. 部署基线：明确 `docker-compose.yml` 仅本地演示，不作为部署基线；补齐 `REDIS_HOST`、中间件密码、profile 注入，生产走 Kubernetes/Secret。
3. 启动守卫保持：默认 `prod`；离线演示必须显式 `mock`；`prod,mock` 混用拒绝启动；`prod` 下空/占位密码拒绝启动；`prod` 下端点覆盖非空拒绝启动。
4. 凭证治理：凭证只走环境变量/Secret/KMS，禁止入库脚本、日志、前端回显；V9 版本字段已用于 CAS，轮换走新版本而不是就地覆盖。
5. 初始化路径核查（已核查，结论：不是阻断项）：`docker-compose.yml` / k8s 只挂载 `docker/init-sql/01-init-databases.sql`（建 14 个空库），业务表全部由各服务 Flyway 迁移创建；`docker/init-sql-legacy/` **仅存档、不参与初始化**（README 已注明）。该存档目录里有 2 个文件含 MariaDB 专有语法 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`（19-init-tables-field-permission.sql、23-init-tables-procurement-upgrade.sql），MySQL 8.0 执行会报 ERROR 1064 并中止脚本——已在这两个文件头部加上 `[ARCHIVE ONLY]` + MySQL 8 改写示例，防止将来有人把它们挪回初始化路径。**因此 P0-39 只影响模拟数据生成的"条件列"，不影响部署启动。**

### Wave 1：SP-API 合规与联调底座（SP）

1. 凭证到位当天执行 `docs/superpowers/runbooks/connector-acceptance-runbook.md`：启动自检、每个启用 operation 至少一个成功样例、401/403/404/429 错误码覆盖、`x-amzn-RateLimit-Limit` 回填、Reports 文档链路闭环、脱敏规则。
2. RDT：`spapi.rdt` 开启后订单/订单项只走 RDT，失败不回退 LWA；RDT 按店铺 + marketplace + 资源集合隔离缓存，不持久化 token；受限订单字段落库前做脱敏与权限校验。
3. 通知入站：真实接入用 `SPAPI_NOTIFICATIONS_SOURCE=sqs` + 显式 queue/region + STS role（禁止店铺静态 AWS 密钥）；补齐订阅漂移对账、DLQ、幂等、按 `eventTime` 排序；评估 EventBridge 工作流作为第二入口。
4. 官方快照与 Usage Plan：继续用 `amzn/selling-partner-api-models` 锁定路径/版本/限流契约，新增 operation 必须同时更新快照、限流表与能力台账，并用双向一致性测试防漂移。

### Wave 2：业务事实模型与经营闭环（BIZ）

1. 订单/财务事实收敛：订单身份、订单明细、结算明细、费用差异、报销索赔、回款采集必须能按店铺 + 时间窗对账；金额统一 `BigDecimal`/定点类型，禁止 `double` 参与金额计算。
2. 财务闭环：Reports/Finances/Fees 与本地事实表对账，差异进差异表并支持重放；金蝶等总账同步去掉占位返回，补账套/期间/重试状态机。
3. 多平台连接器治理：1688/SHEIN/TEMU/TikTok/Amazon Ads 必须改成“按店铺取凭证”，删除 `cred(null)` 与全局 token 兜底；未校准签名在 `prod` 下 fail-closed，不能静默返回空列表/成功。
4. Listing/Catalog/Pricing/FBA Inbound：补齐字段契约测试与样例商品；失败显式分类，不做占位成功。

### Wave 3：性能与规模化（PERF）

依据已有审计 `docs/superpowers/specs/2026-09-26-performance-and-business-optimization-audit.md`：

1. 消灭硬编码截断与无界列表：所有列表接口统一分页/游标，返回 `hasMore/nextCursor`，禁止用截断伪装完整结果。
2. 写路径并发：Upsert、库存扣减、发货/租赁状态机使用 CAS/唯一约束/分布式锁 fail-closed，不能用应用内内存版本替代数据库原子操作。
3. 消灭循环内 DB 操作：批量化、批量 upsert、按店铺批处理；线程池必须有队列上限、拒绝策略与指标。
4. 凭证启动加载：沿用 keyset/lazy 分页读取，避免启动时全量加载店铺凭证；启动自检只读计数与状态哈希。
5. 外部 API 性能：按官方建议做缓存、错峰队列、批量/报表替代逐条查询；重试必须指数退避 + jitter + 最大延迟；429/5xx/Outbox/DLQ 都要有指标与告警。
6. 数据层：为时间窗、店铺、订单身份、库存快照、通知幂等键建复合索引；大表按时间分区/归档；压测目标写入 `loadtest/`，以 P95/P99 与错误率验收，不以“能跑”验收。

### Wave 4：生产运维与安全（OPS）

1. 密钥与审计：Secrets Manager/KMS 托管，凭证轮换与撤销有审计记录；管理端高危操作（凭证写入/删除、重放、订阅同步）记录操作者、来源 IP、请求 ID。
2. 可观测：Prometheus/Grafana/SkyWalking/Alertmanager 已有基础；补齐 SP-API 429 率、Outbox 积压与 DLQ、通知消费延迟、LWA/RDT 失败率、连接器自检趋势等 SLO 告警。
3. 备份与演练：数据库备份恢复演练、故障演练（Redis/MySQL/SQS 不可用）、回滚方案；首次部署用 `bootstrap` profile 导入凭证，不要用 `mock` 或空配置绕过。

### Wave 5：真实联调与试点发布（E4/E5）

1. 沙箱：注册沙箱应用、跑通授权与首批 operation，产出 A1–A8 证据文件。
2. 生产试点：单店铺/单 marketplace 灰度，监控限流与数据一致性，逐步扩量。
3. 只有在上述证据齐备后，才可以把 README/对外材料从“具备对接能力（未联调）”升级为“已联调/生产可用”。

## 4. 没有真实数据时：模拟数据填充方案

### 4.1 演示模式（推荐先用）

- 设置：`SPRING_PROFILES_ACTIVE=mock`（离线演示显式开启；`prod,mock` 会被拒绝）。
- 效果：mock 客户端产出确定性样例（订单、库存、财务结算 TSV、费用分档、通知 `order-change` 场景）；前端在后端不可达/非 200 时降级到内置样例数据并展示“示例数据”标识。
- 边界：这是演示与自测数据，不是联调证据；连接器中心仍不得显示“已接通”。

### 4.2 API-Ready 演练（用假凭证验证“有 API 就能接”，但不假装成功）

仅限非生产环境：

1. 用 ADMIN 写入模拟凭证：`PUT /api/credentials/shop/{shopId}` 或网关别名后面的 `/spapi/credentials/shop/{shopId}`；不要使用已废弃的 `/spapi/credential`。
2. 跑分层自检：`POST /api/preflight/shop/{shopId}?forceTokenRefresh=true`。
3. 期望结果：CREDENTIAL 段 PASS，LWA_TOKEN/READ_API 段 FAIL，`apiReady=false`、`reachable=false`；UI 显示“仍需通过真实 LWA/只读 API 自检”。
4. 若并发写入，验证冲突返回 `error.code=CONFLICT`，而不是后写覆盖先写。

### 4.3 业务模拟数据集：已落地（可生成 / 可加载 / 可清理 / 可识别）

工具链在 `tools/synthetic-data/`。本轮实测证据（可复现）：

| 项 | 结果 |
|---|---|
| 生成 | `generate.py --tier demo --reset` → 113/113 张表、14 个库、225,734 行；`ci` 档 25,484 行 |
| 确定性 | 同 `(dataset_id, seed, tier)` 两次生成 229 个文件字节一致 |
| 结构校验 | `verify.py` → structure / references / id types / markers / determinism / manifest / snapshot 全 OK |
| 可清理 | `purge.py --emit` → 113 条 DELETE；`verify_cleanup.py`（SQLite 内实跑 cleanup.sql）→ 删除 225,734 行、剩余 0 |
| 可识别 | `registry.sql` 登记到 `amz_ops.amz_synthetic_dataset_registry`（`is_demo=1`、ID 段、行数、seed） |
| 真实 schema 可加载性 | `verify_schema_load.py --tier demo`：由 `schema-snapshot.json` 重建 14 库 / 113 表，225,734/225,734 行灌入 0 错误；`--cleanup` 后 113 DELETE / 0 剩余；`test_schema_load.py` 11 项单测证明这些检查真的会失败 |
| 真实 MySQL 8 导入（P1） | `verify_import.py` 连本机一次性 MySQL **8.0.39**（端口 3399）：ci 25,574/25,574、demo 225,824/225,824（= 合成行 + 90 行迁移种子）；`--cleanup` 删除 25,484 / 225,734 行且剩余回到基线；同时读回 `amz_ops` 登记表。范围：本机一次性实例，**非生产环境** |

本轮修掉的两个真实缺陷（都是会静默出错的那类）：

1. **DDL 快照漂移**：`verify.py` 报 `snapshot FAIL` —— 新增迁移（spapi V9 凭证版本、order V5 order_item、ad V7 等，42→49 个文件）没进快照，生成的数据集会缺列。已重生成快照，并在 CI 加了 `snapshot_schema.py --check` 门禁。
2. **清理语句的标记守卫写死**：原实现输出 `AND source = 'SYNTHETIC'`，而生成器实际写 `SYN-source-000000`，导致 `amz_email_task`、`amz_settlement_detail`、`amz_logistics_quote`、`amz_inbound_order`、`amz_profit_snapshot`、`amz_purchase_plan` 六张表**一行都删不掉**（静默漏删）。已改为从数据推导前缀（`LIKE 'SYN-source-%'`），并用 SQLite 离线实跑验证剩余 0 行。

规模与标记（现实值，不是建议值）：

- 维度：demo 档 9 店铺（3 marketplace × 3）、10,000 订单、45 SKU/商品、18 用户；订单项 20,000 行、物流轨迹 30,000 行、拆分日志 100,000 行。
- marketplace 用官方 ID 列表前 N 个：`--marketplaces 3` = US/CA/MX；要覆盖 UK/DE/JP 需 `--marketplaces 10`（US,CA,MX,GB,FR,DE,ES,IT,AU,JP），列表见 `generate.py` 的 `MARKETPLACES`。
- 标记三层（schema 没有 `is_demo` 列，不为演示去改 113 张表）：① 保留 ID 段（多数表 `id >= 900000000000000000`；`amz_user` `100000001+`、`amz_product` `200000001+`）；② 文本标记（`SYNTHETIC…`/`SYN-…`/`1ZSYN…`、`example.invalid` 邮箱、555 电话）；③ 库级登记表 `amz_synthetic_dataset_registry.is_demo=1`。
- 真实统计口径用 ID 段排除（示例：`WHERE id NOT BETWEEN 900000000000700000 AND 900000000000709999`）；更稳的做法是演示库与真实库物理分离。
- 合成用户**不能登录**（`password` 是标记串），登录仍用 bootstrap 账号（`testuser`，id=1，已被提升为 ADMIN）；因此不要对 `amz_user` 用 truncate 清理。

填充方式（诚实边界）：

1. 大批量走 `load.ps1`（SQL 批量灌数），**绕过了应用层校验/审计/租户钩子**，只适合演示与压测基线。
2. 小批量冒烟（店铺、凭证、通知订阅、订单导入）走应用 API，确保校验与审计生效 —— 这部分本轮**没有**做，仍是待办。
3. 前端"降级样例"只在后端不可达/非 200 时出现并带"示例数据"角标；生产应关闭降级或改成明确的不可用态。

完整操作步骤、命令、安全规则与已知限制见 `docs/superpowers/runbooks/mock-data-seed-and-cleanup-runbook.md`。

离线证据分三层，逐层变强：（1）`verify.py` 证明数据自洽且可确定性重放；（2）`verify_cleanup.py` 证明清理能删干净（推断表结构）；（3）`verify_schema_load.py` 证明数据**符合真实 DDL 的列定义与约束**（类型/长度/精度/NOT NULL/日期/JSON/唯一键）。

**真机导入（P1，已完成）**：第四层证据已在**本机一次性 MySQL 8.0.39 实例**（仓库外 `C:\tools\mysql8`，端口 3399；由 49 个 Flyway 迁移建出 14 个库）拿到 —— `verify_import.py` 对 ci / demo 两档都跑通了「灌入 → 行数核对（基线 + manifest）→ `cleanup.sql` 删除并回到基线 → 读回登记表」。过程中修掉两个真缺陷：`verify_import.py` 把 MySQL 8 保留字 `rows` 当列名（报告 `registry` 恒为 null）；`registry.sql` 的 upsert 不刷新 `tier`（先 ci 后 demo 会留下 `tier=ci` + demo 行数的自相矛盾审计行），并把 `VALUES(col)` 换成 8.0.19+ 的行别名语法以消除 deprecation 警告。

**范围边界（措辞不可再升级）**：上面第四层是**本机一次性实例的导入验证**，不是生产环境验证 —— 实例无主从、无真实流量、未接入任何亚马逊凭证、未跑过应用层写入。因此对外只能说「具备对接能力（API-Ready，未联调）」，**不能**说「生产可部署」或「已接通 Amazon」；只有拿到真实凭证并完成 E4 沙箱 / E5 生产试点，才能升级措辞。

## 5. 验收门禁（每次改动后至少跑这些）

```powershell
# 后端定向契约
# 模拟数据工具链（本轮新增，CI 已接入）
cd tools/synthetic-data
python snapshot_schema.py --check          # DDL 漂移门禁
python -m unittest test_ddl_parser test_purge   # 7 个用例全过
python generate.py --tier ci --reset
python verify.py --tier ci --dataset out/ci     # 期望 PASS
python purge.py --tier ci --emit --registry     # 期望 exit 0、skipped=0
python verify_cleanup.py --tier ci              # 期望 0 rows left（需 pip install sqlglot）

mvn -pl amz-service/amz-service-spapi -am `
  '-Dtest=ShopCredentialStoreFailClosedTest,ShopCredentialAdminServiceTest,ShopCredentialControllerContractTest,ShopCredentialMapperContractTest,CredentialSchemaContractTest,SpapiControllerShopScopeContractTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test

# 后端 SP-API 全量
mvn -pl amz-service/amz-service-spapi -am test

# 前端类型检查 + 构建 + 全量测试
cd amz-frontend
node node_modules/vue-tsc/bin/vue-tsc.js --noEmit
node node_modules/vite/bin/vite.js build
node node_modules/vitest/vitest.mjs run
```

通过标准：后端 SP-API 全量 0 failures/0 errors（允许需要真实凭证的 `SpApiIntegrationTest` skipped）；前端 `vue-tsc` 无错误、构建成功、vitest 全绿；连接器中心不把模拟自检或 SKIP 显示成 PASS。

## 6. 成本、变量与被忽略的风险

- 时间成本最大的是平台侧审批/授权和联调，不是代码；代码当前已具备底座。
- 限流是动态的：不要硬编码 QPS/定时器；必须读 `x-amzn-RateLimit-Limit` 并支持 jitter 退避。
- 通知链路成本：SQS/IAM/订阅/幂等/DLQ 必须提前设计；FIFO 不适用于 SP-API 投递顺序。
- 模拟数据风险：演示样例与真实数据混淆会污染经营决策；必须打标、可清理、生产关闭降级。
- 多平台连接器：1688/SHEIN/TEMU/TikTok/Ads 当前存在未校准签名/全局凭证/静默失败，属于生产阻断项，不应与 SP-API 的“API-Ready”混为一谈。
- 性能结论必须来自压测（P95/P99、错误率、队列积压），不能来自小数据量“能跑通”。

## 7. 参考事实源

- `docs/superpowers/specs/2026-09-24-amazon-erp-production-design.md`
- `docs/superpowers/specs/2026-09-26-performance-and-business-optimization-audit.md`
- `docs/superpowers/specs/2026-09-26-production-upgrade-execution-matrix.md`
- `docs/superpowers/runbooks/connector-acceptance-runbook.md`
- `docs/superpowers/runbooks/first-deploy-bootstrap-runbook.md`
- Amazon SP-API：`Connecting to the Selling Partner API`、`Authorizing Selling Partner API applications`、`Usage Plans and Rate Limits`、`Strategies to Optimize Rate Limits for Application Workloads`、`Notifications API`
- 官方模型仓：`https://github.com/amzn/selling-partner-api-models`