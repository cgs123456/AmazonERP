# 连接器 API-Ready 基座（Phase 0-A）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让「提供凭证即可用」在 SP-API 主链路上真正成立——凭证表能自动创建、缺凭证显式失败、生产 profile 拒绝 mock、Reports/Feeds 闭环正确、限流按官方配额，并提供连接器能力清单与自检端点。第 13 轮追加边界：**“有凭证”必须同时包含“凭证能到达进程”**——16 份部署清单与代码占位符双向对齐（Task 8）、Redis 配置去掉硬编码公网地址（Task 9）。

**Architecture:** 不改变现有模块划分与调用方向；改动集中在 `amz-service/amz-service-spapi` 模块内部（凭证、限流、报表闭环、自检），跨模块仍只用既有 Feign 接口（product 的 `SpapiFeedsClient` ↔ spapi 的 `FeedsController`；finance 的 `SpApiFinanceClient` ↔ spapi 的 `FinancialDataController`）。所有新增 HTTP 端点必须带 `@RequireRole` 或 `@ShopScoped` 守卫。

**Tech Stack:** Java 17、Spring Boot 3.3.5、MyBatis-Plus、Flyway 10.20（`flyway-core` + `flyway-mysql`）、dynamic-datasource、MySQL 8、Redis、JUnit 5 + Mockito（`spring-boot-starter-test`）。

**Spec:** `docs/superpowers/specs/2026-09-24-amazon-erp-production-design.md`（§1.9 A1–A8、§4.2、§4.6、§4.8 含 4.8.1 配置覆盖率实测、附录 A.3，以及 P0-01 / P0-23 / P0-24 / P0-25 / P0-27 / P0-28 / P0-29 / P0-30 / P0-31 / P0-32）

## Global Constraints

- JDK 17；Maven 用 `C:\Users\Administrator\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd`，并设 `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp`（本机沙箱必需，否则测试失败）。
- 迁移文件只许递增新增：`V2__shop_credential.sql`、`V3__feed_result_error.sql`；**禁止**修改已发布的 `V1__init.sql`。
- 凭证敏感字段一律 AES-256-GCM 密文落库（`amz-common` 的 `CryptoUtil`），列名以 `_encrypted` 结尾，禁止明文列。
- 缺凭证 / 未启用**不得**返回空列表、null、`*_MOCK_` 占位单号（A2）；三者必须与"调用失败"用不同返回值表达。
- 新增端点必须带守卫注解；附录 F 的 82 条无守卫端点只能减少，不能新增。
- 每个 Task 先写失败测试再写实现，结束时单独提交；单模块测试命令统一为
  `mvn -B -ntp -pl amz-service/amz-service-spapi -am test -Dtest=<TestClass>`。
- 配置键名统一：Redis 一律走 `spring.data.redis.*`（环境变量 `SPRING_DATA_REDIS_HOST/PORT/PASSWORD`）；Nacos 一律 `NACOS_ADDR`，禁止 `NACOS_SERVER_ADDR` 双写；禁止任何默认值指向公网地址或第三方 IP。
- 部署清单以代码占位符为唯一事实源做**双向**差集校验（既不许缺、也不许多）；清单契约测试不可 skip。
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
| Modify | `.env.example`、`docker-compose.yml`（16 个业务服务段）、`k8s/secret.yaml`、`k8s/configmap.yaml`、`k8s/services/*.yaml`（16 份逐份对齐） | 配置三处双向对齐（Task 8） |
| Modify | `amz-service/amz-service-order/src/main/java/com/amz/config/RedissonConfig.java:16-22`、`amz-service/amz-service-product/src/main/java/com/amz/config/RedissonConfig.java:16-22` | 移除公网 Redis 默认值，改走 `spring.data.redis.*`（Task 9） |
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
⑥ **反向断言**：`amz-service-report` 这类无 datasource 的模块不得出现 DB/Rabbit/Redis 注入项（当前 19 项属过度注入）。

`PlaceholderCoverageContractTest` 用正则 `\$\{([A-Z][A-Z0-9_]*)(?::([^}]*))?\}` 提取占位符，**必须显式剔除 `application-local.yml`**——第 13 轮已实测 `MQ_USERNAME`/`MQ_PASSWORD` 只出现在该文件，未剔除会产生假阳性。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -B -ntp -pl amz-service/amz-service-spapi -am test -Dtest='DeploymentManifestContractTest,PlaceholderCoverageContractTest'`
Expected: FAIL（当前 15/16 份 Deployment 缺 `NACOS_ADDR`；logistics 缺 15 项、search 缺 10 项、product 缺 7 项、user/procurement 各缺 5 项；Compose `REDIS_HOST` 0 命中、`MYSQL_HOST` 仅 spapi；report 反向多注入）

- [ ] **Step 3: 按实测差集修正三处**

以代码占位符为唯一事实源，逐模块补齐 `NACOS_ADDR`、`SPRING_PROFILES_ACTIVE`、`AD_PROFILE_ID`、`MONGO_HOST`、`OSS_ACCESS_KEY_ID/SECRET/BUCKET_NAME`、`ES_URIS`、`EMBEDDING_API_KEY/API_URL/ENABLED/MODEL`、`AMZ_17TRACK_BASE_URL/KEY`、`AMZ_LOGISTICS_*`、`AMZ_TRACKING_ENABLED`、`ALIBABA_APP_KEY/APP_SECRET/REFRESH_TOKEN`、`KINGDEE_APP_ID/APP_SECRET`、`AGENT_AI_CHAT_URL`、`SENTINEL_DASHBOARD` 等缺失键；16 份统一为 `NACOS_ADDR`（删除 `NACOS_SERVER_ADDR` 不一致用法）；Compose 补齐 `MYSQL_HOST`/`REDIS_HOST`/`RABBITMQ_HOST` 到所有依赖模块；Secret 改为外部注入（Sealed Secret / External Secrets / KMS），仓库内只保留长度合法的占位。

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

## 验证与完成定义（Definition of Done）

- [ ] 单模块：`mvn -B -ntp -pl amz-service/amz-service-spapi -am test` 全绿；受影响模块（product / finance / logistics）各自全绿。
- [ ] 全量：`mvn -B -ntp clean test`（19 模块）全绿；后端用例数不少于当前 527。
- [ ] 契约：官方模型契约测试（Task 3）、部署清单双向契约测试（Task 8）、Redisson 配置契约测试（Task 9）在 CI 中运行且不可跳过。
- [ ] 配置卫生：`grep -r "121.37.250.15"` 命中 0；`grep -rn "spring\.redis\.host"` 命中 0；`NACOS_SERVER_ADDR` 在部署清单中命中 0（统一 `NACOS_ADDR`）。
- [ ] 守卫：`ConnectorControllerGuardTest` 通过，附录 F 的无守卫端点数**只减不增**。
- [ ] 对应 A1–A8 的证据：每个连接器给出「缺凭证 → 错误码」「错凭证 → 平台错误码」「正确凭证 → 成功样例」三条记录后才能标记 API-Ready。
- [ ] **不得跳过**：真实 SP-API 沙箱或生产联调（A5）；本地无凭证时该项必须留白并显式标记"未验证"。

## 未验证与风险（诚实记录）

1. 本机无 MySQL/Redis：Flyway × dynamic-datasource 是否落在 master 库、启动自检的真实行为，必须在目标环境实测（Task 1 Step 6、Task 2）。
2. 无真实 SP-API 凭证：Task 3/4 只能做到"官方模型契约 + 夹具测试"级别，协议正确性仍需沙箱联调。
3. 限流官方值来自官方模型 `description` 的 Usage Plan（2026-09-24 核验）；Amazon 允许按卖家提额，因此实现必须保留 `x-amzn-RateLimit-Limit` 动态调整。
4. 本计划**不含**跨域事实模型、Outbox/Inbox、多租户隔离收敛与安全整改，那些属 Plan 2 及以后。
5. Task 9 的运行时探针只证明了“连不上第三方公网地址”（45,292 ms 超时）。目标环境 Redis 可达后，仍需验证密码、DB index、Sentinel/Cluster 拓扑三项；不能以“端口能连”替代这三点。
6. Task 8 的差集基线取自 2026-09-24 的仓库快照（PyYAML 6.0.3 解析）。若实现期新增配置键，契约测试会立即失败——这是刻意设计，修清单而不是放松断言。

## 后续计划（不在本计划内）

- Plan 2：订单/库存事实模型与 Outbox/Inbox（P0-01…P0-10、P0-32 相关项）。
- Plan 3：租户与权限收敛（`@ShopScoped` 覆盖到 100%、82 条无守卫端点清零）。
- Plan 4：安全整改（SSE/WebSocket 身份、Agent 记忆 IDOR、刷新令牌链路、PII 分类）。
- Plan 5：可观测性与部署基线（SLO、告警、备份演练、成本看板）。
- Plan 6：确定性模拟数据生成器（第 7 章，固定 seed、显式 SYNTHETIC、错误堆栈注入）。