# 连接器能力台账双向一致性实施计划

- 状态：**Completed（代码与文档已收敛；本轮未提交）**
- 日期：2026-09-26
- 分支：`codex/api-ready-connectors`
- 目标仓库：`C:\Users\Administrator\Desktop\AmazonERP`
- 变更门禁：不触碰 6 个已暂存删除文件；不执行 `git add .`、`git commit`、`git reset`、`git clean`。
- 实施方法：`superpowers:test-driven-development`。

## Goal

让 `ConnectorRegistry.spapiOperations()` 成为 SP-API 已实现 operation 的单一事实源：清单中的每条 `IMPLEMENTED` 项都必须在 `com/amz/client` 有真实 operationId 调用点；源码中的真实调用点也必须全部登记。本轮开始时实测为清单 13 条、源码 25 条，现已收敛为 25 条。

## Architecture

保持现有静态能力清单和 `ConnectorRegistryTest` 的扫描方式，不引入运行时依赖。新增一条测试，从 `src/main/java/com/amz/client` 提取 `API.operationId` 形式的中缀字符串，与 `ConnectorRegistry` 的已实现集合做双向集合比较。清单只增加已经存在的客户端调用点，不新增未验证的 Amazon API 调用。

## Tech Stack

Java 17、JUnit 5、Maven 3.9.11、Spring Boot 3.3.5、MyBatis-Plus（不新增依赖）。

## Spec

- `docs/superpowers/specs/2026-09-26-performance-and-business-optimization-audit.md` P0-04。
- `docs/superpowers/specs/2026-09-24-amazon-erp-production-design.md` §1.9.1 A6。
- 官方模型快照：`amz-service/amz-service-spapi/src/test/resources/contracts/messaging.json`、`uploads_2020-11-01.json`。

## Global Constraints

- 不修改 Amazon 官方契约 JSON，不伪造 operationId 或官方路径。
- 不把“代码存在”声明为“已联调”；A5 仍为 E0，`apiReady=false`、`reachable=false`。
- 不新增 token、凭证或 PII 字段；不修改限流数值。
- 所有新增 operation 的路径必须来自官方快照。
- 目标测试命令：
  `mvn -B -ntp -pl amz-service/amz-service-spapi -am test '-Dtest=ConnectorRegistryTest' '-Dsurefire.failIfNoSpecifiedTests=false'`

## Tasks

### Task 1：锁定客户端调用点与台账的双向集合

**Files:**

- Modify: `amz-service/amz-service-spapi/src/test/java/com/amz/connector/ConnectorRegistryTest.java`

**Interfaces:**

- Produces: `clientOperationIds()`：扫描 `src/main/java/com/amz/client`，返回中缀为 `orders|fbaInventory|feeds|reports|fees|finances|sellers|messaging|uploads` 的字符串集合。
- Produces: `registryMatchesClientCallSitesBidirectionally()`：断言客户端调用点集合与 `IMPLEMENTED` operationId 集合完全相等。

- [x] **Step 1: 写失败测试**

在 `ConnectorRegistryTest` 新增集合比较测试，并保留现有 `implementedOperationsHaveRealCallSites()` 的单向断言。

- [x] **Step 2: 运行确认失败**

Run: 目标测试命令。

Expected: FAIL；客户端集合 25 条，能力台账 `IMPLEMENTED` 集合 13 条，差集包含 Messaging 的 11 条和 Uploads 的 1 条。

- [x] **Step 3: 最小实现**

只增加测试扫描逻辑和集合断言；不改生产代码。

- [x] **Step 4: 运行通过**

Run: 目标测试命令。

Expected: Task 1 完成前仍可复现失败；完成 Task 2 后 PASS。

- [x] **Step 5: 提交**

本轮不提交；保留未提交变更供整体审查。

### Task 2：补齐 12 条已实现 operation

**Files:**

- Modify: `amz-service/amz-service-spapi/src/main/java/com/amz/connector/ConnectorRegistry.java`
- Modify: `amz-service/amz-service-spapi/src/test/java/com/amz/connector/ConnectorRegistryTest.java`

**Interfaces:**

- Consumes: Task 1 的双向集合测试。
- Produces: `spapiOperations()` 中 25 条 `IMPLEMENTED` 项，以及 6 条 `NOT_IMPLEMENTED` 项。

- [x] **Step 1: 运行失败测试**

Run: 目标测试命令。

Expected: FAIL，差集为 Messaging 11 条与 Uploads 1 条。

- [x] **Step 2: 写最小实现**

按官方快照加入以下 operation 和路径：

- `messaging.getMessagingActionsForOrder` → `/messaging/v1/orders/{amazonOrderId}`
- `messaging.GetAttributes` → `/messaging/v1/orders/{amazonOrderId}/attributes`
- `messaging.confirmCustomizationDetails` → `/messaging/v1/orders/{amazonOrderId}/messages/confirmCustomizationDetails`
- `messaging.createConfirmDeliveryDetails` → `/messaging/v1/orders/{amazonOrderId}/messages/confirmDeliveryDetails`
- `messaging.createLegalDisclosure` → `/messaging/v1/orders/{amazonOrderId}/messages/legalDisclosure`
- `messaging.createConfirmOrderDetails` → `/messaging/v1/orders/{amazonOrderId}/messages/confirmOrderDetails`
- `messaging.createConfirmServiceDetails` → `/messaging/v1/orders/{amazonOrderId}/messages/confirmServiceDetails`
- `messaging.CreateWarranty` → `/messaging/v1/orders/{amazonOrderId}/messages/warranty`
- `messaging.createDigitalAccessKey` → `/messaging/v1/orders/{amazonOrderId}/messages/digitalAccessKey`
- `messaging.createUnexpectedProblem` → `/messaging/v1/orders/{amazonOrderId}/messages/unexpectedProblem`
- `messaging.sendInvoice` → `/messaging/v1/orders/{amazonOrderId}/messages/invoice`
- `uploads.createUploadDestinationForResource` → `/uploads/2020-11-01/uploadDestinations/{resource}`

同时把 Javadoc 和测试中的“13 条已实现 / 6 条未实现”更新为“25 条已实现 / 6 条未实现 / 31 条总能力”。

- [x] **Step 3: 运行通过**

Run: 目标测试命令。

Expected: PASS，集合完全相等，`IMPLEMENTED` 数量为 25。

- [x] **Step 4: 回归路径契约**

Run:
`mvn -B -ntp -pl amz-service/amz-service-spapi -am test '-Dtest=SpApiPathContractTest,ConnectorRegistryTest' '-Dsurefire.failIfNoSpecifiedTests=false'`

Expected: PASS，所有新增路径均在官方快照中。

- [x] **Step 5: 提交**

本轮不提交；保留未提交变更供整体审查。

### Task 3：修正审计文档的漂移结论

**Files:**

- Modify: `docs/superpowers/specs/2026-09-26-performance-and-business-optimization-audit.md`

**Interfaces:**

- Produces: P0-04 从“潜在不一致”改为“已确认漂移”，列出 13 条台账、25 条实际调用点、12 条漏登记 operation。

- [x] **Step 1: 写文档断言**

人工核对 P0-04 中的数字、operation 名称和证据来源。

- [x] **Step 2: 修改文档**

把结论改为“已确认漂移”，补充 12 条 operation 分组和双向校验要求。

- [x] **Step 3: 验证文档**

Run: 使用 `rg` 检查 `13|25|12|潜在不一致|已确认漂移` 的上下文，确认没有相互矛盾的数字。

Expected: 文档数字与代码一致。

- [x] **Step 4: 提交**

本轮不提交；保留未提交变更供整体审查。

## 验证结果（2026-09-26）

- 组合定向 `SpApiPathContractTest,ConnectorRegistryTest,AmazonMessagingRealClientContractTest`：**26 / 0F / 0E / 0S**。
- SP-API 全模块 `mvn -B -ntp -pl amz-service/amz-service-spapi -am test`：**362 / 0F / 0E / 2S**。
- 全仓 `mvn -B -ntp test`：**19/19 模块 BUILD SUCCESS**，Surefire 新鲜 XML **161 份 / 1055 / 0F / 0E / 2S**。
- 2 个 skipped 均为需要真实凭证的 `SpApiIntegrationTest`，不按通过计算。

## 验收标准

- `ConnectorRegistryTest` 的客户端调用点集合与 `IMPLEMENTED` operationId 集合完全相等。
- `IMPLEMENTED` 为 25 条，`NOT_IMPLEMENTED` 为 6 条，总能力为 31 条。
- 所有新增路径通过 `SpApiPathContractTest`。
- 对外状态仍为“具备对接能力（未联调）”，不因能力台账补齐而抬高 A5。
- 未修改已暂存删除文件，未执行任何提交或清理命令。

## 第 79 轮追加结果（2026-09-26）

- `ConnectorRegistry` 当前为 **26 条已实现 / 5 条未实现 / 31 条总数**；新增已实现项为 `tokens.createRestrictedDataToken`，剩余未实现为 Notifications、Listings Items、Product Pricing、Catalog Items、FBA Inbound。
- 官方快照当前 **10 份**，usage plan **47 条**；Tokens 快照为 15,751 bytes、sha256 `3cd09ae7f218c83f32536a894cb8c42f2191c94b9c27f6bcf0a164442089b061`。
- RDT 离线底座已实施：显式 LWA/RDT token source、按店铺/marketplace/资源集合隔离的内存缓存、401/403 精确失效、无 LWA 回退、Outbox 重放恢复 token source 且不持久化 RDT。
- 订单同步新增 `SPAPI_ORDER_PII_SYNC_ENABLED`，默认 `false`；只有 RDT 与订单 PII 两个开关同时开启时才走受限订单调用。
- 定向部署契约 **8 / 0F / 0E / 0S**；SP-API 全模块 **390 / 0F / 0E / 2S**；全仓 19/19 模块 `BUILD SUCCESS`，Surefire **168 份 / 1096 / 0F / 0E / 2S**；RDT/订单 PII 组合定向 **42 / 0F / 0E / 0S**。
- 证据边界：以上均为离线 E2/E3 证据；没有真实 RDT、订单 PII 权限、Amazon 沙箱或生产请求记录，A5/E4/E5 不变。

## 第 81 轮追加结果（2026-09-26）

- `ConnectorRegistry` 当前为 **90 条已实现 / 0 条未实现**；其中 26 条来自既有类型化客户端，64 条来自 `SpApiOperationCatalog` + `SpApiOperationClient`。
- 官方快照当前 **15 份**，Usage Plan **106 条**；新增 Notifications 10、Listings Items 5、Product Pricing 2、Catalog Items 2、FBA Inbound 45。
- 新增统一控制器 `GET /spapi/operations` 与 `POST /spapi/operations/{operationId}`；`mock` profile 下可调用，角色与店铺隔离有契约测试。
- 5 条 FBA Inbound operation 官方模型没有 Usage Plan，未登记猜测值，运行时使用保守兜底并等待官方值/响应头收敛。
- SP-API 模块：**428 / 0F / 0E / 2S**；全仓：**19/19 模块 BUILD SUCCESS**，本轮新鲜 Surefire **175 份 / 1134 / 0F / 0E / 2S**；前端 **19 文件 / 144 tests / 0F**，production build 成功。
- 证据边界：以上均为离线 E2/E3 证据；没有真实 SP-API、RDT、订单 PII、Amazon 沙箱或生产请求记录，A5/E4/E5 不变。
- Notifications 仅完成官方管理 API；SQS/SNS 消费、验签、去重、DLQ 和业务映射仍未完成。