# 连接器 API-Ready 基座（Phase 0-A）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让「提供凭证即可用」在 SP-API 主链路上真正成立——凭证表能自动创建、缺凭证显式失败、生产 profile 拒绝 mock、Reports/Feeds 闭环正确、限流按官方配额，并提供连接器能力清单与自检端点。第 13 轮追加边界：**“有凭证”必须同时包含“凭证能到达进程”**——16 份部署清单与代码占位符双向对齐（Task 8）、Redis 配置去掉硬编码公网地址（Task 9）。第 18 轮追加部署边界：**“能迁移”必须先于“能用凭证”**——Compose 只建空库、k8s 用 Job 建空库、表结构只由 Flyway 维护（Task 1 / Task 4 / Task 10）。第 19 轮追加取证边界：**“有 API 就能用”必须可取证**——端点覆盖仅限非生产且 fail-closed、签名必须有已知答案测试（KAT）、连接器状态必须带证据等级（Task 11）；A5 联调记录不可伪造。

**Architecture:** 不改变现有模块划分与调用方向；改动集中在 `amz-service/amz-service-spapi` 模块内部（凭证、限流、报表闭环、自检），跨模块仍只用既有 Feign 接口（product 的 `SpapiFeedsClient` ↔ spapi 的 `FeedsController`；finance 的 `SpApiFinanceClient` ↔ spapi 的 `FinancialDataController`）。所有新增 HTTP 端点必须带 `@RequireRole` 或 `@ShopScoped` 守卫。部署侧不改变代码调用方向，但明确禁止把表结构放进 MySQL 初始化目录；Compose 与 k8s 只负责创建空库，Flyway 是唯一建表事实源。

**Tech Stack:** Java 17、Spring Boot 3.3.5、MyBatis-Plus、Flyway 10.20（`flyway-core` + `flyway-mysql`）、dynamic-datasource、MySQL 8、Redis、JUnit 5 + Mockito（`spring-boot-starter-test`）。

**Spec:** `docs/superpowers/specs/2026-09-24-amazon-erp-production-design.md`（§1.9 A1–A8、§4.2、§4.6、§4.8 含 4.8.1 配置覆盖率实测、附录 A.3、附录 B，以及 P0-01 / P0-07 / P0-23 / P0-24 / P0-25 / P0-27 / P0-28 / P0-29 / P0-30 / P0-31 / P0-32 / P0-33 / P0-34）

## Global Constraints

- JDK 17；Maven 用 `C:\Users\Administrator\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd`，并设 `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp`（本机沙箱必需，否则测试失败）。
- 迁移文件只许递增新增：`V2__shop_credential.sql`、`V3__feed_result_error.sql`；**禁止**修改已发布的 `V1__init.sql`。
- 表结构只能由 Flyway 创建：`docker/init-sql/` 只保留 `01-init-databases.sql` 且该文件不得含 `CREATE TABLE`/`ALTER TABLE`/`INSERT`；k8s 建库 Job 同样只建空库。禁止再新增 `34_*.sql`、`35_*.sql` 之类的表结构镜像。
- 所有带 datasource 的 14 个服务显式配置 `spring.flyway.baseline-on-migrate: true` 与 `baseline-version: 1`：空库仍执行 V1；由旧 init SQL 建出的非空库以 V1 为基线后继续执行 V2+。
- 凭证敏感字段一律 AES-256-GCM 密文落库（`amz-common` 的 `CryptoUtil`），列名以 `_encrypted` 结尾，禁止明文列。
- 缺凭证 / 未启用**不得**返回空列表、null、`*_MOCK_` 占位单号（A2）；三者必须与"调用失败"用不同返回值表达。
- 新增端点必须带守卫注解；附录 F 的 82 条无守卫端点只能减少，不能新增。
- 每个 Task 先写失败测试再写实现，结束时单独提交；单模块测试命令统一为
  `mvn -B -ntp -pl amz-service/amz-service-spapi -am test -Dtest=<TestClass>`。
- 配置键名统一：Redis 一律走 `spring.data.redis.*`（环境变量 `SPRING_DATA_REDIS_HOST/PORT/PASSWORD`）；Nacos 一律 `NACOS_ADDR`，禁止 `NACOS_SERVER_ADDR` 双写；禁止任何默认值指向公网地址或第三方 IP。
- 部署清单以代码占位符为唯一事实源做**双向**差集校验（既不许缺、也不许多）；清单契约测试不可 skip。
- 端点覆盖（`spapi.base-url-override` / `spapi.lwa-endpoint-override`）只允许非生产 profile 生效；prod 下非空即启动失败；只允许 loopback 或显式 allowlist 主机，发往非 allowlist 主机时禁止携带 `x-amz-access-token`（Task 11）。
- SP-API 出站请求必须带合法 `user-agent`（≤500 字符，含 App 名 / 版本 / 语言，按官方转义规则拼接）；marketplace→region 只允许**单一事实源**，未知 ID **必须抛错**，禁止 `getOrDefault(..., "NA")` 式静默回落（spec §1.9.2、P0-35/P0-36，Task 11）。
- 参与签名的实现必须注入 `Clock`；**SP-API 的 AWS SigV4 自 2023-10-02 起已非必需**（spec §1.9.2，官方 changelog 逐字确认 Amazon 忽略该签名），因此本计划**必做**的契约测试是 `user-agent` 必填头、marketplace 全表 fail-closed 与 LWA token 交换；SigV4 KAT 为**可选项**，仅在保留签名器（前向保险）时要求。任何 KAT 的期望值只能来自官方文档或官方 SDK，**禁止**用本仓库实现生成期望值（Task 11）。
- 连接器状态必须带 `evidenceLevel`（E0–E5）：证据 < E3 或 A2/A3/A6 任一未通过 → `apiReady=false`；证据 < E4 不得显示“已接通”（Task 6 / Task 11，口径见 spec §1.9.1）。
- 本计划只是计划：**未经批准不落实现代码**。

---

## 文件结构（先定边界，再写任务）

| 动作 | 路径 | 职责 |
|---|---|---|
| Create | `amz-service/amz-service-spapi/src/main/resources/db/migration/V2__shop_credential.sql` | SP-API 凭证表，进入 Flyway 唯一入口 |
| Create | `amz-service/amz-service-spapi/src/main/resources/db/migration/V3__feed_result_error.sql` | Feed 结果报告逐行错误清单 |
| Modify | `docker/init-sql/01-init-databases.sql` | 唯一建库入口：只建 14 个空库（含 `amz_report`），不建表 |
| Delete | `docker/init-sql/02-…33-*.sql`、`init_all_tables.sql` | 旧表结构镜像退役；表结构只由 Flyway 维护（Task 1 / Task 10） |
| Create | `k8s/infra/mysql-init-job.yaml` | k8s 幂等建库 Job，只创建空库（Task 10） |
| Create | `amz-service/amz-service-spapi/src/main/java/com/amz/credential/ConnectorStartupCheck.java` | 生产启动自检（缺凭证/未启用即拒绝启动） |
| Create | `amz-service/amz-service-spapi/src/main/java/com/amz/connector/ConnectorRegistry.java` | 连接器能力清单与启用状态的事实源 |
| Create | `amz-service/amz-service-spapi/src/main/java/com/amz/controller/ConnectorController.java` | `GET /api/connectors`、`POST /api/connectors/{code}/self-test` |
| Modify | `amz-service/amz-service-spapi/src/main/java/com/amz/client/ReportsRealClient.java:80` | 字段名 `resultDocumentId` → `reportDocumentId` |
| Modify | `amz-service/amz-service-spapi/src/main/java/com/amz/client/FeedsClient.java` | 补结果报告下载与逐行错误解析 |
| Modify | `amz-service/amz-service-spapi/src/main/java/com/amz/ratelimit/SpiRateLimiter.java` | 逐 operation 官方配额、按店铺隔离、去锁内 sleep、可恢复 |
| Modify | `amz-service/amz-service-spapi/src/main/resources/application.yml`、新增 `application-prod.yml` | profile 与自检开关 |
| Modify | `.env.example`、`docker-compose.yml`（16 个业务服务段）、`k8s/secret.yaml`、`k8s/configmap.yaml`、`k8s/services/*.yaml`（16 份逐份对齐） | 配置三处双向对齐（Task 8） |
| Modify | `amz-service/amz-service-order/src/main/java/com/amz/config/RedissonConfig.java:16-22`、`amz-service/amz-service-product/src/main/java/com/amz/config/RedissonConfig.java:16-22` | 移除公网 Redis 默认值，改走 `spring.data.redis.*`（Task 9） |
| Create | `amz-service/amz-service-spapi/src/test/resources/contracts/reports_2021-06-30.json` | 官方模型快照（Apache-2.0，锁 commit） |
| Create | `amz-service/amz-service-spapi/src/main/java/com/amz/connector/MarketplaceRegistry.java` | 23 条 marketplaceId→region→host 的**单一事实源**，未知 ID 抛错（fail-closed）（Task 11） |
| Create | `amz-service/amz-service-spapi/src/main/java/com/amz/auth/SpApiUserAgent.java` | 按官方转义规则构造必填 `user-agent`（≤500 字符）（Task 11） |
| Create | `amz-service/amz-service-spapi/src/test/resources/contracts/lwa-token/`（README + 夹具 JSON） | LWA token 交换契约夹具（期望值取自官方文档，锁 sha256）（Task 11） |
| Create（可选） | `amz-service/amz-service-spapi/src/test/resources/contracts/sigv4-kat/`（README + 夹具 JSON） | SigV4 前向保险的已知答案测试夹具（AWS 官方 `aws4_testsuite`，锁 sha256）；**非必需项**（Task 11） |
| Create | `amz-service/amz-service-spapi/src/main/java/com/amz/connector/ConnectorEvidencePolicy.java` | 证据等级 E0–E5 与 `apiReady` 判定规则的唯一事实源（Task 11） |
| Create | `docs/superpowers/runbooks/connector-acceptance-runbook.md` | 凭证到位当天的一次性验收 runbook（A5 取证）（Task 11） |
| Modify | `OrdersClient.java:61-65`、`FeedsClient.java:61-65`、`FbaInventoryClient.java:56-60`、`SpApiGateway.java:45-49`、`AwsSigV4Signer.java:54`、`LwaTokenManager.java:59-61`、`amz-service-spapi/src/main/resources/application.yml:69` | 端点覆盖（非生产、fail-closed）与注入 `HttpClient`；`Clock` 注入为**可选**前向保险（Task 11） |
| Create | `amz-service/amz-service-spapi/src/test/java/...`（见各 Task） | 契约测试与行为测试 |

---

### Task 1: 凭证表进入唯一迁移入口，并补齐所有业务库建库基线

**Files:**
- Create: `amz-service/amz-service-spapi/src/main/resources/db/migration/V2__shop_credential.sql`
- Modify: `docker/init-sql/01-init-databases.sql`
- Modify: `docker-compose.yml`（mysql 初始化挂载只保留 `01-init-databases.sql`）
- Create: `amz-service/amz-service-spapi/src/test/java/com/amz/credential/CredentialSchemaContractTest.java`
- Create: `amz-service/amz-service-spapi/src/test/java/com/amz/deploy/DatabaseBootstrapContractTest.java`
- Read（勿改）: `amz-service/amz-service-spapi/src/main/resources/db/schema.sql:11-24`

**Interfaces:**
- Produces: 表 `amz_shop_credential`（列 `shop_id` PK、`client_id`、`client_secret_encrypted`、`refresh_token_encrypted`、`access_key_encrypted`、`secret_key_encrypted`、`region`、`marketplace_id`、`seller_id`、`create_time`、`update_time`），供 Task 2、Task 6 使用。
- Produces: 唯一建库入口 `01-init-databases.sql`，只创建 14 个空库：`amz_user`、`amz_product`、`amz_order`、`amz_search`、`amz_spapi`、`amz_ad`、`amz_procurement`、`amz_customer`、`amz_logistics`、`amz_ops`、`amz_finance`、`amz_multiplatform`、`amz_ai`、`amz_report`。**不得包含任何建表/改表/数据写入语句**。

- [ ] **Step 1: 写失败测试**

`CredentialSchemaContractTest` 从仓库根（`Paths.get(System.getProperty("user.dir")).getParent().getParent()`）读取 `V2__shop_credential.sql` 与 `db/schema.sql:11-24`，断言：① V2 含 `CREATE TABLE IF NOT EXISTS amz_shop_credential`；② 从两处抽取的列名集合完全一致；③ `docker/init-sql/**/*.sql` 中没有任何一份复制该表的 `CREATE TABLE`。

`DatabaseBootstrapContractTest` 断言：① `01-init-databases.sql` 的 `CREATE DATABASE` 集合恰好等于上述 14 个库；② 其内容不含 `CREATE TABLE`、`ALTER TABLE`、`INSERT`、`UPDATE`、`DELETE`、`DROP`；③ `docker-compose.yml` 的 mysql 初始化挂载是单文件 `./docker/init-sql/01-init-databases.sql:/docker-entrypoint-initdb.d/01-init-databases.sql:ro`，不是目录挂载；④ `docker/init-sql/` 下只允许这一份 `.sql`。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -B -ntp -pl amz-service/amz-service-spapi -am test -Dtest='CredentialSchemaContractTest,DatabaseBootstrapContractTest'`
Expected: FAIL（V2 不存在；`amz_report` 未建；Compose 挂载整个目录；`docker/init-sql` 仍有 31 份旧表结构脚本）

- [ ] **Step 3: 新增 Flyway V2 迁移**

内容取 `db/schema.sql:11-24` 的 DDL，改为 `CREATE TABLE IF NOT EXISTS`，字符集 `utf8mb4`；不写 `DROP`。V2 是 `amz_shop_credential` 的唯一建表入口。

- [ ] **Step 4: 收敛建库入口并退役旧镜像**

把 `01-init-databases.sql` 改成只创建 14 个空库并补齐 `amz_report`；删除 `docker/init-sql/02-…33-*.sql` 与根目录 `init_all_tables.sql`（Git 历史仍保留原始内容，不再作为可执行入口）。修改 `docker-compose.yml` 只挂载 `01-init-databases.sql`。**不要新增 `34_amz_shop_credential.sql` 或 `35_amz_feed_result_error.sql`。**

- [ ] **Step 5: 运行测试通过**

Run: `mvn -B -ntp -pl amz-service/amz-service-spapi -am test -Dtest='CredentialSchemaContractTest,DatabaseBootstrapContractTest'`
Expected: PASS

- [ ] **Step 6: 记录真实启动验证（本机当前无 MySQL 时必须标记未验证）**

在 Task 完成说明中写清预期验证命令与结果位置：全新卷启动 MySQL 后，每个业务库都应由自身 Flyway 生成 `flyway_schema_history`，`amz_spapi` 在 V1 之后执行 V2；用 `SHOW TABLES LIKE 'amz_shop_credential'` 验证迁移落在 `amz_spapi` 的 master 节点。**不要用旧 init SQL 建表后再声称验证通过。**

- [ ] **Step 7: 提交**

`git add` 上述迁移、建库 SQL、Compose 与测试；`git commit -m "fix(spapi,deploy): 凭证表进入 Flyway 唯一入口，Compose 只建空库并补 amz_report"`

---

### Task 2: 生产 profile 与启动自检（fail-closed）

**Files:**
- Create: `amz-service/amz-service-spapi/src/main/java/com/amz/credential/ConnectorStartupCheck.java`
- Create: `amz-service/amz-service-spapi/src/main/resources/application-prod.yml`
- Modify: `amz-service/amz-service-spapi/src/main/resources/application.yml:3-7`（保留 mock 默认，新增自检开关与说明）
- Test: `amz-service/amz-service-spapi/src/test/java/com/amz/credential/ConnectorStartupCheckTest.java`

**Interfaces:**
- Consumes: Task 1 的表与既有 `ShopCredentialStore`（`loadFromDb()`、`get(shopId)`）。
- Produces: `ConnectorStartupCheck.verify()`（`@PostConstruct` 调用）在 `spapi.startup.require-credentials=true` 时：mock profile → 抛 `IllegalStateException`；凭证表为空 → 抛 `IllegalStateException`；通过则记录每个连接器的启用状态供 Task 6 读取。

- [ ] **Step 1: 写失败测试**

`ConnectorStartupCheckTest` 三个用例：①`require-credentials=true` + `activeProfiles=mock` → 抛异常且消息含 `mock`；②`require-credentials=true` + 凭证表空 → 抛异常且消息含 `amz_shop_credential`；③`require-credentials=false` → 不抛异常。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -B -ntp -pl amz-service/amz-service-spapi -am test -Dtest=ConnectorStartupCheckTest`
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现自检组件**

读取 `spring.profiles.active`、`spapi.startup.require-credentials`；缺凭证信息从 `ShopCredentialStore` 暴露的只读方法获取（若现在没有，允许新增 `int loadedCount()`，禁止把凭证内容暴露给调用方）。

- [ ] **Step 4: 新增 `application-prod.yml`**

至少包含 `spapi.startup.require-credentials: true`，并注释说明：生产 profile 必须显式设置，禁止继承 `mock`。

- [ ] **Step 5: 运行测试通过**

Run: `mvn -B -ntp -pl amz-service/amz-service-spapi -am test -Dtest=ConnectorStartupCheckTest`
Expected: PASS

- [ ] **Step 6: 提交**

`git commit -m "feat(spapi): 生产启动自检——缺凭证与 mock profile 拒绝启动"`
---

### Task 3: Reports 字段名修正 + 官方模型契约测试

**Files:**
- Create: `amz-service/amz-service-spapi/src/test/resources/contracts/reports_2021-06-30.json`
- Create: `amz-service/amz-service-spapi/src/test/resources/contracts/README.md`
- Create: `amz-service/amz-service-spapi/src/test/java/com/amz/client/ReportsFieldContractTest.java`
- Modify: `amz-service/amz-service-spapi/src/main/java/com/amz/client/ReportsRealClient.java:80`
- Modify: `amz-service/amz-service-product/src/main/java/com/amz/client/impl/ListingsMockClient.java:43`

**Interfaces:**
- Produces: 契约测试断言「源码中出现的响应字段名 ⊆ 官方模型属性集」，供后续所有连接器复制该模式（附录 G.4 教训 2）。

- [ ] **Step 1: 落官方模型快照**

从 `https://raw.githubusercontent.com/amzn/selling-partner-api-models/main/models/reports-api-model/reports_2021-06-30.json` 下载（Apache-2.0），在 `contracts/README.md` 记录：来源 URL、抓取日期、**字节数**、sha256、许可证。**锁定文件内容，不做任何改写。**

2026-09-24 实测基准（写进 `contracts/README.md`，测试直接断言字节数与 sha256；**不锁 commit SHA**——上游 `main` 会漂移，被替换的 commit 可能被 GC，只有内容哈希能自证）：

| 模型文件（仓库内相对路径） | 字节数 | sha256 |
|---|---|---|
| `models/reports-api-model/reports_2021-06-30.json` | 83,685 | `d72db9e5280262a92933a0e45e2207c150272f1d66177b2517c20671d69c732c` |
| `models/feeds-api-model/feeds_2021-06-30.json` | 55,901 | `ab235b4a0e5ce21083b885dd4f2b8cae7a6f597d7adf2647b47b90d6f5098a16` |
| `models/orders-api-model/ordersV0.json` | 226,555 | `027ac6f5c97126647c6925db9be09f78c7c741cd1d8727a5367374a1846bedc5` |
| `models/product-fees-api-model/productFeesV0.json` | 49,426 | `d06ad35f909d8c0985845f21420c1f75599531465b27f46d4946f7d7f522fc35` |
| `models/finances-api-model/financesV0.json` | 134,109 | `d80e881091367b0eccd4bde3ce834ed08877d3cf51095239eb8b1e328c0d19d6` |
| `models/fba-inventory-api-model/fbaInventory.json` | 36,985 | `7c14bcdb22de8ca2df45e5a40f2a422cff344d45985a68b9515b2e800edcc5ab` |

抓取陷阱（实测）：`models/fba-inventory-api-model/` 下 `fbaInventory_2020-10-01.json` 与 `inventory_2020-10-01.json` 都只返回 **14 字节**的 `404: Not Found` 响应体；必须校验字节数与 sha256，不能只看 HTTP 状态码。

- [ ] **Step 2: 写失败测试**

`ReportsFieldContractTest`：读取官方模型 JSON，取 `definitions.Report.properties` 与 `definitions.ReportDocument.properties`；正则扫描 `ReportsRealClient.java` 里所有 `str(<var>, "<literal>")` 的字面量，断言每个字面量都存在于官方属性集；并断言源码中**不含** `resultDocumentId`。

- [ ] **Step 3: 运行确认失败**

Run: `mvn -B -ntp -pl amz-service/amz-service-spapi -am test -Dtest=ReportsFieldContractTest`
Expected: FAIL（`resultDocumentId` 不在官方属性集内）

- [ ] **Step 4: 改字段名**

`ReportsRealClient.java:80`：`str(resp,"resultDocumentId")` → `str(resp,"reportDocumentId")`；同步修正 product 模块 `ListingsMockClient.java:43` 的同名字段，保证 mock 与 real 使用同一字段名。

- [ ] **Step 5: 运行测试通过 + 回归两个模块**

Run: `mvn -B -ntp -pl amz-service/amz-service-spapi -am test -Dtest=ReportsFieldContractTest`
Run: `mvn -B -ntp -pl amz-service/amz-service-finance -am test`（结算链路消费方）
Run: `mvn -B -ntp -pl amz-service/amz-service-product -am test`
Expected: 三个命令均 BUILD SUCCESS

- [ ] **Step 6: 提交**

`git commit -m "fix(spapi): Reports 文档 ID 字段名对齐官方模型，并加契约测试"`

---

### Task 4: Feeds 结果报告闭环（拒绝行可查询）

**Files:**
- Create: `amz-service/amz-service-spapi/src/main/resources/db/migration/V3__feed_result_error.sql`
- Modify: `amz-service/amz-service-spapi/src/main/java/com/amz/client/FeedsClient.java`
- Modify: `amz-service/amz-service-product/src/main/java/com/amz/service/impl/ListingCopyService.java:236-256`
- Test: `amz-service/amz-service-spapi/src/test/java/com/amz/client/FeedsResultDocumentTest.java`、`amz-service/amz-service-spapi/src/test/resources/feeds/result-sample.json`

**Interfaces:**
- Consumes: 官方 `Feed.resultFeedDocumentId`、`FeedDocument.url`、`FeedDocument.compressionAlgorithm=GZIP`（已核验）。
- Produces: `FeedsClient#fetchFeedResult(String feedId)` → `FeedResult(rows, errors)`；表 `amz_feed_result_error`（`feed_id`、`shop_id`、`row_index`、`seller_sku`、`error_code`、`error_message`、`raw_row`、`create_time`）。

- [ ] **Step 1: 写失败测试**

用 `result-sample.json` 夹具（**标注 SYNTHETIC**，形状与官方 `processingReport` 一致：`summary` + `issues`）断言：①解析出 N 条拒绝行且字段映射正确；②`errors` 非空时返回结果不含 `SUCCESS`；③GZIP 文档能被解压。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -B -ntp -pl amz-service/amz-service-spapi -am test -Dtest=FeedsResultDocumentTest`
Expected: FAIL（`fetchFeedResult` 不存在）

- [ ] **Step 3: 新增 V3 迁移（唯一入口）**

表 `amz_feed_result_error`，主键 `id BIGINT AUTO_INCREMENT`，唯一键 `(feed_id, row_index)`，索引 `(shop_id, create_time)`。只修改 `db/migration/V3__feed_result_error.sql`；不新增 `docker/init-sql/35_*.sql`，旧表结构镜像已在 Task 1 退役。

- [ ] **Step 4: 实现下载与解析**

`FeedsClient` 补两条调用：`GET /feeds/2021-06-30/feeds/{feedId}`（取 `resultFeedDocumentId`）与 `GET /feeds/2021-06-30/documents/{feedDocumentId}`（取 `url`、`compressionAlgorithm`）；下载后按 `GZIP` 解压并解析 JSON/CSV，拒绝行落库。**网络调用失败必须抛业务异常，不得返回空结果。**

- [ ] **Step 5: 收敛状态机**

`ListingCopyService`：Feed `DONE` 后必须调用 `fetchFeedResult`；`errors` 为空且行数大于 0 → `SUCCESS`；存在拒绝行 → `PARTIAL`/`FAILED` 并写入错误清单；超时不得停在 `SUBMITTED`（按新状态 `TIMEOUT` 或保留可重试态）。

- [ ] **Step 6: 运行测试通过**

Run: `mvn -B -ntp -pl amz-service/amz-service-spapi -am test -Dtest=FeedsResultDocumentTest`
Run: `mvn -B -ntp -pl amz-service/amz-service-product -am test`
Expected: 均 BUILD SUCCESS

- [ ] **Step 7: 提交**

`git commit -m "feat(spapi): Feeds 结果报告下载与拒绝行落地，收敛 Listing 拷贝状态机"`

---

### Task 5: 限流按官方 usage plan + 按店铺隔离 + 去锁内 sleep

**Files:**
- Modify: `amz-service/amz-service-spapi/src/main/java/com/amz/ratelimit/SpiRateLimiter.java:37-148`
- Create: `amz-service/amz-service-spapi/src/main/java/com/amz/ratelimit/UsagePlan.java`
- Test: `amz-service/amz-service-spapi/src/test/java/com/amz/ratelimit/SpiRateLimiterTest.java`

**Interfaces:**
- Produces: `UsagePlan`（`ratePerSecond`、`burst`、`operationId`）与 `SpiRateLimiter#acquire(Long shopId, String operationId)`；`updateLimit(shopId, operationId, observedRate)` 只影响该店铺该 operation，且可在观测值回升时恢复至官方默认上限。

- [ ] **Step 1: 写失败测试**

四个用例：①按官方值断言 `orders.getOrders=0.0167/20`、`reports.createReport=0.0167/15`、`reports.getReport=2/15`、`reports.getReportDocument=0.0167/15`、`feeds.createFeedDocument=0.5/15`、`feeds.createFeed=0.0083/15`、`fees.getMyFeesEstimates=0.5/1`、`fbaInventory.getInventorySummaries=2/2`（**2026-09-24 按官方模型逐项复核并锁定 sha256；本计划前稿此处的 reports / feeds / fees 断言值是错的**）；②店铺 A 触发收紧不影响店铺 B；③A 店 sleep 期间 B 店可立即获得许可（验证未持锁睡眠）；④`updateLimit` 收到更高观测值后可恢复。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -B -ntp -pl amz-service/amz-service-spapi -am test -Dtest=SpiRateLimiterTest`
Expected: FAIL（策略仍为 endpoint 维度、默认值宽松）

- [ ] **Step 3: 实现 operation 维度策略表**

`policies` 键改为 `operationId`（如 `orders.getOrders`）；`windows` 键改为 `shopId:operationId`；删除 `listings` 死策略或补齐调用方。

`UsagePlan` 必须支持可选 `feedType` 维度：官方 `createFeed` 的 `description` 原文写明 `JSON_LISTINGS_FEED` 的限流与 `createFeed` operation **不同**（具体配额见 *Building Listings Management Workflows Guide*，本轮抓取 developer-docs 失败，**不得填入猜测值**）。本仓库 `FeedsClient.java:53` 硬编码该 feedType，因此 `feeds.createFeed` 策略至少要以注释标注该差异，拿到官方数值后立即分档。

参照实现（已逐行核验）：`wimoor-amazon/amazon-boot/src/main/java/com/wimoor/amazon/auth/pojo/entity/AmazonAuthority.java:206-240`（读 `x-amzn-RateLimit-Limit` → 解析 → 回写每店铺门控实体）与同目录 `AmzAuthApiTimelimit.java:70-77`（放行条件 `restore == null || 距上次放行秒数 × restore > 1`）。**不要抄**：`AmazonAuthority.java:344-348` 的 `getTimeOut()` 返回 `Long.MAX_VALUE`，以及 `amazon-sp-api/src/main/java/com/amazon/spapi/SellingPartnerAPIAA/RateLimitConfigurationOnRequests.java:36-37` 的空实现 `return null`。

- [ ] **Step 4: 去锁内 sleep + 可恢复**

`acquire()`：计算等待时间后在**锁外**等待，循环重试；`updateLimit()` 允许在观测值高于当前值时上调，但不超过官方默认 burst。

- [ ] **Step 5: 运行测试通过**

Run: `mvn -B -ntp -pl amz-service/amz-service-spapi -am test -Dtest=SpiRateLimiterTest`
Expected: PASS

- [ ] **Step 6: 提交**

`git commit -m "perf(spapi): 限流改为逐 operation 官方配额与按店铺隔离，去掉锁内 sleep"`
---

### Task 6: 连接器能力清单与自检端点

**Files:**
- Create: `amz-service/amz-service-spapi/src/main/java/com/amz/connector/ConnectorRegistry.java`
- Create: `amz-service/amz-service-spapi/src/main/java/com/amz/controller/ConnectorController.java`
- Test: `amz-service/amz-service-spapi/src/test/java/com/amz/controller/ConnectorControllerGuardTest.java`、`amz-service/amz-service-spapi/src/test/java/com/amz/connector/ConnectorRegistryTest.java`

**Interfaces:**
- Consumes: Task 2 的启动自检结果、`ShopCredentialStore`。
- Produces: `GET /api/connectors` → `[{code, enabled, credentialSource(env|db|vault|none), lastCallAt, lastResult, operations[], evidenceLevel(E0..E5), apiReady}]`；`POST /api/connectors/{code}/self-test` → 脱敏请求/响应摘要与错误码。判定规则由 `ConnectorEvidencePolicy` 唯一定义（spec §1.9.1：证据 < E3 或 A2/A3/A6 任一未通过 → `apiReady=false`；证据 < E4 不得显示“已接通”）。

- [ ] **Step 1: 写失败测试**

`ConnectorControllerGuardTest`：反射扫描 `ConnectorController` 的所有 `@*Mapping` 方法，断言每个方法都带 `@RequireRole` 或 `@ShopScoped`（防止新增无守卫端点）。`ConnectorRegistryTest`：断言清单中的 operation 白名单与 1.4.1 能力缺口扫描一致（未实现的能力必须是"未实现"而不是缺字段）。**另加一条既有端点的回归断言（第 22 轮 code review 遗留，勿丢）**：`amz-service-spapi/.../controller/FeedsController.java:47-49` 的 `submit` 属附录 F 的 C 类（无方法级 `@ShopScoped`，归属校验只靠方法内 `UserContext.isShopAllowed(request.getShopId())`）；实现时给该方法补一行注释说明"此处 `isShopAllowed` 即店铺归属校验"，并在本 Task 的守卫测试中断言 C 类端点仍然保留 `isShopAllowed` 调用，防止后续重构把唯一校验删掉。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -B -ntp -pl amz-service/amz-service-spapi -am test -Dtest=ConnectorControllerGuardTest+ConnectorRegistryTest`
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现 Registry 与 Controller**

自检调用最小只读 operation（SP-API：marketplace participations；后续连接器按 4.8 表）。响应体**禁止**包含任何凭证明文；错误码原样透传平台返回（401/403/429）。

- [ ] **Step 4: 运行测试通过**

Run: `mvn -B -ntp -pl amz-service/amz-service-spapi -am test -Dtest=ConnectorControllerGuardTest+ConnectorRegistryTest`
Expected: PASS

- [ ] **Step 5: 记录网关与前端接线（本 Task 不实现）**

在完成说明中写明：网关路由与前端"已对接/未接通"标签改读该接口，属 Plan 2 范围。

- [ ] **Step 6: 提交**

`git commit -m "feat(spapi): 连接器能力清单与自检端点（带守卫与反射测试）"`

---

### Task 7: 静态降级清理（Keepa / 17TRACK / 金蝶）

**Files:**
- Modify: `amz-service/amz-service-product/src/main/java/com/amz/client/impl/KeepaRealClient.java:27,47-66`
- Modify: `amz-service/amz-service-logistics/src/main/java/com/amz/client/LogisticsTrackingRealClient.java`、`LogisticsTrackingProperties.java:28,42`
- Modify: `amz-service/amz-service-finance/src/main/java/com/amz/client/KingdeeRealClient.java:27,48-51`、`amz-service/amz-service-finance/src/main/java/com/amz/service/impl/FinanceServiceImpl.java:190-206`
- Test: 三个模块各新增一个"缺凭证必须失败"的用例

**Interfaces:**
- Produces: 统一的"未启用"表达（`ConnectorDisabledException` 或既有 `Result` 错误码），与"调用失败"区分；金蝶同步状态机允许从 `SYNCING` 按租约重试。

- [ ] **Step 1: 写失败测试**

①Keepa 无 key → 抛/返回明确错误，断言**不是** `null`；②17TRACK `enabled=false` → 返回"未启用"标识而非空轨迹；`enabled=true` 且调用失败 → 抛异常；③金蝶 `RealClient` 不再返回 `KINGDEE_MOCK_*`，且 `SYNCING` 超时后允许重试。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -B -ntp -pl amz-service/amz-service-product -am test`、`-pl amz-service/amz-service-logistics -am test`、`-pl amz-service/amz-service-finance -am test`
Expected: 新增用例 FAIL

- [ ] **Step 3: 逐模块实现**

删除静默 `return null` / `emptyList()` / 占位单号路径；金蝶改为真实失败并修正状态机（允许 `SYNCING` 超时重试，避免 P0-22 的死锁）。

- [ ] **Step 4: 运行测试通过**

Run: 与 Step 2 相同三条命令
Expected: 均 BUILD SUCCESS

- [ ] **Step 5: 提交**

`git commit -m "fix(connectors): 缺凭证显式失败，移除 Keepa/17TRACK/金蝶静默降级"`

---

### Task 8: 配置与部署清单双向对齐（16 模块，不只 spapi 一段）

**Files:**
- Modify: `.env.example`、`docker-compose.yml`（**全部 16 个业务服务段**）、`k8s/configmap.yaml`、`k8s/secret.yaml`、`k8s/services/*.yaml`（16 份逐份对齐）
- Create: `amz-service/amz-service-spapi/src/test/java/com/amz/deploy/DeploymentManifestContractTest.java`（清单侧断言）
- Create: `amz-service/amz-service-spapi/src/test/java/com/amz/deploy/PlaceholderCoverageContractTest.java`（代码侧占位符扫描 + 双向差集）

**Interfaces:**
- Produces: CI 可拦四类回归——清单缺变量、清单多变量、键名不一致、密钥长度非法（对应 P0-01 / P0-25 / P0-29 / P0-32）。

- [ ] **Step 1: 写失败测试**

`DeploymentManifestContractTest` 断言：
① 16 份 `k8s/services/*.yaml` 的 Deployment `env` **逐模块**覆盖该模块 `src/main/resources/*.yml` 的全部 `${UPPER_SNAKE}` 占位符，且不包含未被读取的多余项；
② 每份 Deployment 显式包含 `SPRING_PROFILES_ACTIVE`（取值 `prod`，spapi 另有 `application-prod.yml`，见 Task 2）；禁止依赖 `JAVA_OPTS` 传递 profile；
③ `docker-compose.yml` 的 16 个业务服务段满足①②，且 `env_file` 策略显式（统一 `env_file: .env` 或逐项注入，二选一，不允许“看似会加载、实际没有”）；
④ `.env.example` 覆盖全部非 Secret 键，且与 k8s ConfigMap 键集合一致；
⑤ `k8s/secret.yaml` 的 `AMZ_CRYPTO_KEY` base64 解码后**恰好 32 字节**，`JWT_SECRET_KEY` 满足 `JwtUtil` 长度要求；
⑥ **report 数据源断言**：`amz-service-report` 有 6 个 MyBatis `BaseMapper`、6 个 `@TableName`、Flyway V1 与 MySQL 依赖，必须补齐 `jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/amz_report`、`DB_USERNAME`、`DB_PASSWORD`；不得按“无 DB 模块”删掉 k8s 中对应注入。反向断言只针对真正没有 JDBC/MyBatis 的模块。

`PlaceholderCoverageContractTest` 用正则 `\$\{([A-Z][A-Z0-9_]*)(?::([^}]*))?\}` 提取占位符，**必须显式剔除 `application-local.yml`**——第 13 轮已实测 `MQ_USERNAME`/`MQ_PASSWORD` 只出现在该文件，未剔除会产生假阳性。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -B -ntp -pl amz-service/amz-service-spapi -am test -Dtest='DeploymentManifestContractTest,PlaceholderCoverageContractTest'`
Expected: FAIL（当前 15/16 份 Deployment 缺 `NACOS_ADDR`；logistics 缺 15 项、search 缺 10 项、product 缺 7 项、user/procurement 各缺 5 项；Compose `REDIS_HOST` 0 命中、`MYSQL_HOST` 仅 spapi；report 代码已写 6 张表却缺 datasource）

- [ ] **Step 3: 按实测差集修正三处**

先给 `amz-service-report` 补 datasource 配置（`amz_report` + `MYSQL_HOST`/`MYSQL_PORT`/`DB_USERNAME`/`DB_PASSWORD`），再以代码占位符为唯一事实源，逐模块补齐 `NACOS_ADDR`、`SPRING_PROFILES_ACTIVE`、`AD_PROFILE_ID`、`MONGO_HOST`、`OSS_ACCESS_KEY_ID/SECRET/BUCKET_NAME`、`ES_URIS`、`EMBEDDING_API_KEY/API_URL/ENABLED/MODEL`、`AMZ_17TRACK_BASE_URL/KEY`、`AMZ_LOGISTICS_*`、`AMZ_TRACKING_ENABLED`、`ALIBABA_APP_KEY/APP_SECRET/REFRESH_TOKEN`、`KINGDEE_APP_ID/APP_SECRET`、`AGENT_AI_CHAT_URL`、`SENTINEL_DASHBOARD` 等缺失键；16 份统一为 `NACOS_ADDR`（删除 `NACOS_SERVER_ADDR` 不一致用法）；Compose 补齐 `MYSQL_HOST`/`REDIS_HOST`/`RABBITMQ_HOST` 到所有依赖模块；Secret 改为外部注入（Sealed Secret / External Secrets / KMS），仓库内只保留长度合法的占位。

- [ ] **Step 4: 运行测试通过**

Run: 同 Step 2
Expected: PASS

- [ ] **Step 5: 提交**

`git commit -m "chore(deploy): 16 模块清单双向对齐 + profile/密钥契约测试"`

---

### Task 9: Redis / Redisson 配置基线（移除硬编码公网地址）

**Files:**
- Modify: `amz-service/amz-service-order/src/main/java/com/amz/config/RedissonConfig.java:16-22`、`amz-service/amz-service-product/src/main/java/com/amz/config/RedissonConfig.java:16-22`
- Modify: `amz-service/amz-service-order/src/main/resources/application.yml`、`amz-service/amz-service-product/src/main/resources/application.yml`（确认 `spring.data.redis.*` 为唯一配置源；两模块现有 17 处 `redis:` 块父级均已是 `spring.data.redis`）
- Create: `amz-service/amz-service-order/src/test/java/com/amz/config/RedissonConfigTest.java`、`amz-service/amz-service-product/src/test/java/com/amz/config/RedissonConfigTest.java`

**Interfaces:**
- Produces: Redisson 解析出的 host/port/password 与 `spring.data.redis.*`（及 `SPRING_DATA_REDIS_*` 环境变量）一致；仓库内不存在任何指向公网 IP 的默认值。
- 依赖：本 Task 与 Task 8 的 Compose `REDIS_HOST` 注入必须一起验证，否则测试环境仍解析不到地址。

- [ ] **Step 1: 写失败测试**

`RedissonConfigTest` 用 `ApplicationContextRunner`（或 `ReflectionTestUtils` 读取 `@Value` 字段）断言：① `spring.data.redis.host=redis` 时解析结果是 `redis:6379`；② 设 `SPRING_DATA_REDIS_HOST=127.0.0.1` 后可覆盖；③ 无任何配置时**不得**回落到 `121.37.250.15`（默认只允许 `localhost`，或直接启动失败）。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -B -ntp -pl amz-service/amz-service-order -am test -Dtest=RedissonConfigTest`、`mvn -B -ntp -pl amz-service/amz-service-product -am test -Dtest=RedissonConfigTest`
Expected: FAIL（当前 `${spring.redis.host:121.37.250.15}` 命中第三方公网地址）

- [ ] **Step 3: 单一配置源 + 启动自检**

二选一：删除两份自定义 `RedissonConfig` 让 starter 走 `spring.data.redis.*` 自动配置；或保留 Bean 但改读 `spring.data.redis.*` 且默认值改为 `localhost`。生产 profile 增加一次带超时的 Redis 连通性自检（与 Task 2 的 `ConnectorStartupCheck` 同一入口），失败即拒绝启动。

- [ ] **Step 4: 运行测试通过**

Run: 同 Step 2（两条命令）
Expected: PASS

- [ ] **Step 5: 提交**

`git commit -m "fix(order,product): Redisson 改走 spring.data.redis.*，移除公网默认地址"`

---

### Task 10: k8s 建库 Job、Flyway 基线与两条部署路径一致性门禁

**Files:**
- Create: `k8s/infra/mysql-init-job.yaml`
- Modify: 14 个带 datasource 的 `amz-service/*/src/main/resources/application.yml`（ad / ai / customer / finance / logistics / multiplatform / ops / order / procurement / product / report / search / spapi / user）
- Modify: `k8s/services/*.yaml`（14 个 DB 模块增加等待本模块 schema 的 initContainer）
- Create: `amz-service/amz-service-spapi/src/test/java/com/amz/deploy/DeploymentSchemaBootstrapContractTest.java`
- Create: `amz-service/amz-service-spapi/src/test/java/com/amz/deploy/FlywayBaselineContractTest.java`
- Modify: `README.md`（数据库迁移说明改为“Compose/k8s 只建空库，Flyway 唯一建表”）
- 依赖：Task 1 已完成 `01-init-databases.sql` 收敛；Task 8 已补齐 report datasource 与部署环境变量。

**Interfaces:**
- Produces: k8s `amz-mysql-init` Job 幂等创建与 Compose 完全相同的 14 个空库；Job 先等待 MySQL ready，再执行建库 SQL，成功条件为 `Complete`。
- Produces: 14 个服务统一显式 `spring.flyway.baseline-on-migrate: true`、`baseline-version: 1`、`locations: classpath:db/migration`；由旧 `docker/init-sql` 建出的非空库不会再把 14 个服务全部卡死在启动阶段。
- Produces: CI 同时守护“Compose 不挂表结构”“k8s 不内嵌表结构”“应用配置不丢 Flyway 基线”“14 个模块等待各自 schema”四类回归。

- [ ] **Step 1: 写失败测试**

`DeploymentSchemaBootstrapContractTest` 断言：
① `docker/init-sql/` 仅含 `01-init-databases.sql`，且该文件无任何表 DDL/DML；仓库根不再存在可执行的 `init_all_tables.sql`；
② `docker-compose.yml` 的 mysql 初始化只挂载该单文件；
③ `k8s/infra/mysql-init-job.yaml` 中的 ConfigMap SQL 抽出的库集合与 `01-init-databases.sql` 完全一致，且不包含 `CREATE TABLE`；Job 从 `amz-erp-secret` 读取 `DB_USERNAME/DB_PASSWORD`，并在 `mysqladmin ping` 成功后才执行；
④ `k8s/services/*.yaml` 中 14 个 DB 模块分别等待 `amz_user` / `amz_product` / `amz_order` / `amz_search` / `amz_spapi` / `amz_ad` / `amz_procurement` / `amz_customer` / `amz_logistics` / `amz_ops` / `amz_finance` / `amz_multiplatform` / `amz_ai` / `amz_report`，不得缺项或串库。

`FlywayBaselineContractTest` 断言：
① 14 个 application.yml 均显式包含 `baseline-on-migrate: true` 与 `baseline-version: 1`，不依赖框架默认或命令行参数；
② `locations` 均为 `classpath:db/migration`；
③ 扫描全部 `db/migration/*.sql`，唯一表集合仍为 106 张，且不含 `docker/init-sql` 的第二建表入口；
④ 两条部署路径的建库集合严格等于 14 个库，不能多也不能少。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -B -ntp -pl amz-service/amz-service-spapi -am test -Dtest='DeploymentSchemaBootstrapContractTest,FlywayBaselineContractTest'`
Expected: FAIL（k8s Job 不存在；14 份 application.yml 无 Flyway 基线；仍有模块缺少 schema 等待；当前仓库保留旧表结构镜像）

- [ ] **Step 3: 配置 Flyway 基线**

在 14 个 `application.yml` 的 `spring` 下增加：

```yaml
  flyway:
    locations: classpath:db/migration
    baseline-on-migrate: true
    baseline-version: 1
```

空库仍从 V1 执行；非空存量库由 Flyway 写入 V1 baseline 后执行 V2+。不得再把 `--spring.flyway.baseline-on-migrate=true` 只放在 `.start-backend-final.bat` 里。

- [ ] **Step 4: 新增 k8s 建库 Job 与 schema 等待**

`mysql-init-job.yaml` 包含：只建 14 个空库的 ConfigMap、`batch/v1` Job（`backoffLimit`、`activeDeadlineSeconds`、`restartPolicy: Never`），以及等待 MySQL ready 的 shell 循环。14 个 Deployment 增加 initContainer：连接 `$MYSQL_HOST:$MYSQL_PORT`，查询 `information_schema.schemata` 确认本模块 schema 已存在后才启动主容器。Job 与 initContainer 均不得包含表结构。

- [ ] **Step 5: 更新 README 并运行测试通过**

README 删除“挂载 init-sql 目录初始化全部表”的暗示，改为“Compose/k8s 只建空库，各服务启动时由 Flyway 迁移”。随后运行与 Step 2 相同命令，Expected: PASS。

- [ ] **Step 6: 目标环境真实验证（本机无 Docker/MySQL 时不得声称通过）**

```powershell
# 仅在可丢弃的本地卷上执行；会删除当前 Compose 数据
docker compose down -v
docker compose up -d mysql
docker compose ps
# 目标环境分别验证 amz_spapi / amz_report 的 flyway_schema_history、V1/V2 版本与表集合

kubectl apply -f k8s/infra/mysql-init-job.yaml
kubectl -n amz-erp wait --for=condition=complete job/amz-mysql-init --timeout=5m
```

记录每个库的 `SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;`，并验证 14 个库均存在。若无法执行，标记“静态验证通过、运行未验证”。

- [ ] **Step 7: 提交**

`git commit -m "fix(deploy): k8s 建库 Job + 14 模块 Flyway 基线 + schema 引导契约测试"`

---

### Task 11: 零凭证取证基座（必填头 + 市场映射 + 端点覆盖 + LWA 契约 + 桩回放 + 证据门禁）

**目标修订（第 22 轮，依据 spec §1.9.2）**：原目标“SigV4 签名 KAT”建立在错误前提上——SP-API 自 2023-10-02 起不再要求 AWS SigV4，Amazon 会忽略该签名（官方 changelog 逐字确认）。因此本 Task 的**必做目标**调整为：**官方必填头 `user-agent`**（P0-35）、**marketplace→region 单一事实源且 fail-closed**（P0-36）、**LWA token 交换契约测试**、端点覆盖与桩回放、证据门禁；SigV4 KAT 与 `Clock` 注入**保留但降级为前向保险**。

**Files:**
- Create: `amz-service/amz-service-spapi/src/main/java/com/amz/connector/MarketplaceRegistry.java`（23 条：marketplaceId → region → 国家码 → host；未知 ID 抛 `UnknownMarketplaceException`）
- Create: `amz-service/amz-service-spapi/src/main/java/com/amz/auth/SpApiUserAgent.java`（官方转义规则 + ≤500 字符校验）
- Modify: `amz-service/amz-service-spapi/src/main/java/com/amz/config/SpApiConfig.java`（新增 `appName` / `appVersion` / `userAgent`，缺省由前两者拼装）
- Modify: `amz-service/amz-service-spapi/src/main/java/com/amz/client/OrdersClient.java`（`:70-81` 与 `:390`）、`FeedsClient.java`（`:359`）、`FbaInventoryClient.java`（`:298`）、`SpApiGateway.java`（`:51-62` 与 `:250-252`）：删除四份 MARKETPLACE_REGION 副本，统一委托 `MarketplaceRegistry`；四个客户端与 `FeedsClient`/`OrdersClient` 的每个出站请求统一注入 `user-agent`
- Modify: `amz-service/amz-service-spapi/src/main/java/com/amz/client/OrdersClient.java:61-65`、`FeedsClient.java:61-65`、`FbaInventoryClient.java:56-60`、`SpApiGateway.java:45-49`（端点解析改为可覆盖，生产仍是官方主机）
- Modify: `amz-service/amz-service-spapi/src/main/java/com/amz/auth/LwaTokenManager.java`（`HttpClient` 可注入、LWA 端点可覆盖、`sha256Hex` 异常分支禁止静默退化）
- Modify（可选，前向保险）: `amz-service/amz-service-spapi/src/main/java/com/amz/auth/AwsSigV4Signer.java`（注入 `Clock`，`sign` 接收 `Instant`）
- Modify: `amz-service/amz-service-spapi/src/main/resources/application.yml`（新增 `spapi.app-name`/`spapi.app-version` 与 override 键，默认空）、`application-prod.yml`
- Create: `amz-service/amz-service-spapi/src/main/java/com/amz/connector/ConnectorEvidencePolicy.java`
- Create: `amz-service/amz-service-spapi/src/test/resources/contracts/lwa-token/README.md` + 夹具 JSON
- Create: `amz-service/amz-service-spapi/src/test/java/com/amz/auth/SpApiRequiredHeaderContractTest.java`
- Create: `amz-service/amz-service-spapi/src/test/java/com/amz/connector/MarketplaceRegistryTest.java`
- Create: `amz-service/amz-service-spapi/src/test/java/com/amz/auth/LwaTokenExchangeContractTest.java`
- Create: `amz-service/amz-service-spapi/src/test/java/com/amz/auth/SpApiConditionalSigningTest.java`
- Create: `amz-service/amz-service-spapi/src/test/java/com/amz/client/SpApiEndpointOverrideSafetyTest.java`
- Create: `amz-service/amz-service-spapi/src/test/java/com/amz/client/SpApiProtocolStubTest.java`
- Create: `amz-service/amz-service-spapi/src/test/java/com/amz/connector/ConnectorEvidencePolicyTest.java`
- Create（可选）: `amz-service/amz-service-spapi/src/test/java/com/amz/auth/AwsSigV4KnownAnswerTest.java` + `src/test/resources/contracts/sigv4-kat/`
- Create: `docs/superpowers/runbooks/connector-acceptance-runbook.md`

**Interfaces:**
- Produces: `MarketplaceRegistry.resolveRegion(String marketplaceId)`（未知即抛）、`MarketplaceRegistry.resolveHost(String region)`；`SpApiUserAgent.build(config)`；`SpApiEndpointResolver.resolve(region, profile)`；`ConnectorEvidencePolicy.evaluate(evidence)` → `{evidenceLevel, apiReady, reachable}`；`AwsSigV4Signer.sign(..., Instant)`（可选，前向保险）。
- Consumes: spec §1.9.1 证据等级表、spec §1.9.2（SigV4 事实 + 23 条 marketplace 全表）、4.8 契约表、Task 6 的能力清单。

- [ ] **Step 1: 先写失败测试（7 必做 + 1 可选）**

必做：
1. `SpApiRequiredHeaderContractTest`：用 `com.sun.net.httpserver.HttpServer`（127.0.0.1:0，**不新增依赖**）挂载桩，驱动 `SpApiGateway`、`OrdersClient`、`FeedsClient`、`FbaInventoryClient`、`LwaTokenManager` 的每个出站路径，断言**每个请求**都带 `user-agent`、长度 ≤500、且含 App 名/版本/语言；同时断言 App 名含 `/`、版本含 `(` 时按官方规则转义（当前全仓无该头 → FAIL）。
2. `MarketplaceRegistryTest`：**逐条断言官方 23 条** marketplaceId 的 region 与 host；断言未知 ID（如 `"NOT_A_MARKETPLACE"`、空串、null）**抛异常**而**不是**返回 `NA`；断言 4 个客户端与 `SpApiGateway` 不再各自持有映射副本（反射或静态扫描）。
3. `LwaTokenExchangeContractTest`：固定 `clientId`/`clientSecret`/`refreshToken`，对桩断言 token 交换的路径、`Content-Type`、body 表单字段、`grant_type=refresh_token`，以及响应解析（`access_token`/`expires_in`）与**缺字段时显式失败**；夹具记录来源 URL / 字节数 / sha256。
4. `SpApiEndpointOverrideSafetyTest`：prod 非空 → 拒绝启动；非 allowlist → 拒绝；非 prod + `http://127.0.0.1:<port>` → 通过；发往非 allowlist 主机时**不含** `x-amz-access-token`。
5. `SpApiProtocolStubTest`：跑 Feeds 全链路 createFeedDocument → PUT → createFeed → getFeedStatus → 下载结果报告，断言请求序列/路径/必带头/幂等键，以及 429 退避与 `x-amzn-RateLimit-Limit` 读取。
6. `ConnectorEvidencePolicyTest`：E0–E5 × A1–A8 判定表。
7. `SpApiConditionalSigningTest`（**P0-38**）：断言“有 AWS 密钥 → 请求含 `Authorization`”；“无 AWS 密钥（只给 LWA 凭证）→ 请求**不含** `Authorization`，但仍含 `host`/`x-amz-access-token`/`x-amz-date`/`user-agent`”；并断言两条分支都**不出现** `Credential=null` 或 `Credential=/`（当前实现必然 FAIL）。

可选（前向保险）：`AwsSigV4KnownAnswerTest`（固定输入 + AWS 官方 `aws4_testsuite` 期望 `Authorization` 逐字符比对；夹具来源见 spec §1.9.2）。**注意**：既有 23 个签名相关 `@Test` 只是 E1 自证，不构成 A1 证据（spec §1.9.1 G4）。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -B -ntp -pl amz-service/amz-service-spapi -am test -Dtest=SpApiRequiredHeaderContractTest+MarketplaceRegistryTest+LwaTokenExchangeContractTest+SpApiEndpointOverrideSafetyTest+SpApiProtocolStubTest+ConnectorEvidencePolicyTest`
Expected: FAIL（类不存在 / 断言失败）

- [ ] **Step 3: 端点覆盖、必填头与市场映射（fail-closed 优先）**

覆盖键默认空；仅非生产生效，prod 非空即启动失败；allowlist 默认 `127.0.0.1`、`localhost`；非 allowlist 主机不得携带 `x-amz-access-token`，其响应也不得写入业务表（避免桩数据污染）。`MarketplaceRegistry` 成为唯一事实源，四份副本删除；`getOrDefault(..., "NA")` 必须从代码中消失（DoD 用 `grep` 断言）。7 处签名调用改为**有条件签名**：`accessKey`/`secretKey` 同时非空才注入 `Authorization`，否则跳过（P0-38），**不得**依赖 `"AWS4" + null` 的当前行为。`user-agent` 由 `SpApiUserAgent` 统一构造并注入全部出站客户端。`LwaTokenManager` 与 4 个客户端的 `HttpClient` 改为构造注入。`AwsSigV4Signer` 的 `Clock` 注入为可选项（前向保险）。

- [ ] **Step 4: LWA 契约夹具落地（必做）；SigV4 KAT 夹具（可选）**

期望值来源：(a) 官方文档示例的完整已知答案；(b) 官方 SDK 在固定输入下的输出。README 记录来源 URL / SDK 坐标与版本 / 生成脚本 / 字节数 / sha256。**若 LWA 契约夹具取不到，A1 证据上限为 E2**，完成说明必须标注；SigV4 部分取不到不阻塞本 Task（它不是必需路径）。

- [ ] **Step 5: 运行测试通过**

Run: `mvn -B -ntp -pl amz-service/amz-service-spapi -am test`
Expected: PASS（既有 527 用例不回退）

- [ ] **Step 6: 落 runbook**

按 spec §1.9.1（5）写 `docs/superpowers/runbooks/connector-acceptance-runbook.md`：一条命令、产出 JSON 与 sha256、覆盖 401/403/404/429、限流头回填、Reports 文档下载断言、A1–A8 逐项结论；**不含任何明文密钥**。runbook 需写明**沙箱限流 5 rps / burst 15** 与“沙箱仅覆盖 2xx/400，其余错误码须在生产或按官方指引构造”（spec §1.9.2(5)）。

- [ ] **Step 7: 提交**

`git commit -m "test(spapi): 零凭证取证基座（user-agent 必填头 + marketplace fail-closed + 端点覆盖 + LWA 契约 + 桩回放 + 证据门禁）"`

> **实施顺序建议（待用户确认，不擅自改序）**：Task 2（启动自检）→ Task 11（本 Task）→ Task 6（能力清单）→ Task 1/4。理由：P0-35/P0-36 为零凭证可离线完成项，直接决定“凭证到位当天能否跑通”；启动自检必须先于能力清单，否则清单无法可信。

---
## 验证与完成定义（Definition of Done）

- [ ] 单模块：`mvn -B -ntp -pl amz-service/amz-service-spapi -am test` 全绿；受影响模块（product / finance / logistics）各自全绿。
- [ ] 全量：`mvn -B -ntp clean test`（19 模块）全绿；后端用例数不少于当前 527。
- [ ] 契约：官方模型契约测试（Task 3）、部署清单双向契约测试（Task 8）、Redisson 配置契约测试（Task 9）、schema 引导/建库契约测试（Task 10）在 CI 中运行且不可跳过。
- [ ] 部署 schema：`docker/init-sql/` 只有 `01-init-databases.sql` 且无表 DDL；Compose 与 k8s 都只建 14 个空库；14 个服务显式配置 `baseline-on-migrate: true`；Flyway 唯一表集合为 106 张。
- [ ] 配置卫生：`grep -r "121.37.250.15"` 命中 0；`grep -rn "spring\.redis\.host"` 命中 0；`NACOS_SERVER_ADDR` 在部署清单中命中 0（统一 `NACOS_ADDR`）。
- [ ] 守卫：`ConnectorControllerGuardTest` 通过，附录 F 的无守卫端点数**只减不增**。
- [ ] 对应 A1–A8 的证据：每个连接器给出「缺凭证 → 错误码」「错凭证 → 平台错误码」「正确凭证 → 成功样例」三条记录后才能标记 API-Ready。
- [ ] **不得跳过**：真实 SP-API 沙箱或生产联调（A5）；本地无凭证时该项必须留白并显式标记"未验证"。
- [ ] 取证基线：端点覆盖仅非生产生效且 prod 拒绝（`SpApiEndpointOverrideSafetyTest`）；`SpApiRequiredHeaderContractTest`（每请求都带合法 `user-agent`、≤500 字符）与 `MarketplaceRegistryTest`（23 条逐条断言 + 未知 ID 抛错）通过；`LwaTokenExchangeContractTest` 通过；`SpApiConditionalSigningTest`（无 AWS 密钥时不含 `Authorization`，且永不出现 `Credential=null`）通过；`ConnectorEvidencePolicyTest` 通过；`grep -rn 'getOrDefault(marketplaceId' amz-service/amz-service-spapi/src/main` 命中 **0**。SigV4 KAT 为**可选项**（spec §1.9.2），若保留签名器则夹具必须含来源与 sha256。
- [ ] 证据透明：`GET /api/connectors` 返回 `evidenceLevel`；证据 < E4 不得显示“已接通”；`connector-acceptance-runbook.md` 落盘且可执行。

## 未验证与风险（诚实记录）

1. 本机无 MySQL/Redis：Flyway × dynamic-datasource 是否落在 master 库、启动自检的真实行为，必须在目标环境实测（Task 1 Step 6、Task 2）。
2. 无真实 SP-API 凭证：Task 3/4 只能做到"官方模型契约 + 夹具测试"级别，协议正确性仍需沙箱联调。
3. 限流官方值来自官方模型 `description` 的 Usage Plan（2026-09-24 核验）；Amazon 允许按卖家提额，因此实现必须保留 `x-amzn-RateLimit-Limit` 动态调整。
4. 本计划**不含**跨域事实模型、Outbox/Inbox、多租户隔离收敛与安全整改，那些属 Plan 2 及以后。
5. Task 9 的运行时探针只证明了“连不上第三方公网地址”（45,292 ms 超时）。目标环境 Redis 可达后，仍需验证密码、DB index、Sentinel/Cluster 拓扑三项；不能以“端口能连”替代这三点。
6. Task 8 的差集基线取自 2026-09-24 的仓库快照（PyYAML 6.0.3 解析）。若实现期新增配置键，契约测试会立即失败——这是刻意设计，修清单而不是放松断言。
7. **`JSON_LISTINGS_FEED` 的专属配额未取到官方正文**：developer-docs 页面本轮抓取失败，Task 5 先按 `createFeed` 默认值 0.0083/15 实现并在代码注释中标注差异；拿到官方 guide 数值后必须补分档。同理，`FeedsClient.java:54` 的 `Content-Type: application/json`（无 `charset`）是否被官方接受，只能由沙箱联调确认，不得凭猜测修改。
8. **P0-33 / P0-34 目前是静态推断**：必须在可丢弃环境执行 Task 10 Step 6，确认空库路径能跑通 V1/V2、旧非空库能按 V1 baseline 后跑 V2；未执行前只能称“静态验证通过、运行未验证”。**第 28 轮更新**：本机已具备 Docker + `mysql:8.0.46`，Compose 侧已部分复跑——原始 `docker/init-sql/` 在 `14-init-tables-ops.sql` 处即中止（P0-43，容器 `Exited (1)`），因此 P0-33 的“Flyway 撞非空 schema”**尚未触达**、仍属未验证；Task 10 Step 6 必须在修完 P0-39/43/45/47 后重跑，并把“真实 MySQL 8 执行 init-sql 至 `rc=0`”纳入验收。
9. **baseline 可能跳过 V1 的校验**：Flyway 对非空且无 history 的旧库以 V1 建基线时，不会重新执行/校验 V1。虽然建表脚本与迁移的**表集合**已实测覆盖，但列、索引、默认值仍可能漂移；上线前需对存量库做 schema 对照与抽样校验，不能把 `baseline-on-migrate=true` 当作 schema 正确性的证明。
10. **桩回放只能证明“我方与假设一致”**：E1/E2 证据（含现有 23 个签名测试）都不能证明平台接受我方请求。若 Task 11 Step 4 取不到官方 KAT 夹具，A1 在凭证到位前最高只能到 E2，对外只能宣称“具备对接能力（未联调）”，**不得**宣称“有 API 即可直接使用”。
11. **沙箱与错误码覆盖本轮未联网复核**：raw.githubusercontent.com 与 developer-docs 抓取失败，runbook 中 401/403/404/429 的触发方式需在凭证到位时以官方文档确认；沙箱按保守假设（需要应用注册与凭证）处理。
12. **端点覆盖是新增攻击面**：override 若在 prod 生效或 allowlist 过宽，等于把 SP-API access token 交给任意主机。CI 必须断言"prod 拒绝 + 非 allowlist 不携带 token"，这两条断言不可 skip。
13. **SigV4 已非必需（前提纠正，第 22 轮）**：官方 changelog 逐字确认 2023-10-02 起 Amazon **忽略** SigV4 签名（spec §1.9.2）。本计划因此把 SigV4 KAT 降为可选，**但不删除签名器**：保留为前向保险（Amazon 可能恢复校验、其它 AWS 系接口可复用）。若将来恢复校验，必须**同时**把 `spapi.region` 从写死的 `us-east-1` 改为按 region 派生（EU `eu-west-1` / FE `us-west-2`），否则 EU/FE 必然失败。
14. **`user-agent` 是否被判拒无法离线证明**：官方只写"必须在每个请求中包含"，未写不合规的状态码。本计划能证明的只是"头存在、格式合法、长度合规"（E3 上限）；平台是否接受属 A5（E4/E5），不得用契约测试冒充联调。
15. **marketplace 表会漂移**：23 条取自 2026-09-24 的官方 `store-identifiers.md` 快照。Amazon 新增站点时，`MarketplaceRegistryTest` 会因新 ID 未登记的 fail-closed 行为而报错——这是**刻意设计**（宁可显式失败，不可静默打到 NA 端点），修复方式是补表而不是放宽断言。
16. **P0-38 与 P0-35/P0-36 是同一条链路上的前置条件**：把 AWS 密钥降为可选（spec §1.9.2）**必须**与“有条件签名”同时实施，否则只给 LWA 凭证的用户会发出 `Credential=null` 的畸形 `Authorization` 头（第 22 轮已用真实编译产物实测）。今日 Amazon 忽略该头，所以它不表现为立即失败，而是**静默错误**——不得因为“现在能跑”就不修。
17. **RDT（P0-37）不在本计划的实现范围**：本计划只登记它与业务后果（客服/RMA/面单在无 RDT 时不可交付）。RDT 实现属 Plan 4（安全与 PII）的相邻范围，须在客服域交付前完成。

## 后续计划（不在本计划内）

- Plan 2：订单/库存事实模型与 Outbox/Inbox（P0-01…P0-10、P0-32 相关项）。
- Plan 3：租户与权限收敛（`@ShopScoped` 覆盖到 100%、82 条无守卫端点清零）。
- Plan 4：安全整改（SSE/WebSocket 身份、Agent 记忆 IDOR、刷新令牌链路、PII 分类）。
- Plan 5：可观测性与部署基线（SLO、告警、备份演练、成本看板）。
- Plan 6：确定性模拟数据生成器（第 7 章）——**第 28 轮已落地并完成 MySQL 8 端到端实测**（spec §7.9），见文末「附 A」。


---

## 附 A：第 28 轮 MySQL 8 实测补充（2026-09-24，不改变本计划实施顺序）

本计划主体（Task 1–12）针对 SP-API 连接器与部署配置。第 28 轮在实现**模拟数据工具链**（spec §7.9）时，用真实 MySQL 8.0.46 对仓库 DDL 做了端到端实测，暴露 9 个新编号 P0（净新增 7 条独立缺陷）。这些发现**不改变 Task 顺序**，但扩大 Task 10（schema 引导）与 Task 8（部署清单契约）的范围，且是 Task 2「启动自检」的前置事实。

### A.1 实测事实（可复现）

| 项 | 结果 |
|---|---|
| 生成器 | `tools/synthetic-data/` 已落地：ci 档 69 张有行表 / **21,604 行** / `rc=0` / 0 errors / 2.3 s；`verify.ps1` 六类全绿；141 文件二次生成逐字节一致 |
| 灌入 | 空库 + 完整 DDL 后 `load-all.sql` 全量成功；`amz_order.tracking_number IS NULL = 400` 与 manifest 一致 |
| 幂等加载 | `--truncate-first` 在 `mysql:8.0.46` **连灌两次 `rc=0`**、逐表行数向量零差异；且使实测行数与 manifest **69/69 精确相等**（默认空库首灌为 57 符合 + 12 不符，差额 79 行**全部**来自仓库自带种子行，已用空表实测钉死） |
| 原始 DDL | **未打补丁的 `docker/init-sql/` 在 `14-init-tables-ops.sql` 处中止**（`rank` 保留字 / `ERROR 1064`），MySQL 容器 `Exited (1)`，只建出 **37 张表 / 10 库**（15–33 号脚本从未执行） |
| 补丁后 | 修 5 类问题后建出 **105** 张；28–33 号脚本仍无 `USE`，需手工 `DBMAP` 才能建对库 |
| 28 号脚本单测 | 直接 `source` 报 `ERROR 1046 (3D000) No database selected`、`rc=1` |

### A.2 新增 P0 与归属

| 编号 | 摘要 | 归属 |
|---|---|---|
| P0-39 | MySQL 8 不支持 `ADD COLUMN IF NOT EXISTS`（7 处） | Task 10 扩大范围 |
| P0-40 | `amz_order.uk_amazon_order` 单列唯一键与 spec §7.6 冲突 | Plan 2（订单事实模型） |
| P0-41 | 5 处引用列类型与父键不一致 | Task 10 + Plan 2 |
| P0-42 | Compose 缺 `amz_agent_eval_log`（净新增）/ `amz_shop_credential`（=P0-23） | Task 10 |
| P0-43 | `rank` 保留字致 Compose 首次启动**崩库** | Task 10（最高优先） |
| P0-44 | `amz_report` 无建库（=P0-07） | Task 10 |
| P0-45 | `amz_inventory_alert` 的 `NOT NULL` 与 `INSERT NULL` 自相矛盾 | Task 10 |
| P0-46 | 合成数据 `load-all.sql` 不可重复执行 | **工具侧已修**（`--truncate-first`） |
| P0-47 | 28–33 号脚本无 `USE`，22 张表库归属未定义 | Task 10 |

### A.3 Definition of Done 增补（不替代正文 DoD）

- [ ] `docker/init-sql/` 由**真实 MySQL 8 容器**执行完、`rc=0`，且建表集合 == 迁移集合（当前原始路径在第 14 个文件即崩库）。
- [ ] 每个 init-sql 文件显式声明库归属，执行日志无 `No database selected`（P0-47）。
- [x] `tools/synthetic-data/` 的 `--truncate-first` 模式连灌两次 `rc=0`（P0-46）——**第 28 轮补测通过**：两次 `rc=0`、非警告 stderr 行 0、逐表行数向量零差异、69/69 与 manifest 精确相等（容器 `amz-mysql-syntax-test`）。
- [ ] spec §7.9 的产物与本文档证据一致，P0-39…P0-47 可在 spec §1.3 交叉检索。

> **边界不变**：本轮实测不产生任何 E4/E5 证据；A5 仍只能由凭证到位当天的 runbook 产出（spec §1.9.1(5)）。
