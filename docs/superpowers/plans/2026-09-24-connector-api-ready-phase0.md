# 连接器 API-Ready 基座（Phase 0-A）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让「提供凭证即可用」在 SP-API 主链路上真正成立——凭证表能自动创建、缺凭证显式失败、生产 profile 拒绝 mock、Reports/Feeds 闭环正确、限流按官方配额，并提供连接器能力清单与自检端点。

**Architecture:** 不改变现有模块划分与调用方向；改动集中在 `amz-service/amz-service-spapi` 模块内部（凭证、限流、报表闭环、自检），跨模块仍只用既有 Feign 接口（product 的 `SpapiFeedsClient` ↔ spapi 的 `FeedsController`；finance 的 `SpApiFinanceClient` ↔ spapi 的 `FinancialDataController`）。所有新增 HTTP 端点必须带 `@RequireRole` 或 `@ShopScoped` 守卫。

**Tech Stack:** Java 17、Spring Boot 3.3.5、MyBatis-Plus、Flyway 10.20（`flyway-core` + `flyway-mysql`）、dynamic-datasource、MySQL 8、Redis、JUnit 5 + Mockito（`spring-boot-starter-test`）。

**Spec:** `docs/superpowers/specs/2026-09-24-amazon-erp-production-design.md`（§1.9 A1–A8、§4.2、§4.6、§4.8、附录 A.3，以及 P0-23 / P0-24 / P0-27 / P0-28 / P0-29 / P0-30）

## Global Constraints

- JDK 17；Maven 用 `C:\Users\Administrator\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd`，并设 `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp`（本机沙箱必需，否则测试失败）。
- 迁移文件只许递增新增：`V2__shop_credential.sql`、`V3__feed_result_error.sql`；**禁止**修改已发布的 `V1__init.sql`。
- 凭证敏感字段一律 AES-256-GCM 密文落库（`amz-common` 的 `CryptoUtil`），列名以 `_encrypted` 结尾，禁止明文列。
- 缺凭证 / 未启用**不得**返回空列表、null、`*_MOCK_` 占位单号（A2）；三者必须与"调用失败"用不同返回值表达。
- 新增端点必须带守卫注解；附录 F 的 82 条无守卫端点只能减少，不能新增。
- 每个 Task 先写失败测试再写实现，结束时单独提交；单模块测试命令统一为
  `mvn -B -ntp -pl amz-service/amz-service-spapi -am test -Dtest=<TestClass>`。
- 本计划只是计划：**未经批准不落实现代码**。

---

## 文件结构（先定边界，再写任务）

| 动作 | 路径 | 职责 |
|---|---|---|
| Create | `amz-service/amz-service-spapi/src/main/resources/db/migration/V2__shop_credential.sql` | SP-API 凭证表，进入 Flyway 唯一入口 |
| Create | `amz-service/amz-service-spapi/src/main/resources/db/migration/V3__feed_result_error.sql` | Feed 结果报告逐行错误清单 |
| Create | `docker/init-sql/34_amz_shop_credential.sql`、`docker/init-sql/35_amz_feed_result_error.sql` | Compose 建库镜像（与 Flyway 保持一致） |
| Create | `amz-service/amz-service-spapi/src/main/java/com/amz/credential/ConnectorStartupCheck.java` | 生产启动自检（缺凭证/未启用即拒绝启动） |
| Create | `amz-service/amz-service-spapi/src/main/java/com/amz/connector/ConnectorRegistry.java` | 连接器能力清单与启用状态的事实源 |
| Create | `amz-service/amz-service-spapi/src/main/java/com/amz/controller/ConnectorController.java` | `GET /api/connectors`、`POST /api/connectors/{code}/self-test` |
| Modify | `amz-service/amz-service-spapi/src/main/java/com/amz/client/ReportsRealClient.java:80` | 字段名 `resultDocumentId` → `reportDocumentId` |
| Modify | `amz-service/amz-service-spapi/src/main/java/com/amz/client/FeedsClient.java` | 补结果报告下载与逐行错误解析 |
| Modify | `amz-service/amz-service-spapi/src/main/java/com/amz/ratelimit/SpiRateLimiter.java` | 逐 operation 官方配额、按店铺隔离、去锁内 sleep、可恢复 |
| Modify | `amz-service/amz-service-spapi/src/main/resources/application.yml`、新增 `application-prod.yml` | profile 与自检开关 |
| Modify | `.env.example`、`docker-compose.yml`、`k8s/secret.yaml`、`k8s/services/amz-service-spapi.yaml`、`k8s/configmap.yaml` | 配置三处对齐 |
| Create | `amz-service/amz-service-spapi/src/test/resources/contracts/reports_2021-06-30.json` | 官方模型快照（Apache-2.0，锁 commit） |
| Create | `amz-service/amz-service-spapi/src/test/java/...`（见各 Task） | 契约测试与行为测试 |

---

### Task 1: 凭证表进入唯一迁移入口

**Files:**
- Create: `amz-service/amz-service-spapi/src/main/resources/db/migration/V2__shop_credential.sql`
- Create: `docker/init-sql/34_amz_shop_credential.sql`
- Create: `amz-service/amz-service-spapi/src/test/java/com/amz/credential/CredentialSchemaContractTest.java`
- Read（勿改）: `amz-service/amz-service-spapi/src/main/resources/db/schema.sql:11-24`、`init_all_tables.sql:459`

**Interfaces:**
- Produces: 表 `amz_shop_credential`（列 `shop_id` PK、`client_id`、`client_secret_encrypted`、`refresh_token_encrypted`、`access_key_encrypted`、`secret_key_encrypted`、`region`、`marketplace_id`、`seller_id`、`create_time`、`update_time`），供 Task 2、Task 6 使用。

- [ ] **Step 1: 写失败测试**

新建 `CredentialSchemaContractTest`，从仓库根（`Paths.get(System.getProperty("user.dir")).getParent().getParent()`）读三处 SQL：`amz-service/amz-service-spapi/src/main/resources/db/migration/V2__shop_credential.sql`、`docker/init-sql/34_amz_shop_credential.sql`、`init_all_tables.sql`，断言：三处都含 `CREATE TABLE` + `amz_shop_credential`，且抽取出的列名集合完全一致。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -B -ntp -pl amz-service/amz-service-spapi -am test -Dtest=CredentialSchemaContractTest`
Expected: FAIL（`V2__shop_credential.sql` 与 `34_amz_shop_credential.sql` 不存在）

- [ ] **Step 3: 新增 Flyway V2 迁移**

内容取 `db/schema.sql:11-24` 的 DDL，改为 `CREATE TABLE IF NOT EXISTS`，字符集 `utf8mb4`；不写 `DROP`。

- [ ] **Step 4: 新增 Compose 镜像 `docker/init-sql/34_amz_shop_credential.sql`**

与 V2 完全同构（镜像目录编号 01–33 缺 03，下一个可用号为 34）。

- [ ] **Step 5: 运行测试通过**

Run: `mvn -B -ntp -pl amz-service/amz-service-spapi -am test -Dtest=CredentialSchemaContractTest`
Expected: PASS

- [ ] **Step 6: 记录 Flyway × dynamic-datasource 的实测结论**

在 Task 完成说明里写清：Flyway 默认 `locations=classpath:db/migration`，会与 `dynamic-datasource` 的 `DynamicRoutingDataSource` 组合；必须在目标环境用 `SHOW TABLES LIKE 'amz_shop_credential'` 验证迁移落在 `amz_spapi` 库的 master 节点（本地无 MySQL 时该步留空并标记未验证）。

- [ ] **Step 7: 提交**

`git add` 上述 3 个文件 + 测试；`git commit -m "fix(spapi): 凭证表进入 Flyway 唯一迁移入口并同步 Compose 镜像"`

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

从 `https://raw.githubusercontent.com/amzn/selling-partner-api-models/main/models/reports-api-model/reports_2021-06-30.json` 下载（Apache-2.0），在 `contracts/README.md` 记录：来源 URL、抓取日期、sha256、许可证。**锁定文件内容，不做任何改写。**

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
- Create: `docker/init-sql/35_amz_feed_result_error.sql`
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

- [ ] **Step 3: 新增 V3 迁移与 Compose 镜像**

表 `amz_feed_result_error`，主键 `id BIGINT AUTO_INCREMENT`，唯一键 `(feed_id, row_index)`，索引 `(shop_id, create_time)`。

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

四个用例：①按官方值断言 `orders.getOrders=0.0167/20`、`reports.createReport=0.0167/15`、`reports.getReportDocument=2/15`、`feeds.createFeedDocument=0.0083/15`、`fees.getMyFeesEstimate=1/2`、`fbaInventory.getInventorySummaries=2/2`；②店铺 A 触发收紧不影响店铺 B；③A 店 sleep 期间 B 店可立即获得许可（验证未持锁睡眠）；④`updateLimit` 收到更高观测值后可恢复。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -B -ntp -pl amz-service/amz-service-spapi -am test -Dtest=SpiRateLimiterTest`
Expected: FAIL（策略仍为 endpoint 维度、默认值宽松）

- [ ] **Step 3: 实现 operation 维度策略表**

`policies` 键改为 `operationId`（如 `orders.getOrders`）；`windows` 键改为 `shopId:operationId`；删除 `listings` 死策略或补齐调用方。

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
- Produces: `GET /api/connectors` → `[{code, enabled, credentialSource(env|db|vault|none), lastCallAt, lastResult, operations[], apiReady}]`；`POST /api/connectors/{code}/self-test` → 脱敏请求/响应摘要与错误码。

- [ ] **Step 1: 写失败测试**

`ConnectorControllerGuardTest`：反射扫描 `ConnectorController` 的所有 `@*Mapping` 方法，断言每个方法都带 `@RequireRole` 或 `@ShopScoped`（防止新增无守卫端点）。`ConnectorRegistryTest`：断言清单中的 operation 白名单与 1.4.1 能力缺口扫描一致（未实现的能力必须是"未实现"而不是缺字段）。

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

### Task 8: 配置与部署清单对齐（含自动化清单校验）

**Files:**
- Modify: `.env.example`、`docker-compose.yml`（spapi 段）、`k8s/secret.yaml`、`k8s/services/amz-service-spapi.yaml`、`k8s/configmap.yaml`
- Create: `amz-service/amz-service-spapi/src/test/java/com/amz/deploy/DeploymentManifestContractTest.java`

**Interfaces:**
- Produces: 静态校验测试，CI 中即可拦住"部署清单缺变量 / 密钥长度非法"两类回归（对应 P0-25、P0-29）。

- [ ] **Step 1: 写失败测试**

`DeploymentManifestContractTest` 断言：①`docker-compose.yml` 的 spapi 服务同时包含 `SPRING_PROFILES_ACTIVE` 与 `NACOS_ADDR`；②`k8s/services/amz-service-spapi.yaml` 同样包含两者；③`.env.example` 含 `AMZ_CRYPTO_KEY`、`NACOS_ADDR`、`AMZ_SPAPI_CLIENT_ID` 等必需键；④`k8s/secret.yaml` 的 `AMZ_CRYPTO_KEY` base64 解码后**恰好 32 字节**。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -B -ntp -pl amz-service/amz-service-spapi -am test -Dtest=DeploymentManifestContractTest`
Expected: FAIL（当前 compose 缺 profile、Secret 为 34 字节）

- [ ] **Step 3: 修正清单与密钥**

Secret 一律改为外部注入（Sealed Secret / External Secrets / KMS），仓库内只保留占位并保证长度合法；compose 与 k8s 统一变量名 `NACOS_ADDR`（删除 `NACOS_SERVER_ADDR` 的不一致用法）。

- [ ] **Step 4: 运行测试通过**

Run: `mvn -B -ntp -pl amz-service/amz-service-spapi -am test -Dtest=DeploymentManifestContractTest`
Expected: PASS

- [ ] **Step 5: 提交**

`git commit -m "chore(deploy): 配置三处对齐并加部署清单契约测试"`

---

## 验证与完成定义（Definition of Done）

- [ ] 单模块：`mvn -B -ntp -pl amz-service/amz-service-spapi -am test` 全绿；受影响模块（product / finance / logistics）各自全绿。
- [ ] 全量：`mvn -B -ntp clean test`（19 模块）全绿；后端用例数不少于当前 527。
- [ ] 契约：官方模型契约测试（Task 3）与部署清单契约测试（Task 8）在 CI 中运行且不可跳过。
- [ ] 守卫：`ConnectorControllerGuardTest` 通过，附录 F 的无守卫端点数**只减不增**。
- [ ] 对应 A1–A8 的证据：每个连接器给出「缺凭证 → 错误码」「错凭证 → 平台错误码」「正确凭证 → 成功样例」三条记录后才能标记 API-Ready。
- [ ] **不得跳过**：真实 SP-API 沙箱或生产联调（A5）；本地无凭证时该项必须留白并显式标记"未验证"。

## 未验证与风险（诚实记录）

1. 本机无 MySQL/Redis：Flyway × dynamic-datasource 是否落在 master 库、启动自检的真实行为，必须在目标环境实测（Task 1 Step 6、Task 2）。
2. 无真实 SP-API 凭证：Task 3/4 只能做到"官方模型契约 + 夹具测试"级别，协议正确性仍需沙箱联调。
3. 限流官方值来自官方模型 `description` 的 Usage Plan（2026-09-24 核验）；Amazon 允许按卖家提额，因此实现必须保留 `x-amzn-RateLimit-Limit` 动态调整。
4. 本计划**不含**跨域事实模型、Outbox/Inbox、多租户隔离收敛与安全整改，那些属 Plan 2 及以后。

## 后续计划（不在本计划内）

- Plan 2：订单/库存事实模型与 Outbox/Inbox（P0-01…P0-10 相关项）。
- Plan 3：租户与权限收敛（`@ShopScoped` 覆盖到 100%、82 条无守卫端点清零）。
- Plan 4：安全整改（SSE/WebSocket 身份、Agent 记忆 IDOR、刷新令牌链路、PII 分类）。
- Plan 5：可观测性与部署基线（SLO、告警、备份演练、成本看板）。
- Plan 6：确定性模拟数据生成器（第 7 章，固定 seed、显式 SYNTHETIC、错误堆栈注入）。