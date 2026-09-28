# SP-API 通知入站（Notifications Inbox）与订单 Upsert 设计规格

- 文档日期：2026-09-26
- 仓库：`C:\Users\Administrator\Desktop\AmazonERP`
- 分支：`codex/api-ready-connectors`，基线提交 `aee00c6`
- 状态：设计规格草案，待用户 review；本轮不修改生产代码
- 关联文档：
  - `C:\Users\Administrator\Desktop\AmazonERP\docs\superpowers\specs\2026-09-26-production-upgrade-execution-matrix.md`
  - `C:\Users\Administrator\Desktop\AmazonERP\docs\superpowers\specs\2026-09-26-performance-and-business-optimization-audit.md`
  - `C:\Users\Administrator\Desktop\AmazonERP\docs\superpowers\plans\2026-09-26-spapi-remaining-capabilities-api-ready.md`

## 1. 目标与口径

### 1.1 业务目标

在没有真实 Amazon 凭证的阶段，系统必须能用合成数据完整运行通知驱动链路；拿到合规 API 权限后，只切换事件源实现与配置，不重写业务主链路。

### 1.2 必须纠正的前提

“有 API 就可以直接使用”不成立。本设计交付的是**对接能力**，不是“已接通”。

真实可用仍需要：Amazon 应用审批、卖家授权、角色/RDT/SPDS 权限、SQS/EventBridge 队列与 IAM 配置、订阅映射、沙箱联调、生产验收。完成 E4/E5 取证前，对外口径只能是“具备对接能力（未联调）”。

### 1.3 证据等级

本规格可交付的最高等级为 E1/E2：仓库内自证、单元测试、故障注入、桩回放。真实 SQS/EventBridge 与 Amazon 联调属于 E4，默认以跳过形式存在，**skipped 不得计为通过**。

## 2. 范围

### 2.1 范围内

1. 通知入站链路：`NotificationEventSource` 抽象 + `MockNotificationEventSource` + `SqsNotificationEventSource`。
2. 通知 Inbox 持久化、去重、领取、重试、DLQ、毒消息隔离。
3. `ORDER_CHANGE`、`FEED_PROCESSING_FINISHED`、`REPORT_PROCESSING_FINISHED` 三类 Handler；其他类型标记为 `UNSUPPORTED` 并保留原始事件，不静默丢弃。
4. 订单 Upsert：`amz-service-order` 从“存在即跳过”改为幂等 upsert。
5. `amz_order` 店铺维度唯一键迁移与查询隔离。
6. 订阅/目标映射表、订阅对账调度、PII 与日志脱敏、指标与健康检查。

### 2.2 范围外（后续 spec）

Listing/Feed/库存写操作自动化、财务结算对账、前端页面、报表导出、性能容量治理、多租户横切整改的第二阶段。

## 3. 当前代码事实（本轮核对）

1. `C:\Users\Administrator\Desktop\AmazonERP\amz-service\amz-service-spapi\src\main\java\com\amz\client\NotificationsClient.java` 只有订阅与 destination 管理 operation；类注释已明确 SQS/SNS 投递、验签、去重、排序、DLQ 属于 consumer 职责。
2. `C:\Users\Administrator\Desktop\AmazonERP\amz-service\amz-service-spapi\pom.xml` 没有 AWS SDK v2、SQS、EventBridge 依赖；`C:\Users\Administrator\Desktop\AmazonERP\pom.xml` 的 `dependencyManagement` 也没有 AWS BOM。
3. `C:\Users\Administrator\Desktop\AmazonERP\amz-service\amz-service-order\src\main\java\com\amz\service\impl\OrderServiceImpl.java:184-266`：先按 `amazonOrderId` `selectCount`，存在即 `return`，只插入不更新；已存在订单的后续状态变更会被丢弃。
4. `C:\Users\Administrator\Desktop\AmazonERP\amz-service\amz-service-order\src\main\resources\db\migration\V1__init.sql:38`：唯一键为 `uk_amazon_order (amazon_order_id)`，没有 `shop_id`，多店铺存在串店与抢占唯一键风险。
5. `C:\Users\Administrator\Desktop\AmazonERP\amz-service\amz-service-spapi\src\main\java\com\amz\outbox\SpApiCallOutboxService.java` 是出站调用审计/重放账本，不能当入站 Inbox。
6. `C:\Users\Administrator\Desktop\AmazonERP\amz-service\amz-service-spapi\src\main\java\com\amz\scheduler\OrderSyncScheduler.java` 中 `publishSaveMessage`、`publishProfitMessages`、`sendJson` 为 private；`sendJson` 捕获异常后只 `log.error`，存在 MQ 丢失风险。通知 Handler 需要抽取共享 publisher，禁止复制粘贴。
7. `C:\Users\Administrator\Desktop\AmazonERP\amz-service\amz-service-spapi\src\main\java\com\amz\credential\ConnectorStartupCheck.java` 与 `application-prod.yml` 已建立 fail-closed 启动校验风格，本设计沿用同一风格。

## 4. 目标架构

```text
Amazon SP-API
   |  (SQS workflow: SP-API -> SQS)
   |  (EventBridge workflow: SP-API -> EventBridge -> SQS target)
   v
[SQS Standard Queue]  --IAM 角色独立于店铺凭证--
   |
   v
NotificationEventSource
   |-- MockNotificationEventSource    (mock profile, 合成事件)
   |-- SqsNotificationEventSource     (prod/联调 profile, AWS SDK v2)
   |
   v
NotificationInboxService      -> amz_spapi_notification_inbox
   |
   v
NotificationInboxWorker       -> 原子领取 + 租约 + 重试 + DLQ
   |
   v
NotificationEventHandler
   |-- OrderChangeHandler       -> OrderUpsertPublisher -> RabbitMQ -> amz-service-order upsert
   |-- FeedFinishedHandler      -> FeedsClient.fetchFeedResult
   |-- ReportFinishedHandler    -> ReportsRealClient.getReport / downloadDocument
   |-- UnsupportedTypeHandler   -> 落 UNSUPPORTED，等待后续实现
```

核心约束：

- 队列必须使用 **SQS standard queue**。SP-API 不支持 FIFO SQS；standard queue 不保证顺序且可能重复投递，所以去重与乱序保护必须在应用侧实现。
- 消费端必须使用**独立 IAM 角色**（IRSA / 实例角色 / 显式 `role-arn` 假设），禁止复用 `amz_shop_credential` 中的 AWS access key / secret key。
- 事件先落 Inbox 再删除 SQS 消息；DB 写入失败绝不删除消息。

## 5. 组件职责

| 组件 | 职责 | 依赖 |
|---|---|---|
| `NotificationEventSource` | 抽象“拉取一批原始通知”；只负责传输，不理解业务 | 无 |
| `MockNotificationEventSource` | 从合成数据集或测试注入读取事件，payload 带 `SYNTHETIC` 标记 | `spapi.notifications.mock.*` |
| `SqsNotificationEventSource` | 长轮询 `ReceiveMessage`，处理成功后 `DeleteMessage` | AWS SDK v2 `sqs`，独立 IAM 角色 |
| `NotificationInboxService` | 校验、加密、哈希、去重、落库、状态迁移 | `CryptoUtil`，Inbox Mapper |
| `NotificationInboxWorker` | 定时/循环原子领取、调用 Handler、重试与 DLQ | `NotificationEventHandler` 列表 |
| `NotificationEventHandler` | 按类型处理单条事件，必须自带业务幂等键 | 下游 client / publisher |
| `NotificationSubscriptionAdmin` | 维护 destination/subscription 与 shop 映射，支持对账 | `NotificationsClient` |

边界规则：EventSource 不直接执行业务；Worker 不解析业务 JSON；Handler 不负责重试与去重框架。

## 6. 数据模型（Flyway）

`amz-service-spapi` 当前已有 V1–V5，新增从 **V6** 开始：

- `V6__spapi_notification_destination.sql`
- `V7__spapi_notification_subscription.sql`
- `V8__spapi_notification_inbox.sql`

### 6.1 `amz_spapi_notification_destination`

字段：`destination_id`、`shop_id`、`marketplace_id`、`resource_type`（`SQS` / `EVENT_BRIDGE`）、`queue_arn`、`event_bus_arn`、`region`、`status`、`create_time`、`update_time`。

约束：`PRIMARY KEY (destination_id)`；`UNIQUE KEY uk_destination_shop_market (shop_id, marketplace_id, destination_id)`。

### 6.2 `amz_spapi_notification_subscription`

字段：`subscription_id`、`destination_id`、`shop_id`、`marketplace_id`、`notification_type`、`payload_version`、`filter_expression`、`event_filter`、`status`、`create_time`、`update_time`。

约束：`PRIMARY KEY (subscription_id)`；`KEY idx_subscription_type (notification_type, status)`。

Shop 解析规则：由 `NotificationMetadata.SubscriptionId` 查本表得到 `shop_id`、`marketplace_id`。查不到映射的事件进入 `UNRESOLVED_SUBSCRIPTION` 隔离状态并告警，**不写 DLQ、不删除 SQS 消息**，由对账调度或人工补映射后重放。

### 6.3 `amz_spapi_notification_inbox`

字段：`id`、`notification_id`（UNIQUE）、`notification_type`、`payload_version`、`event_time`、`publish_time`、`shop_id`、`marketplace_id`、`application_id`、`subscription_id`、`destination_id`、`payload_encrypted`（AES-256-GCM）、`payload_sha256`、`status`、`attempt_count`、`max_attempts`、`next_attempt_at`、`lease_owner`、`lease_until`、`last_error_code`、`last_error_message`、`duplicate_count`、`created_at`、`updated_at`、`processed_at`。

状态机：`RECEIVED -> PROCESSING -> PROCESSED | FAILED -> (重试) -> DLQ`，另有 `UNSUPPORTED`、`UNRESOLVED_SUBSCRIPTION`、`INVALID`。

索引：`UNIQUE KEY uk_notification_id (notification_id)`；`KEY idx_inbox_due (status, next_attempt_at)`；`KEY idx_inbox_shop_type_time (shop_id, notification_type, event_time)`；`KEY idx_inbox_lease (lease_owner, lease_until)`。

存储约定：`payload_encrypted` 使用 `CryptoUtil`（AES-256-GCM，密钥来自 `AMZ_CRYPTO_KEY`）；日志只输出 `notification_id`、`notification_type`、`shop_id`、`payload_sha256`、错误码，禁止输出买家姓名、地址、电话等 PII。

## 7. 订单 Upsert 与店铺隔离

### 7.1 服务接口

`amz-service-order` 新增：

- `OrderService.upsertAmazonOrder(OrderUpsertCommand)`。
- `OrderUpsertCommand` 在现有 `OrderSyncDto` 基础上增加：`eventTime`、`notificationId`、`source`（`SPAPI_PULL` / `SPAPI_NOTIFICATION` / `SYNTHETIC`）。
- 现有 `syncAmazonOrder(OrderSyncDto)` 保留签名，委托到 upsert；`eventTime` 缺省时回退为 `lastUpdateDate`，两者皆缺失则拒绝更新状态字段（只允许插入，不允许覆盖）。

### 7.2 Upsert 语义

1. 查询条件必须同时带 `shop_id` 与 `amazon_order_id`，禁止仅按 `amazon_order_id` 查询。
2. 不存在则插入，并触发一次业财凭证消息；存在则按“较新事件才覆盖”更新：`eventTime` / `lastUpdateDate` 早于库中值时跳过状态更新并记录 `order.stale_event.skipped` 指标。
3. 更新只写通知可证明的字段：`order_status`、`last_update_date`、`fulfillment_channel`、`ship_service_level`、`final_price`（金额仅在通知携带时更新）。
4. 并发冲突用唯一索引 + 原子 upsert 或 `DuplicateKeyException` 兜底；不允许返回部分成功。
5. 凭证消息发送沿用现有 afterCommit 语义；发送失败必须记录并进入对账补发，不允许静默吞异常。

### 7.3 `amz_order` 迁移

新增 `C:\Users\Administrator\Desktop\AmazonERP\amz-service\amz-service-order\src\main\resources\db\migration\V2__amazon_order_shop_scope.sql`。

迁移前置检查（必须先在运维环境执行并留证据）：

1. 检查是否存在 `shop_id IS NULL` 的 Amazon 订单；存在则先人工补 `shop_id`。
2. 检查是否存在同一 `amazon_order_id` 对应不同 `shop_id` 的行；存在则先人工裁决，不得由迁移脚本删数据。
3. 备份 `amz_order` 并确认可回滚。

迁移动作：

1. 删除 `uk_amazon_order`。
2. 新增 `UNIQUE KEY uk_shop_amazon_order (shop_id, amazon_order_id)`。
3. 新增 `KEY idx_shop_last_update (shop_id, last_update_date)`。

删除全局 `amazon_order_id` 唯一键是显式决定：它让一个店铺可以抢占另一个店铺同号订单的写入权。迁移失败时应用应拒绝启动（fail-closed），不得回退到旧查询语义。

## 8. 配置与 Profile 切换

统一前缀 `spapi.notifications`：

| 属性 | 默认 | 说明 |
|---|---|---|
| `source` | `mock` | `mock` / `sqs` |
| `queue-url` | 空 | SQS standard queue URL；`source=sqs` 时必填 |
| `region` | 空 | SQS region；`source=sqs` 时必填 |
| `sts-role-arn` | 空 | 可选跨账号角色；为空时使用运行时实例角色/IRSA |
| `wait-time-seconds` | `20` | 长轮询 |
| `max-messages` | `10` | 单次批量 |
| `visibility-timeout-seconds` | `60` | 必须大于 `lease-timeout-seconds` + Handler 最大耗时 |
| `batch-size` | `20` | Worker 单轮领取条数 |
| `max-attempts` | `5` | 超过进 DLQ |
| `base-delay-seconds` | `30` | 指数退避基数 |
| `lease-timeout-seconds` | `300` | 租约过期后可被其他实例回收 |
| `payload-max-bytes` | `262144` | 超过则隔离 |
| `subscription-sync-enabled` | `true` | 订阅对账调度 |
| `mock.scenario` | 空 | mock 场景名；空表示默认合成事件集 |

启动校验（新增到现有 fail-closed 校验链）：

1. `prod` profile 且 `source=mock` → 拒绝启动。
2. `source=sqs` 且 `queue-url` / `region` 任一为空 → 拒绝启动。
3. `source=sqs` 且检测到使用 `amz_shop_credential` 的 AWS 静态密钥作为 SQS 凭据 → 拒绝启动。
4. `visibility-timeout-seconds <= lease-timeout-seconds` → 拒绝启动。

AWS SDK 版本策略：新增 `software.amazon.awssdk:bom` 并在根 `pom.xml` 的 `dependencyManagement` 中固定为 `2.55.6`；禁止动态版本或版本区间。

## 9. 处理流程

1. EventSource 批量接收原始消息。
2. 逐条校验：JSON 可解析、大小未超限、存在 `NotificationVersion`、`NotificationType`、`PayloadVersion`、`EventTime`、`Payload`、`NotificationMetadata`，且 `NotificationMetadata` 内有 `ApplicationId`、`SubscriptionId`、`NotificationId`。
3. 按 `SubscriptionId` 解析 `shop_id` / `marketplace_id`；无法解析 → `UNRESOLVED_SUBSCRIPTION`。
4. 加密 payload、计算 `payload_sha256`，以 `notification_id` 唯一键落 Inbox；重复则 `duplicate_count + 1`，同哈希直接忽略，异哈希保留首条并告警。
5. Inbox 写入成功后才删除对应 SQS 消息；写入失败保留消息并退避重试。
6. Worker 原子领取：`UPDATE ... SET status='PROCESSING', lease_owner=?, lease_until=?, attempt_count=attempt_count+1 WHERE id=? AND status='RECEIVED' AND next_attempt_at <= NOW()`，返回受影响行数为 1 才处理。
7. Handler 执行；成功标记 `PROCESSED`；可重试错误按指数退避回 `RECEIVED`；不可重试或超过 `max-attempts` 进 `DLQ`。
8. 租约过期由恢复任务回收；`processed_at` 与指标同时更新。

## 10. 幂等矩阵

`notification_id` 唯一键只保证入口去重，不保证业务副作用 exactly-once。

| 领域 | 业务幂等键 |
|---|---|
| 订单 | `shop_id + amazon_order_id + event_time + notification_type` |
| Feed | `feed_id` |
| Report | `report_id` |
| 库存（后续） | `shop_id + seller_sku + marketplace_id + event_time + notification_type` |
| 未知/不支持类型 | 不产生业务副作用，仅落 `UNSUPPORTED` |

## 11. 安全与合规

1. SQS 消费使用独立 IAM 角色，最小权限：`sqs:ReceiveMessage`、`sqs:DeleteMessage`、`sqs:ChangeMessageVisibility`、`sqs:GetQueueAttributes`。
2. 禁止把店铺 SP-API AWS 密钥用于队列消费；`amz_shop_credential` 的 `access_key_encrypted` / `secret_key_encrypted` 仅用于 SP-API 出站 SigV4（如需）。
3. 通知 payload 可能包含 PII；入库加密，日志脱敏，禁止作为指标标签。
4. 队列必须开启服务端加密（SSE-SQS 或 KMS）；生产禁止明文队列。
5. `mock` 数据统一带 `SYNTHETIC` 标记，禁止进入生产业务库。

## 12. 可观测性

指标前缀 `amz.spapi.notification`：`received`、`duplicate`、`invalid`、`unresolved_subscription`、`unsupported`、`processed`、`retry_scheduled`、`dlq`、`payload_too_large`、`sqs_delete_failed`、`inbox_lag_seconds`、`handler_duration_seconds`。

健康检查：新增 `NotificationSourceHealthIndicator`；`source=mock` 时 UP；`source=sqs` 时探测 `GetQueueAttributes`，失败标记 DOWN，`/actuator/health` 细节仍为 `never`。

告警：DLQ 增长、租约回收频繁、`unresolved_subscription` 持续增长、`inbox_lag_seconds` 超阈值、`sqs_delete_failed > 0`。

## 13. 测试矩阵

必须覆盖：

1. `mock` profile 下无 AWS 凭证也能完成“接收 → Inbox → Handler → 业务副作用”全链路。
2. 同一 `notification_id` 重复投递只产生一次业务副作用。
3. 同一 `notification_id` 不同 payload 时保留首条并告警。
4. 乱序 `ORDER_CHANGE` 不覆盖更新状态，不产生负库存或旧状态回退。
5. 缺 `NotificationId` / `SubscriptionId` / `PayloadVersion` 时被隔离或 DLQ，不进入业务。
6. 未知 `NotificationType` 落 `UNSUPPORTED`，不产生副作用。
7. DB 写 Inbox 失败时 SQS 消息不删除。
8. 入库后、处理前崩溃可恢复。
9. 业务副作用已发生、标记成功前崩溃，不重复产生副作用。
10. 毒消息超过 `max-attempts` 进 DLQ。
11. 多店铺事件不串店；`shop_id` 缺失时必须拒绝。
12. PII 不进入日志；`payload_encrypted` 可解密且哈希一致。
13. `prod` profile 缺队列配置、或 `prod` + `mock` 同时激活时拒绝启动。
14. AWS SDK mock/桩验证 `ReceiveMessage`、`DeleteMessage`、visibility timeout、DLQ 路径。
15. 真实 SQS 联调测试默认 `@Disabled`，不计为通过。
16. `amz_order` 迁移前置检查脚本对脏数据返回非零退出码。

## 14. 真实接入前仍需完成的清单

即使代码具备对接能力，下列项未完成前不得宣称可用：

1. Amazon 应用审批与卖家授权。
2. destination 与 subscription 创建，并与本系统 `shop_id` / `marketplace_id` 对齐。
3. SQS standard queue、IAM 角色、KMS/SSE、DLQ 与可见性超时配置。
4. 订阅对账调度跑通，`subscription_id -> shop_id` 映射完整。
5. `sendTestNotification` 与真实事件验收到 E4 证据。
6. 订单、Feed、Report Handler 的真实字段契约核对与脱敏审计。
7. 生产监控、告警、回滚演练与对账。

## 15. 已决问题

1. 队列类型：`SQS standard`，不支持 FIFO。
2. EventBridge：仅允许“EventBridge → SQS target”的间接消费；第一阶段不实现 EventBridge 直连消费者。
3. 首期 Handler：`ORDER_CHANGE`、`FEED_PROCESSING_FINISHED`、`REPORT_PROCESSING_FINISHED`。
4. 订单唯一键：删除全局 `amazon_order_id` 唯一键，改为 `(shop_id, amazon_order_id)` 复合唯一键。
5. 幂等：Inbox 去重与 Handler 业务幂等键双层实现。
6. 凭据：SQS 消费独立 IAM 角色，禁止复用店铺静态 AWS 密钥。

## 16. 参考来源

- `https://developer-docs.amazon.com/sp-api/docs/set-up-notifications-with-amazon-sqs`
- `https://developer-docs.amazon.com/sp-api/docs/set-up-notifications-with-amazon-eventbridge`
- `https://developer-docs.amazon.com/sp-api/docs/filter-notification-subscriptions`
- `https://raw.githubusercontent.com/amzn/selling-partner-api-models/main/schemas/notifications/OrderChangeNotification.json`
- `https://repo1.maven.org/maven2/software/amazon/awssdk/bom/maven-metadata.xml`（本轮核实时 latest/release = `2.55.6`）

仓库内事实来源：

- `C:\Users\Administrator\Desktop\AmazonERP\amz-service\amz-service-spapi\src\test\resources\contracts\notifications.json`
- `C:\Users\Administrator\Desktop\AmazonERP\amz-service\amz-service-spapi\src\main\java\com\amz\client\NotificationsClient.java`
- `C:\Users\Administrator\Desktop\AmazonERP\amz-service\amz-service-order\src\main\java\com\amz\service\impl\OrderServiceImpl.java`
- `C:\Users\Administrator\Desktop\AmazonERP\amz-service\amz-service-order\src\main\resources\db\migration\V1__init.sql`
- `C:\Users\Administrator\Desktop\AmazonERP\amz-common\src\main\java\com\amz\util\CryptoUtil.java`
