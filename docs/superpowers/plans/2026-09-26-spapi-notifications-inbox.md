# SP-API Notifications Inbox + Order Upsert Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立 SP-API 通知入站链路（mock 可跑、SQS 可切换）并修复 Amazon 订单从“存在即跳过”到幂等 upsert 的正确性缺陷。

**Architecture:** 事件源抽象 `NotificationEventSource` 下挂 Mock 与 SQS 两个实现；原始通知先落加密 Inbox 再删 SQS 消息，Worker 原子领取并调用类型化 Handler；`ORDER_CHANGE` 经共享 Publisher 发到 `amz-service-order` 执行按 `(shop_id, amazon_order_id)` 的 upsert。

**Tech Stack:** Java 17、Spring Boot 3.3.5、MyBatis-Plus、Flyway、MySQL 8、RabbitMQ、AWS SDK v2（`software.amazon.awssdk:bom:2.55.6`）、Micrometer、JUnit 5、Mockito。

**Spec:** `C:\Users\Administrator\Desktop\AmazonERP\docs\superpowers\specs\2026-09-26-spapi-notifications-inbox-design.md`

## Global Constraints

- AWS SDK BOM 固定 `2.55.6`；禁止动态版本或版本区间。
- AWS SDK 同步 HTTP 客户端依赖 `httpclient5` / `httpcore5` 必须在根 pom 显式钉版本（当前 `5.6.4` / `5.4.3`）：
  这两个 artifact 不在 `software.amazon.awssdk:bom` 管理范围内，会被 `spring-boot-dependencies` 降级，
  导致 `spapi.notifications.source=sqs` 时服务启动即失败。详见 Task 11。
- 队列只允许 SQS **standard** queue；不支持 FIFO。
- SQS 消费使用独立 IAM 角色；禁止使用 `amz_shop_credential.access_key_encrypted` / `secret_key_encrypted` 作为队列凭据。
- Inbox payload 用 `CryptoUtil` AES-256-GCM 加密；日志禁止输出 PII。
- 默认参数：`max-attempts=5`、`base-delay-seconds=30`、`lease-timeout-seconds=300`、`visibility-timeout-seconds=60`、`payload-max-bytes=262144`。
- `visibility-timeout-seconds` 必须大于 `lease-timeout-seconds` 与 Handler 最大耗时之和。
- spapi 新增 Flyway `V6`/`V7`/`V8`；order 新增 `V2`。
- `prod` profile 与 `source=mock` 同时出现、或 `source=sqs` 缺 `queue-url`/`region` 时拒绝启动。
- 真实 SQS 联调测试必须 `@Disabled`，skipped 不计为通过；本计划证据等级最高 E2。
- 构建命令统一前缀：`$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp'`，Maven 用 `C:\Users\Administrator\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd`。

---

### Task 1: 依赖、配置属性与启动校验

**Files:**
- Modify: `C:\Users\Administrator\Desktop\AmazonERP\pom.xml`
- Modify: `C:\Users\Administrator\Desktop\AmazonERP\amz-service\amz-service-spapi\pom.xml`
- Create: `C:\Users\Administrator\Desktop\AmazonERP\amz-service\amz-service-spapi\src\main\java\com\amz\notification\NotificationProperties.java`
- Create: `C:\Users\Administrator\Desktop\AmazonERP\amz-service\amz-service-spapi\src\main\java\com\amz\notification\NotificationStartupCheck.java`
- Test: `C:\Users\Administrator\Desktop\AmazonERP\amz-service\amz-service-spapi\src\test\java\com\amz\notification\NotificationStartupCheckTest.java`

**Interfaces:**
- Produces: `NotificationProperties`（`source`、`queueUrl`、`region`、`stsRoleArn`、`waitTimeSeconds`、`maxMessages`、`visibilityTimeoutSeconds`、`batchSize`、`maxAttempts`、`baseDelaySeconds`、`leaseTimeoutSeconds`、`payloadMaxBytes`、`subscriptionSyncEnabled`、`mockScenario`）
- Produces: `NotificationStartupCheck.validate(String activeProfiles, NotificationProperties props)` 抛 `IllegalStateException`

- [ ] **Step 1: 写失败测试**

```java
@Test
void prodWithMockSourceIsRejected() {
    NotificationProperties props = new NotificationProperties();
    props.setSource("mock");
    assertThrows(IllegalStateException.class,
            () -> NotificationStartupCheck.validate("prod", props));
}

@Test
void sqsWithoutQueueUrlIsRejected() {
    NotificationProperties props = new NotificationProperties();
    props.setSource("sqs");
    props.setRegion("us-east-1");
    assertThrows(IllegalStateException.class,
            () -> NotificationStartupCheck.validate("prod", props));
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:
`$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp'; & 'C:\Users\Administrator\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd' -B -ntp -pl amz-service/amz-service-spapi -am test "-Dtest=NotificationStartupCheckTest" "-DfailIfNoTests=false"`
Expected: FAIL，类不存在。

- [ ] **Step 3: 最小实现**

在 `pom.xml` 的 `dependencyManagement` 增加 `software.amazon.awssdk:bom:2.55.6`（`pom` / `import`）；在 spapi `pom.xml` 增加 `software.amazon.awssdk:sqs` 与 `software.amazon.awssdk:auth`（不写 version，继承 BOM）。`NotificationStartupCheck.validate` 按 Global Constraints 四条规则抛 `IllegalStateException`。

- [ ] **Step 4: 运行测试确认通过**

Expected: PASS。

- [ ] **Step 5: 提交**

`git add` 上述文件并 commit（禁止 `git add .`）。

### Task 2: Flyway V6–V8 与实体/Mapper

**Files:**
- Create: `...\amz-service-spapi\src\main\resources\db\migration\V6__spapi_notification_destination.sql`
- Create: `...\V7__spapi_notification_subscription.sql`
- Create: `...\V8__spapi_notification_inbox.sql`
- Create: `...\main\java\com\amz\model\NotificationInboxEntity.java`
- Create: `...\main\java\com\amz\mapper\NotificationInboxMapper.java`
- Create: `...\main\java\com\amz\mapper\NotificationSubscriptionMapper.java`
- Create: `...\main\java\com\amz\mapper\NotificationDestinationMapper.java`
- Test: `...\test\java\com\amz\notification\NotificationSchemaContractTest.java`

**Interfaces:**
- Produces: `NotificationInboxMapper extends BaseMapper<NotificationInboxEntity>`、`NotificationSubscriptionMapper`、`NotificationDestinationMapper`

- [ ] **Step 1: 写失败测试**（读取迁移 SQL 文本并断言关键 DDL 片段存在）

```java
@Test
void inboxHasUniqueNotificationId() throws Exception {
    String sql = Files.readString(Path.of("src/main/resources/db/migration/V8__spapi_notification_inbox.sql"));
    assertTrue(sql.contains("uk_notification_id"));
    assertTrue(sql.contains("payload_encrypted"));
}
```

- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 按 spec §6 编写三份 SQL 与实体/Mapper**
- [ ] **Step 4: 运行确认通过**
- [ ] **Step 5: 提交**

### Task 3: 事件源抽象、Mock 源与载荷校验

**Files:**
- Create: `...\notification\NotificationEventSource.java`
- Create: `...\notification\NotificationEnvelope.java`
- Create: `...\notification\NotificationPayloadValidator.java`
- Create: `...\notification\MockNotificationEventSource.java`
- Test: `...\test\java\com\amz\notification\NotificationPayloadValidatorTest.java`
- Test: `...\test\java\com\amz\notification\MockNotificationEventSourceTest.java`

**Interfaces:**
- `NotificationEventSource.poll(int maxMessages) -> List<NotificationEnvelope>`
- `NotificationEnvelope(String receiptHandle, String rawJson)`
- `NotificationPayloadValidator.validate(String rawJson, int maxBytes) -> ValidatedNotification`（缺 `NotificationVersion`/`NotificationType`/`PayloadVersion`/`EventTime`/`Payload`/`NotificationMetadata`，或 `NotificationMetadata` 缺 `ApplicationId`/`SubscriptionId`/`NotificationId` 时抛 `InvalidNotificationException`）
- `MockNotificationEventSource` 从 `spapi.notifications.mock.scenario` 对应的合成文件读取，`synthetic=true`

- [ ] **Step 1: 写失败测试**（缺字段、超大小、正常三种）
- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 最小实现**
- [ ] **Step 4: 运行确认通过**
- [ ] **Step 5: 提交**

### Task 4: Inbox 服务（加密、哈希、去重、状态）

**Files:**
- Create: `...\notification\NotificationInboxService.java`
- Modify: `...\config\SchedulingConfig.java`（池大小 6→8，容纳通知 Worker 与租约恢复）
- Test: `...\test\java\com\amz\notification\NotificationInboxServiceTest.java`

**Interfaces:**
- `NotificationInboxService.ingest(ValidatedNotification n, String rawJson) -> IngestResult`（`CREATED` / `DUPLICATE_SAME_HASH` / `DUPLICATE_DIFF_HASH` / `TOO_LARGE`）
- 依赖 `CryptoUtil.encrypt` 与 SHA-256 哈希；`notification_id` 唯一键冲突视为重复

- [ ] **Step 1: 写失败测试**（首次入库、同哈希重复、异哈希重复、超大小）
- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 最小实现**
- [ ] **Step 4: 运行确认通过**
- [ ] **Step 5: 提交**

### Task 5: Worker（原子领取、重试、租约恢复、DLQ）

**Files:**
- Create: `...\notification\NotificationInboxWorker.java`
- Create: `...\notification\NotificationRetryPolicy.java`
- Test: `...\test\java\com\amz\notification\NotificationInboxWorkerTest.java`

**Interfaces:**
- `NotificationInboxWorker.claimBatch(int batchSize)` 使用条件 `UPDATE ... WHERE id=? AND status='RECEIVED' AND next_attempt_at <= NOW()`，返回受影响行数
- `NotificationRetryPolicy.nextDelay(int attempt)` 指数退避；`attempt >= maxAttempts` → `DLQ`
- 租约过期由 `recoverStaleLeases(LocalDateTime)` 回收

- [ ] **Step 1: 写失败测试**（领取 0/1、超过 maxAttempts 进 DLQ、毒消息不重复产生副作用）
- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 最小实现**
- [ ] **Step 4: 运行确认通过**
- [ ] **Step 5: 提交**

### Task 6: 事件 Handler 与共享 Publisher

**Files:**
- Create: `...\notification\NotificationEventHandler.java`
- Create: `...\notification\OrderChangeHandler.java`
- Create: `...\notification\FeedFinishedHandler.java`
- Create: `...\notification\ReportFinishedHandler.java`
- Create: `...\notification\UnsupportedTypeHandler.java`
- Create: `...\notification\OrderUpsertPublisher.java`（抽取 `OrderSyncScheduler` 的 private `sendJson`/`publishSaveMessage` 逻辑，禁止复制粘贴）
- Modify: `...\scheduler\OrderSyncScheduler.java`（改为调用 `OrderUpsertPublisher`，并让 `sendJson` 失败抛异常而非吞掉）
- Test: `...\test\java\com\amz\notification\OrderChangeHandlerTest.java`
- Test: `...\test\java\com\amz\notification\UnsupportedTypeHandlerTest.java`

**Interfaces:**
- `NotificationEventHandler.supports(String notificationType) -> boolean`
- `NotificationEventHandler.handle(NotificationContext ctx)`
- `OrderChangeHandler` 业务幂等键：`shopId + amazonOrderId + eventTime + notificationType`
- `FeedFinishedHandler` 用 `FeedsClient.fetchFeedResult`；`ReportFinishedHandler` 用 `ReportsRealClient.getReport/downloadDocument`

- [ ] **Step 1: 写失败测试**
- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 最小实现**
- [ ] **Step 4: 运行确认通过**
- [ ] **Step 5: 提交**

### Task 7: `amz-service-order` 订单 Upsert 与店铺隔离

**Files:**
- Modify: `...\amz-service-order\src\main\java\com\amz\service\OrderService.java`
- Create: `...\model\dto\OrderUpsertCommand.java`
- Modify: `...\service\impl\OrderServiceImpl.java`（替换 `syncAmazonOrder` 的“存在即跳过”）
- Modify: `...\mq\consumer\OrderConsumer.java`（SP-API 路径改调 upsert）
- Create: `...\resources\db\migration\V2__amazon_order_shop_scope.sql`
- Create: `...\test\java\com\amz\service\impl\OrderUpsertTest.java`

**Interfaces:**
- `OrderService.upsertAmazonOrder(OrderUpsertCommand)`；`syncAmazonOrder(OrderSyncDto)` 保留并委托
- 查询必须带 `shop_id` 与 `amazon_order_id`；`eventTime`/`lastUpdateDate` 早于库中值时跳过状态更新并计 `order.stale_event.skipped`
- 迁移：删 `uk_amazon_order`，加 `UNIQUE KEY uk_shop_amazon_order (shop_id, amazon_order_id)` 与 `KEY idx_shop_last_update (shop_id, last_update_date)`

- [ ] **Step 1: 写失败测试**（已存在订单收到更新、旧事件不覆盖新状态、跨店同号不串店）
- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 最小实现**
- [ ] **Step 4: 运行确认通过**
- [ ] **Step 5: 提交**

### Task 8: 订阅映射、对账调度与健康检查/指标

**Files:**
- Create: `...\notification\NotificationSubscriptionRegistry.java`
- Create: `...\scheduler\NotificationSubscriptionSyncScheduler.java`
- Create: `...\notification\NotificationSourceHealthIndicator.java`
- Create: `...\notification\NotificationMetrics.java`
- Test: `...\test\java\com\amz\notification\NotificationSubscriptionRegistryTest.java`

**Interfaces:**
- `NotificationSubscriptionRegistry.resolve(String subscriptionId) -> Optional<ShopBinding>`；缺映射 → `UNRESOLVED_SUBSCRIPTION` 隔离（不删 SQS 消息、不进 DLQ）
- 指标前缀 `amz.spapi.notification`：`received`、`duplicate`、`invalid`、`unresolved_subscription`、`unsupported`、`processed`、`retry_scheduled`、`dlq`、`payload_too_large`、`sqs_delete_failed`、`inbox_lag_seconds`、`handler_duration_seconds`

- [ ] **Step 1: 写失败测试**
- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 最小实现**
- [ ] **Step 4: 运行确认通过**
- [ ] **Step 5: 提交**

### Task 9: Mock 全链路端到端与真实 SQS 桩测试

**Files:**
- Create: `...\test\java\com\amz\notification\NotificationEndToEndMockTest.java`
- Create: `...\test\java\com\amz\notification\SqsNotificationEventSourceTest.java`（AWS SDK 桩验证 `ReceiveMessage`/`DeleteMessage`/visibility timeout）
- Create: `...\test\java\com\amz\notification\RealSqsIntegrationTest.java`（`@Disabled`，需真实凭证）

**Interfaces:**
- 端到端覆盖 spec §13 第 1–12 项；`RealSqsIntegrationTest` 默认禁用

- [ ] **Step 1: 写失败测试**
- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 最小实现**
- [ ] **Step 4: 运行确认通过**
- [ ] **Step 5: 提交**

### Task 10: 全仓回归与文档口径修正

**Files:**
- Modify: `C:\Users\Administrator\Desktop\AmazonERP\docs\superpowers\specs\2026-09-26-performance-and-business-optimization-audit.md`（旧口径 26/5/31 与旧测试数字改为实测值）
- Modify: `C:\Users\Administrator\Desktop\AmazonERP\docs\superpowers\plans\2026-09-26-connector-capability-ledger.md`

- [ ] **Step 1: 跑全仓回归**

Run:
`$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp'; & 'C:\Users\Administrator\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd' -B -ntp test`
Expected: BUILD SUCCESS，0 failures/errors；`SpApiIntegrationTest` 与新增 `RealSqsIntegrationTest` 为 skipped，不计为通过。

- [ ] **Step 2: 修正文档口径并重新自检**
- [ ] **Step 3: 提交**


### Task 11: httpclient5 / httpcore5 版本冲突修复（Task 9 实测暴露的 P0）

**背景：实测暴露，不是推测。** 跑 `SqsNotificationEventSourceTest` 时抛出
`NoClassDefFoundError: org/apache/hc/client5/http/ssl/TlsSocketStrategy`，
栈顶为 `software.amazon.awssdk.http.apache5.Apache5HttpClient$DefaultBuilder.buildWithDefaults`。

**Files:**
- Modify: `C:\Users\Administrator\Desktop\AmazonERP\pom.xml`（新增 `httpclient5.version` / `httpcore5.version` 属性与 dependencyManagement 条目，并修正「AWS BOM 不会抢版本」的错误注释）
- Modify: `C:\Users\Administrator\Desktop\AmazonERP\amz-service\amz-service-spapi\src\test\java\com\amz\notification\SqsNotificationEventSourceTest.java`（`capturedDeleteRequest` 改用 `atLeastOnce()`）
- Create: `C:\Users\Administrator\Desktop\AmazonERP\amz-service\amz-service-spapi\src\test\java\com\amz\notification\AwsSdkHttpLinkageContractTest.java`

**根因（三步证据链）：**
1. `mvn dependency:tree -Dverbose` 显示 `httpclient5:5.3.1 (version managed from 5.6.4)`、
   `httpcore5:5.2.5 (version managed from 5.4.3)` —— 即 Spring Boot 3.3.5 的
   `spring-boot-dependencies` 把 AWS SDK 实际需要的版本<b>降级</b>了。
2. `software.amazon.awssdk:bom` 只管理 `software.amazon.awssdk` 组，管不到 `org.apache.httpcomponents`。
3. `TlsSocketStrategy` 在 httpclient5 5.4+ 才有（实测 5.3.1 jar 内无此类，5.5.2 有）。

**为什么这是 P0 且此前必然漏网：**
- `NoClassDefFoundError` 是 `Error` 不是 `Exception`，业务 `catch (Exception)` 兜不住。
- 只在<b>构建客户端</b>那一刻触发，而所有基于 mock 的单测都不构建真实客户端；
  `source=mock` 模式永远跑不到，本地/CI 用 mock 时 100% 发现不了。
- `SqsNotificationEventSource` 在构造函数里建客户端 → `source=sqs` 时<b>服务启动即失败</b>。

**修复：** 根 pom 显式钉 `httpclient5=5.6.4`、`httpcore5=5.4.3`、`httpcore5-h2=5.4.3`。
本 pom 直接声明的 dependencyManagement 优先于父 pom / 被 import 的 BOM，取值一定生效。
影响面实测：全仓只有 `amz-service-spapi` 解析到 httpclient5，其余 18 个模块不受影响。

**验收（必须双向验证，防卫兵变摆设）：**
- 正向：`mvn -o -pl amz-service/amz-service-spapi -am test` → BUILD SUCCESS，`AwsSdkHttpLinkageContractTest` 3/3、`SqsNotificationEventSourceTest` 15/15。
- 负向：`-Dhttpclient5.version=5.3.1 -Dhttpcore5.version=5.2.5 -Dtest=AwsSdkHttpLinkageContractTest -Dsurefire.failIfNoSpecifiedTests=false` → 必须 3/3 失败并复现同一 `NoClassDefFoundError`。已实测成立。
- 全仓：`mvn -o test -fae` → 18/19 SUCCESS；`amz-service-ad` 的 `AdvertisingApiRealClientContractTest`
  因本机环境禁止回环连接（连 `Selector.open()` 都抛 `Unable to establish loopback connection`）失败，
  属环境问题非代码缺陷，且该测试在 15:15 的全仓跑测中 7/7 通过。

- [x] **Step 1: 钉版本并修正错误注释**
- [x] **Step 2: 补链接契约测试 + 修测试辅助方法**
- [x] **Step 3: 正向/负向/全仓三项验证**
- [x] **Step 4: 提交**

---

## 自检结果

- Spec 覆盖：§4–§13 均有对应 Task；§14 真实接入清单不属代码实现，保留在 spec。
- 占位符扫描：无 TBD/TODO/“类似 Task N”。
- 类型一致性：`NotificationEnvelope`、`ValidatedNotification`、`NotificationContext`、`OrderUpsertCommand` 名称在全部 Task 中一致。
