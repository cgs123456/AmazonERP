# SP-API Restricted Data Token（RDT）API-Ready 实施计划

- 状态：**离线 API-Ready 已实施；真实 RDT/订单 PII 未联调；配置默认关闭**
- 日期：2026-09-26
- 分支：`codex/api-ready-connectors`
- 目标仓库：`C:\Users\Administrator\Desktop\AmazonERP`
- 实施门禁：用户已要求继续实施离线 API-Ready 范围；未取得真实凭证前不得伪造联调或生产证据。
- 实施方法：`superpowers:test-driven-development`；每个任务先写失败测试，再写最小实现，再重构。

## 1. 目标与边界

### 1.1 目标

让 SP-API 在拿到合规凭证后，具备合规获取订单 PII 的技术能力：

1. 使用普通 LWA access token 调用官方 Tokens API 获取 RDT。
2. RDT 只保存在内存，按店铺、marketplace、资源集合隔离，短时有效，过期前刷新。
3. `SpApiGateway` 不再隐式固定使用普通 LWA；受限调用必须显式声明 token source。
4. Orders 的买家信息和收货地址只能通过 RDT 请求；受限失败不得回退普通 LWA。
5. SP-API Outbox 重放时不保存 RDT，重放前重新获取。
6. 为后续 Notifications、Messaging PII、RMA、面单等受限调用提供统一底座。
7. 保持当前对外状态：**具备对接能力（未联调）**；真实凭证前不声明 E4/E5。

### 1.2 非目标

- 本计划不实现 Notifications、Listings Items、Product Pricing、Catalog Items、FBA Inbound。
- 本计划不伪造真实凭证、沙箱请求、Amazon 审核或生产联调记录。
- 本计划不把 RDT 写入数据库、Outbox、缓存、日志、指标标签、异常文本或 API 响应。
- 本计划不自动开启 PII 同步；必须有显式配置开关。
- 本计划不绕过 Amazon 应用审批、卖家授权、角色、SPDS 或 DPP 要求。

### 1.3 官方契约事实

来源：

- [Connecting to the SP-API](https://developer-docs.amazon.com/sp-api/docs/connecting-to-the-selling-partner-api)
- [Authorization with Restricted Data Token](https://developer-docs.amazon.com/sp-api/docs/authorization-with-restricted-data-token)
- [Tokens API OpenAPI model](https://github.com/amzn/selling-partner-api-models/blob/main/models/tokens-api-model/tokens_2021-03-01.json)

已核实：

- operation：`createRestrictedDataToken`
- method/path：`POST /tokens/2021-03-01/restrictedDataToken`
- usage plan：`1 req/s`，burst `10`
- 请求字段：`restrictedResources` 必填，1–50 项；每项包含 `method`、`path`，可选 `dataElements`
- 响应字段：`restrictedDataToken`、`expiresIn`
- 模型文件：15,751 bytes
- sha256：`3cd09ae7f218c83f32536a894cb8c42f2191c94b9c27f6bcf0a164442089b061`

工程解释：

- RDT 是短时、受限的 access token，不是长期凭证。
- RDT 的请求必须使用普通 LWA access token 获取；受限业务请求再把 RDT 放入 `x-amz-access-token`。
- 受限资源集合必须和实际请求匹配；不能“申请所有权限”后长期复用。
- 401/403、过期、资源不匹配和响应缺失都必须失败关闭。

## 2. 设计决定

### 2.1 组件

计划新增或调整：

| 组件 | 责任 | 安全边界 |
|---|---|---|
| `TokensClient` | 调官方 Tokens API，解析 RDT 和 `expiresIn` | 只接收脱敏后的 shop 上下文；不打印 token |
| `RestrictedDataTokenManager` | 缓存、刷新、失效、按 shop/marketplace/resource set 隔离 | 内存有界；不得持久化；键中不得含 token |
| `RestrictedResource` | 规范化 method/path/dataElements，最多 50 项 | 拒绝空值、未知 method、相对路径、控制字符 |
| `SpApiGateway.TokenSource` | 明确 `LWA` 或 `RDT` | 受限调用不能默认降级 |
| `OrdersClient` | 显式提供受限订单方法 | PII 与普通字段分离 |
| `SpiRateLimiter` | 注册 `tokens.createRestrictedDataToken` 官方配额 | 不得自造速率 |
| `ConnectorRegistry` | 在真实联调前保持未达标证据 | 不因代码存在就抬高 E4/E5 |

### 2.2 关键不变量

1. `restrictedDataToken`、普通 access token、refresh token、client secret 不得进入日志。
2. RDT 缓存键必须至少包含 `shopId + marketplaceId + canonical resource set`。
3. 不同店铺、不同 marketplace、不同资源集合不得复用 token。
4. 缓存条目必须包含绝对过期时间；刷新需要提前量。
5. 401/403 只能失效当前 shop/marketplace/resource set 对应的条目。
6. 请求失败不得回退到普通 LWA。
7. Outbox 记录不得新增 token 字段；重放重新获取 RDT。
8. 配置关闭时，受限入口必须明确返回“未启用”，不能静默返回空数据。
9. 资源数量超过 50、重复资源、非法路径或非法 method 必须在发请求前失败。
10. 任何 token 管理异常都 fail-closed。

### 2.3 配置草案

```yaml
spapi:
  restricted-data:
    enabled: ${SPAPI_RESTRICTED_DATA_ENABLED:false}
    refresh-skew-seconds: ${SPAPI_RESTRICTED_DATA_REFRESH_SKEW_SECONDS:60}
    max-cache-entries: ${SPAPI_RESTRICTED_DATA_MAX_CACHE_ENTRIES:1000}
```

说明：

- 默认 `false`，避免部署后无意同步 PII。
- 只有生产联调明确需要时才在 Secret/ConfigMap 中开启。
- `refresh-skew-seconds` 必须小于 token 有效期；配置校验失败时拒绝启动。
- `max-cache-entries` 防止多店大 marketplace 下无界增长。

## 3. 文件边界（离线实施实际范围）

### 3.1 预计新增

- `amz-service/amz-service-spapi/src/main/java/com/amz/client/TokensClient.java`
- `amz-service/amz-service-spapi/src/main/java/com/amz/connector/RestrictedResource.java`
- `amz-service/amz-service-spapi/src/main/java/com/amz/connector/RestrictedDataTokenManager.java`
- `amz-service/amz-service-spapi/src/main/java/com/amz/config/RestrictedDataProperties.java`
- `amz-service/amz-service-spapi/src/test/java/com/amz/client/TokensClientContractTest.java`
- `amz-service/amz-service-spapi/src/test/java/com/amz/connector/RestrictedResourceTest.java`
- `amz-service/amz-service-spapi/src/test/java/com/amz/connector/RestrictedDataTokenManagerTest.java`
- `amz-service/amz-service-spapi/src/test/java/com/amz/client/SpApiGatewayRestrictedDataTest.java`
- `amz-service/amz-service-spapi/src/test/java/com/amz/client/OrdersClientRestrictedDataTest.java`
- `amz-service/amz-service-spapi/src/test/resources/contracts/tokens_2021-03-01.json`
- 未新增 `rdt/` 夹具目录；脱敏桩回放直接使用现有测试 transport 与内联 JSON。

### 3.2 预计修改

- `amz-service/amz-service-spapi/src/main/java/com/amz/client/SpApiGateway.java`
- `amz-service/amz-service-spapi/src/main/java/com/amz/client/OrdersClient.java`
- `amz-service/amz-service-spapi/src/main/java/com/amz/connector/SpApiRequestFactory.java`（仅在测试证明必要时）
- `amz-service/amz-service-spapi/src/main/java/com/amz/ratelimit/SpiRateLimiter.java`
- `amz-service/amz-service-spapi/src/main/java/com/amz/connector/ConnectorRegistry.java`
- `amz-service/amz-service-spapi/src/main/resources/application.yml`
- `amz-service/amz-service-spapi/src/main/java/com/amz/connector/SpApiTokenSource.java`
- `amz-service/amz-service-spapi/src/main/java/com/amz/service/SpApiCallOutboxService.java`
- `amz-service/amz-service-spapi/src/main/java/com/amz/scheduler/SpApiOutboxReplayScheduler.java`
- `amz-service/amz-service-spapi/src/main/resources/db/migration/V5__spapi_outbox_token_source.sql`
- `amz-service/amz-service-spapi/src/test/java/com/amz/client/SpApiPathContractTest.java`
- `amz-service/amz-service-spapi/src/test/java/com/amz/ratelimit/SpiRateLimiterTest.java`
- `amz-service/amz-service-spapi/src/test/java/com/amz/connector/ConnectorRegistryTest.java`
- `amz-service/amz-service-spapi/src/test/resources/contracts/README.md`
- `README.md`
- `docs/superpowers/specs/2026-09-24-amazon-erp-production-design.md`
- `docs/superpowers/plans/2026-09-24-connector-api-ready-phase0.md`
- `docs/superpowers/runbooks/connector-acceptance-runbook.md`

### 3.3 明确不修改

- 实际新增 `V5__spapi_outbox_token_source.sql`，只保存 token source、受限资源摘要与 AES-256-GCM 密文，不保存 RDT 本体。
- 已修改 `SpApiCallOutboxEntity` 和 Outbox DDL 以恢复重放 token source；不新增 RDT 列。
- 不修改已暂存的 Messaging 删除。
- 不修改与 RDT 无关的业务模块。
- 不执行 `git add .`。

## 4. TDD 任务

### Task 1：RestrictedResource 规范化与校验

**先写测试：**

- 接受 `GET`/`POST` 等合法 method。
- 接受以 `/` 开头的官方路径。
- 拒绝空 method/path、相对路径、控制字符、重复资源。
- `dataElements` 去重、保序、不可为空字符串。
- 最多 50 项；51 项必须在本地失败。
- 规范化后生成稳定 key，顺序不同但集合相同的资源产生相同 key。

**最小实现：**

- record/class 保存 method、path、dataElements。
- 提供 `canonicalKey()`，用于缓存键。
- 不在异常消息中回显完整敏感路径参数或 token。

**完成标准：**

- `RestrictedResourceTest` 全绿。
- 无网络调用、无 Spring 上下文依赖。

### Task 2：TokensClient 请求与响应契约

**先写测试：**

- 请求 method/path 为 `POST /tokens/2021-03-01/restrictedDataToken`。
- 请求体包含 `restrictedResources`，并保留 method/path/dataElements。
- 1–50 项边界正确。
- 响应缺 `restrictedDataToken`、缺 `expiresIn`、空 token、非正数 expiresIn 均显式失败。
- 响应异常不回显 token。
- 使用普通 LWA access token 调 Tokens API，而不是 RDT。

**最小实现：**

- `TokensClient` 复用现有传输、错误分类和 Outbox 边界。
- 解析为内部不可变结果：token + expiresAt。
- 不把 token 放入日志、异常或指标。

**完成标准：**

- `TokensClientContractTest` 全绿。
- 官方模型路径快照测试通过。

### Task 3：RestrictedDataTokenManager 缓存与隔离

**先写测试：**

- 相同 `shopId + marketplaceId + resource set` 复用未过期 token。
- 不同 shop、marketplace、resource set 不复用。
- 距离过期小于 refresh skew 时刷新。
- 并发请求只允许受控刷新，不产生无界重复请求。
- 401/403 失效当前键，不影响其他键。
- 缓存达到上限时有界淘汰。
- token 不进入 `toString()`、日志或异常。

**最小实现：**

- 使用有界并发缓存。
- 记录绝对过期时间。
- 提供 `getToken(...)`、`invalidate(...)`、`clear()`。
- 使用可控 clock 便于测试。

**完成标准：**

- `RestrictedDataTokenManagerTest` 全绿。
- 并发和过期边界可重复。
### Task 4：SpApiGateway 显式 token source

**先写测试：**

- `LWA` source 保持现有行为。
- `RDT` source 使用 `RestrictedDataTokenManager` 获取 token。
- RDT 获取失败时，业务请求不发出。
- 受限请求的 `x-amz-access-token` 是 RDT，不是普通 LWA。
- 401/403 失效 RDT 缓存，不调用 LWA invalidate 逻辑。
- 普通 LWA 401/403 仍失效 LWA 缓存。
- 不提供默认 token source 的受限调用编译失败或显式失败。
- 日志、异常和 Outbox 不包含 token。

**最小实现：**

- 引入明确的 token source 参数。
- 保留现有普通调用 API 的兼容入口，但内部映射为显式 `LWA`。
- 受限入口必须传 `RDT`。
- 不改变 `SpApiRequestFactory` 的 header 注入职责，除非测试证明必要。

**完成标准：**

- `RestrictedDataGatewayTest` 和既有网关测试全绿。
- 无 LWA 回退路径。

### Task 5：Orders 受限字段调用

**先写测试：**

- `getOrders` 受限调用申请 `buyerInfo + shippingAddress`。
- `getOrderItems` 受限调用申请 `buyerInfo`。
- 普通订单字段和 PII 字段分别映射。
- 配置关闭时受限入口明确失败/禁用，不返回空成功。
- PII 不进入普通日志和普通 Outbox 明文。
- 分页 token、marketplaceId、shopId 不串店。

**最小实现：**

- `OrdersClient` 增加显式受限方法或参数。
- 将 PII 映射限制在最小必要字段。
- 为后续 PII vault 留出接口，但本计划不实现 vault。

**完成标准：**

- `OrdersClientRestrictedDataTest` 全绿。
- 不改变普通订单同步的默认行为。

### Task 6：Outbox 重放重新获取 RDT

**先写测试：**

- Outbox `CallRequest` 和 `ReplayCall` 不新增 token 字段。
- 受限 GET 重放时重新调用 `RestrictedDataTokenManager`。
- 重放前 RDT 已过期仍能成功。
- RDT 获取失败时记录明确失败，不发送业务请求。
- 普通请求重放继续使用 LWA。
- 401/403 重放失效正确缓存。

**最小实现：**

- 在 gateway 重放路径恢复 token source 语义。
- 不持久化 token。
- 保持现有 `PENDING / SUCCEEDED / FAILED / REPLAYING / REPLAYED / DLQ` 状态。

**完成标准：**

- Outbox 既有测试和新增 RDT 重放测试全绿。
- 不新增数据库列。

### Task 7：限流、能力清单和证据等级

**先写测试：**

- `tokens.createRestrictedDataToken` 注册官方 `1 req/s`、burst `10`。
- 限流按店铺隔离，不按全局单桶。
- `ConnectorRegistry` 不再把 RDT 标为未实现。
- RDT 代码存在不自动把 A5 从 E0 抬高。
- 在无真实联调时 `apiReady=false`、`reachable=false`。
- UI 对外显示仍为“具备对接能力（未联调）”。

**最小实现：**

- 更新限流 operation 表。
- 更新能力清单和证据声明。
- 不伪造 E4/E5 来源。

**完成标准：**

- `SpiRateLimiterTest`、`ConnectorRegistryTest` 全绿。
- 能力清单和实现双向一致。

### Task 8：官方模型快照与文档

**先写测试：**

- 快照 byte count 为 15,751。
- sha256 为官方值。
- 路径契约扫描接受 Tokens API 路径。
- 快照读取失败时测试失败，不静默跳过。
- README、spec、runbook 明确“未联调”边界。

**最小实现：**

- 下载并锁定官方模型。
- 更新 contracts README、生产设计、Phase 0 计划和验收 runbook。
- 记录获取时间、URL、hash 和消费者。

**完成标准：**

- 契约测试全绿。
- 文档不再声称“有 API 即可直接使用”。

## 5. 验收矩阵

| 验收项 | 离线可完成 | 需要真实/沙箱凭证 | 通过标准 |
|---|---:|---:|---|
| RDT 请求模型与路径 | 是 | 否 | 官方快照 + hash + 路径契约通过 |
| 资源数量/方法/路径校验 | 是 | 否 | 边界和负向测试通过 |
| token/expiry 解析 | 是 | 否 | 缺失/畸形响应显式失败 |
| 缓存与店铺隔离 | 是 | 否 | 不同 shop/marketplace/resource 不复用 |
| RDT 作为 `x-amz-access-token` | 是 | 否 | 桩回放捕获 header 并断言 |
| 无 LWA 回退 | 是 | 否 | RDT 失败时业务请求数保持 0 |
| 401/403 缓存失效 | 是 | 否 | 只失效正确键，不影响其他键 |
| Outbox 重放重新获取 | 是 | 否 | 不新增 token 列，重放重新申请 |
| 官方限流 | 是 | 否 | 1 req/s、burst 10 双向契约 |
| 真实 RDT 获取 | 否 | 是 | 沙箱/生产请求 ID + 脱敏证据 |
| 受限 Orders PII | 否 | 是 | 资源匹配 + PII 脱敏 + 权限验证 |
| E4/E5 | 否 | 是 | 真实联调/生产运行证据 |

## 6. 发布与回滚

1. 合并前默认 `spapi.restricted-data.enabled=false`。
2. 先部署不启用配置的版本，确认普通 SP-API 调用无回归。
3. 在沙箱开启配置，只执行只读受限 Orders 调用。
4. 验证资源匹配、token 不泄漏、429/401/403/过期处理。
5. 生产先单店只读，观察窗口通过后再决定是否接入 PII 持久化。
6. 回滚时关闭配置即可停止受限调用；不得回滚已写入的 PII 删除/保留义务。
7. 不保存 RDT，因此无 token 数据迁移；业务数据回滚按 PII 策略单独处理。

## 7. 风险与未决问题

- **权限范围**：RDT 可申请的资源不等于已获批的数据权限；Amazon 仍可能拒绝。
- **资源匹配**：path 中包含订单号时必须与实际请求逐字匹配；缓存键和请求构造必须一致。
- **多店隔离**：RDT 缓存键缺 marketplace 或 resource set 会导致串用或错误拒绝。
- **并发刷新**：缓存 stampede 会触发 Tokens API 限流；需要单飞刷新或有界退避。
- **Outbox**：重放时业务状态可能已变化，RDT 重获取成功不代表业务动作仍应执行。
- **PII 生命周期**：RDT 只解决“如何获取”，不解决“如何存储、保留、删除和审计”。
- **可观测性**：可以记录缓存命中/刷新/失效计数，但不能用 token、订单号或买家信息做标签。
- **测试真实性**：桩回放只能证明代码行为，不能替代 Amazon 沙箱的 401/403、权限和限流响应。

## 8. Definition of Done

### 8.1 已完成（离线 API-Ready）

- [x] 用户已要求继续实施离线 API-Ready 范围；真实联调仍等待凭证与 Amazon 授权。
- [x] 新增测试按 TDD 先红后绿，SP-API 全模块与全仓回归通过。
- [x] RDT 请求路径、字段、byte count、sha256 和 usage plan 有官方快照证据。
- [x] 受限调用没有普通 LWA 回退；RDT 获取失败时业务请求数为 0。
- [x] RDT 不落库、不打印、不出现在异常、指标标签或 API 响应。
- [x] 店铺、marketplace、资源集合缓存隔离有负向测试；401/403 只失效正确 token source。
- [x] Outbox 重放恢复 token source 并重新获取 RDT，不新增 token 列。
- [x] `ConnectorRegistry` 登记为 26 条已实现 / 5 条未实现，且仍诚实显示“具备对接能力（未联调）”。
- [x] 生产配置默认关闭；RDT 与订单 PII 同步都需要显式开启。
- [x] 没有修改已暂存的 Messaging 删除，没有执行 `git add .`。

### 8.2 未完成（真实联调/生产）

- [ ] 真实 Tokens API 获取 RDT 成功，并保存脱敏的请求 ID、状态和限流证据。
- [ ] 真实 Orders PII 请求证明 `buyerInfo` / `shippingAddress` 资源与方法匹配。
- [ ] 验证 Amazon 应用审批、卖家授权、角色、marketplace、region、RDT/SPDS 权限。
- [ ] 验证真实 401/403/404/429/5xx、过期、资源不匹配、重试和 Outbox 重放。
- [ ] 完成 PII 加密、掩码、访问审计、保留/删除和备份恢复后的删除验证。
- [ ] 完成 E4 沙箱和 E5 生产验收；在此之前不得宣称“已接通”或“可直接生产部署”。

## 9. 实施结论与真实联调入口

当前状态是 **离线 API-Ready 已实施、默认关闭、未联调**。代码存在只证明接入路径、缓存隔离、失败关闭和官方契约已固化，不证明 Amazon 已授权或接受请求。

凭证与授权到位后按以下顺序继续：

1. 先关闭两个业务开关部署，确认普通 LWA/SP-API 调用无回归。
2. 配置 LWA、卖家授权、marketplace、region 和 RDT/SPDS 权限。
3. 在沙箱开启 RDT，只执行只读 Tokens/Orders 调用并记录脱敏证据。
4. 再开启订单 PII 同步，验证资源匹配、401/403/429、分页和 Outbox 重放。
5. 完成 PII 生命周期与生产观测后，才讨论 E4/E5 和上线。
