# SP-API 剩余能力 API-Ready 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不伪造真实联调的前提下，把 Notifications、Listings Items、Product Pricing、Catalog Items、FBA Inbound 五类共 64 条官方 SP-API operation 接入统一网关，并保证 `mock` profile 下所有接口仍可调用。

**Architecture:** 用 `SpApiOperationCatalog` 声明官方 operation 元数据（operationId、HTTP 方法、路径模板、成功码、grantless 边界、必填 path/query/body 字段）。`SpApiOperationClient` 提供真实与 mock 两个实现；五个业务客户端只做类型化参数封装，不自行构造 HTTP、LWA、SigV4 或限流。能力台账、限流表和路径契约测试均以同一份目录/官方快照双向校验。

**Tech Stack:** Java 17、Spring Boot 3.3.5、JUnit 5、Mockito、Gson、Maven 3.9.11。

**Spec:** `docs/superpowers/specs/2026-09-26-production-upgrade-execution-matrix.md`

## Global Constraints

- 不执行 `git add .`、`git commit`、`git reset`、`git clean`。
- 不触碰以下 6 个已暂存删除文件：`MessagingApiClient.java`、`MessagingApiMockClient.java`、`MessagingApiRealClient.java`、`MessageController.java`、`MessageSyncService.java`、`MessageSyncServiceImpl.java`。
- 所有 shell 命令显式使用 PowerShell。
- 真实凭证不存在时，`apiReady=false`、`reachable=false`、A5=E0 不变；mock 成功不得抬高证据等级。
- 新增 operation 的路径、方法、成功码、必填参数必须来自 5 份官方 OpenAPI 快照。
- Notifications 只有 7 条 operation 允许 grantless LWA；其余 3 条必须走卖家授权 LWA。
- FBA Inbound 的 5 条官方模型无 Usage Plan 数值，不登记猜测值，运行时使用保守兜底并等待响应头收敛。
- 新增测试先失败，再写最小实现；禁止先写生产代码后补测试。

---

### Task 1: 官方 operation 目录契约

**Files:**
- Create: `amz-service/amz-service-spapi/src/test/java/com/amz/client/SpApiOperationCatalogContractTest.java`
- Create: `amz-service/amz-service-spapi/src/main/java/com/amz/client/SpApiOperationSpec.java`
- Create: `amz-service/amz-service-spapi/src/main/java/com/amz/client/SpApiOperationCatalog.java`

**Interfaces:**
- Produces: `record SpApiOperationSpec(String operationId, String method, String path, List<Integer> successStatuses, boolean grantless, Set<String> requiredPathParameters, Set<String> requiredQueryParameters, Set<String> requiredBodyFields)`
- Produces: `SpApiOperationCatalog.operations()`, `SpApiOperationCatalog.find(String operationId)`, `SpApiOperationCatalog.operationsFor(String family)`

- [x] **Step 1: 写失败测试**

测试从 `notifications.json`、`listingsItems_2021-08-01.json`、`productPricing_2022-05-01.json`、`catalogItems_2022-04-01.json`、`fulfillmentInbound_2024-03-20.json` 解析全部 operation，断言目录恰好 64 条，并逐条核对 operationId、方法、路径、成功码、必填 path/query/body 字段。

- [x] **Step 2: 运行确认失败**

Run: `mvn -B -ntp -pl amz-service/amz-service-spapi -am test '-Dtest=SpApiOperationCatalogContractTest' '-Dsurefire.failIfNoSpecifiedTests=false'`
Expected: 编译失败，提示 `SpApiOperationSpec` / `SpApiOperationCatalog` 尚不存在。

- [x] **Step 3: 写最小实现**

实现不可变目录和 64 条元数据；Notifications 精确 allowlist 为 `getDestinations`、`getDestination`、`createDestination`、`deleteDestination`、`getSubscriptionById`、`deleteSubscriptionById`、`sendTestNotification`。

- [x] **Step 4: 运行通过**

Run: 同 Step 2。
Expected: PASS，64 / 0F / 0E。

### Task 2: 统一执行器与请求校验

**Files:**
- Create: `amz-service/amz-service-spapi/src/main/java/com/amz/client/SpApiOperationClient.java`
- Create: `amz-service/amz-service-spapi/src/main/java/com/amz/client/RealSpApiOperationClient.java`
- Create: `amz-service/amz-service-spapi/src/main/java/com/amz/client/MockSpApiOperationClient.java`
- Create: `amz-service/amz-service-spapi/src/test/java/com/amz/client/SpApiOperationClientTest.java`

**Interfaces:**
- Produces: `JsonObject execute(Long shopId, String marketplaceId, SpApiOperationSpec operation, Map<String,String> pathParameters, Map<String,String> queryParameters, JsonObject body)`
- Consumes: `SpApiOperationSpec`、`SpApiOperationCatalog`

- [x] **Step 1: 写失败测试**

覆盖：普通 operation 走 `callJsonWithStatuses`；7 条 grantless 走 `callJsonWithStatusUsingGrantlessLwa`；路径段 URI 编码；必填 path/query/body 缺失时网络请求为 0；未知 operation 拒绝；mock 返回 `synthetic=true` 且不访问网关。

- [x] **Step 2: 运行确认失败**

Run: `mvn -B -ntp -pl amz-service/amz-service-spapi -am test '-Dtest=SpApiOperationClientTest' '-Dsurefire.failIfNoSpecifiedTests=false'`
Expected: 编译失败或断言失败，证明实现尚不存在。

- [x] **Step 3: 写最小实现**

真实实现只委托 `SpApiGateway`；mock 实现只在 `@Profile("mock")` 激活，返回确定性的模拟 JSON。两者共用同一套参数校验，禁止静默补默认值。

- [x] **Step 4: 运行通过**

Run: 同 Step 2。
Expected: PASS。

### Task 3: 五个业务客户端

**Files:**
- Create: `amz-service/amz-service-spapi/src/main/java/com/amz/client/NotificationsClient.java`
- Create: `amz-service/amz-service-spapi/src/main/java/com/amz/client/ListingsItemsClient.java`
- Create: `amz-service/amz-service-spapi/src/main/java/com/amz/client/ProductPricingClient.java`
- Create: `amz-service/amz-service-spapi/src/main/java/com/amz/client/CatalogItemsClient.java`
- Create: `amz-service/amz-service-spapi/src/main/java/com/amz/client/FbaInboundClient.java`
- Create: `amz-service/amz-service-spapi/src/test/java/com/amz/client/RemainingCapabilitiesClientTest.java`

**Interfaces:**
- Consumes: `SpApiOperationClient.execute(...)`
- Produces: Notifications 10 个类型化方法；Listings Items 5 个类型化方法；Product Pricing 2 个；Catalog Items 2 个；FBA Inbound 通用 `execute` + 45 条目录查询。

- [x] **Step 1: 写失败测试**

逐家族验证客户端传入正确 operationId、path/query/body；mock profile 下五个客户端均可装配且返回模拟结果。

- [x] **Step 2: 运行确认失败**

Run: `mvn -B -ntp -pl amz-service/amz-service-spapi -am test '-Dtest=RemainingCapabilitiesClientTest' '-Dsurefire.failIfNoSpecifiedTests=false'`
Expected: 编译失败，证明客户端尚未实现。

- [x] **Step 3: 写最小实现**

客户端不得依赖 `SpApiGateway` 或自行构造 HTTP；只组装参数并调用 `SpApiOperationClient`。

- [x] **Step 4: 运行通过**

Run: 同 Step 2。
Expected: PASS。

### Task 4: 能力台账、限流与路径契约

**Files:**
- Modify: `amz-service/amz-service-spapi/src/main/java/com/amz/connector/ConnectorRegistry.java`
- Modify: `amz-service/amz-service-spapi/src/main/java/com/amz/ratelimit/SpiRateLimiter.java`
- Modify: `amz-service/amz-service-spapi/src/test/java/com/amz/connector/ConnectorRegistryTest.java`
- Modify: `amz-service/amz-service-spapi/src/test/java/com/amz/ratelimit/SpiRateLimiterTest.java`
- Modify: `amz-service/amz-service-spapi/src/test/java/com/amz/client/SpApiPathContractTest.java`

**Interfaces:**
- Consumes: `SpApiOperationCatalog.operations()`
- Produces: 90 条 `IMPLEMENTED`、0 条 `NOT_IMPLEMENTED`、106 条 official plans、15 份快照。

- [x] **Step 1: 写失败测试**

把数量和集合期望改为 90 / 0 / 106 / 15，并加入五类路径根。

- [x] **Step 2: 运行确认失败**

Run: `mvn -B -ntp -pl amz-service/amz-service-spapi -am test '-Dtest=ConnectorRegistryTest,SpiRateLimiterTest,SpApiPathContractTest' '-Dsurefire.failIfNoSpecifiedTests=false'`
Expected: 数量或集合断言失败。

- [x] **Step 3: 写最小实现**

能力表替换 5 个占位项；限流表加入 40 条有官方数值的 FBA 条目，5 条无数值项不登记；路径契约加入 5 份快照。

- [x] **Step 4: 运行通过**

Run: 同 Step 2。
Expected: PASS。

### Task 5: 统一操作控制器与文档

**Files:**
- Create: `amz-service/amz-service-spapi/src/main/java/com/amz/controller/SpApiOperationController.java`
- Create: `amz-service/amz-service-spapi/src/test/java/com/amz/controller/SpApiOperationControllerTest.java`
- Modify: `amz-service/amz-service-spapi/src/test/resources/contracts/README.md`
- Modify: `docs/superpowers/specs/2026-09-26-production-upgrade-execution-matrix.md`
- Modify: `docs/superpowers/plans/2026-09-26-connector-capability-ledger.md`

**Interfaces:**
- Produces: `GET /spapi/operations`（元数据，VIEWER/OPERATOR/ADMIN）和 `POST /spapi/operations/{operationId}`（执行，OPERATOR/ADMIN，`@ShopScoped`）。

- [x] **Step 1: 写失败测试**

断言路由、角色、店铺隔离、未知 operation 404/400、mock profile 可用、不输出 token/凭证。

- [x] **Step 2: 运行确认失败**

Run: `mvn -B -ntp -pl amz-service/amz-service-spapi -am test '-Dtest=SpApiOperationControllerTest' '-Dsurefire.failIfNoSpecifiedTests=false'`
Expected: 编译失败。

- [x] **Step 3: 写最小实现**

控制器只接受目录内 operationId；未知/格式错误返回结构化失败，不泄露内部异常。

- [x] **Step 4: 运行通过**

Run: 同 Step 2。
Expected: PASS。

### Task 6: 全量验证

- [x] **Step 1: SP-API 模块全测试**

Run: `mvn -B -ntp -pl amz-service/amz-service-spapi -am test`
Expected: BUILD SUCCESS；真实凭证测试允许 skipped。

- [x] **Step 2: 全仓测试**

Run: `mvn -B -ntp test`
Expected: 所有模块 BUILD SUCCESS。

- [x] **Step 3: 静态检查**

Run: `git diff --check`
Expected: 无空白错误；不得提交或清理工作区。

- [x] **Step 4: 证据边界复核**

确认文档仍声明：离线 API-Ready、未接通真实 SP-API、mock 不证明真实费用/税费/退款/结算/平台受理、Notifications 不包含 SQS/SNS 消费器。

## 验证结果（2026-09-26，第 81 轮）

- SP-API 模块：`428 / 0F / 0E / 2S`，`BUILD SUCCESS`；2 个 skip 为需要真实凭证的 `SpApiIntegrationTest`，不按通过计算。
- 全仓 Maven：19/19 模块 `BUILD SUCCESS`；新鲜 Surefire XML **175 份 / 1134 / 0F / 0E / 2S**。
- 前端：19 个测试文件 / 144 tests / 0 failures；`vue-tsc && vite build` 成功。
- 静态检查：`git diff --check` 退出码 0；仅出现 Git 的 CRLF/LF 提示，无空白错误。
- 能力台账：`ConnectorRegistry` 为 **90 条已实现 / 0 条未实现**；官方快照 **15 份**，Usage Plan **106 条**。
- 证据边界不变：以上为离线 E2/E3 证据，没有真实 SP-API、RDT、订单 PII、Amazon 沙箱或生产请求记录；Notifications 尚无 SQS/SNS 消费、验签、去重、DLQ 和业务映射。