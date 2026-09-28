# AmazonERP 生产化审计与 API-Ready 升级方案

- 日期：2026-09-26
- 状态：只读审计与设计；本轮不修改生产代码、SQL、配置或部署清单
- 分支：`codex/api-ready-connectors`
- 适用范围：`C:\Users\Administrator\Desktop\AmazonERP`
- 关联文档：
  - `docs/superpowers/specs/2026-09-26-production-upgrade-execution-matrix.md`
  - `docs/superpowers/plans/2026-09-26-spapi-rdt-api-ready.md`
  - `docs/superpowers/plans/2026-09-24-connector-api-ready-phase0.md`
  - `docs/superpowers/runbooks/connector-acceptance-runbook.md`

## 0. 结论摘要

当前仓库已经具备较完整的离线工程基线和一部分 API-Ready 基座：

- 后端 Reactor 模块曾完成 19/19 构建；Surefire 报告曾汇总 161 份、1053 tests、0 failures/errors、2 skipped（真实凭证集成测试）。
- 前端曾完成 19 个测试文件、144 tests、0 failures；Vite production build 成功。
- 合成数据曾生成 100 张表、4997 行；结构、引用、标记、确定性和快照检查通过。
- SP-API 当前能力台账为 26 条已实现 operation、5 条未实现能力（共 31 条）；RDT/Tokens API 已进入离线 API-Ready 范围，已实现清单与 `com/amz/client` 真实 operationId 调用点双向一致。
- 当前证据评估仍为 `apiReady=false`、`reachable=false`。
- 对外只能写：**具备对接能力（未联调）**。不能写“已接通”“有凭证即可直接使用”或“生产可用”。

“有 API 就能直接使用”需要纠正为下面这句：

> 有合规 API 凭证后，代码和配置层面可以直接进入联调，不需要重写主链路；但是否真正可用，还取决于 Amazon 应用审批、卖家授权、角色权限、marketplace/region、RDT、限流、沙箱验证、生产验证和凭证轮换。

当前最优先的风险不是“API 客户端数量不够”，而是以下五类：

1. **库存正确性**：FIFO 出库存在并发超卖窗口。
2. **分布式任务正确性**：旧实现 Redis 故障时调度锁 fail-open；第 80 轮已将默认策略改为 fail-closed（代码级），但真实 Redis 双实例、断连、租期和崩溃接管回放仍未完成。
3. **受限数据合规**：RDT 尚未实现，Orders PII 没有完整合规路径。
4. **凭证与规模**：启动时全量加载店铺凭证，店铺增长后启动时间、内存和敏感信息暴露面线性增长。
5. **API 适配层完整性**：RDT、Notifications、Listings Items、Product Pricing、Catalog Items、FBA Inbound 仍未实现；部分客户端已存在但未纳入统一能力台账。

## 1. 证据等级与边界

沿用生产升级矩阵的等级口径：

| 等级 | 含义 |
|---|---|
| E0 | 只有设计或口头声明，没有可复现证据 |
| E1 | 仓库内自证，例如单元测试或静态契约测试 |
| E2 | 进程内桩、录制回放或本地契约测试 |
| E3 | 官方模型、官方文档或哈希锁定的外部事实 |
| E4 | 真实沙箱或真实凭证联调记录 |
| E5 | 生产运行、监控、审计和回滚证据 |

本审计中的“可实现”“可离线验证”不等于 E4/E5。任何没有真实 Amazon 请求 ID、响应证据、权限验证和脱敏记录的结论，都不能升级为 E4/E5。

## 2. P0 正确性风险

### P0-01 FIFO 出库并发超卖（代码级已修复，待真实 MySQL 集成回放）

**原缺陷**

- 修复前 `amz-service/amz-service-procurement/src/main/java/com/amz/service/impl/FbaShipmentServiceImpl.java:409-464` 先调用 `listBatchesBySku` 读取批次，再汇总 `availableTotal`，最后逐批 `updateById(batch)`。
- `updateById` 没有 `available_quantity >= deductQty` 条件，没有行锁、版本号或原子条件更新。
- `@Transactional` 只保证单个事务的一致性，默认隔离级别不能消除两个事务先读同一旧值再写的丢失更新；两个并发 FIFO 出库可以同时通过库存检查。

**已完成修复**

- `InventoryBatchMapper.decreaseAvailableQuantityAtomic(...)` 使用 MySQL 条件更新，返回受影响行数；`FbaShipmentServiceImpl#fifoOutbound` 仅在返回 1 时继续，返回 0 时抛出 `CodeErrorException`，由 `@Transactional(rollbackFor = Exception.class)` 回滚此前批次扣减，不返回部分成功。
- SQL 同时校验 `id`、`shop_id`、`sku`、`status = 'ACTIVE'` 和 `available_quantity >= #{qty}`，防止跨店/跨 SKU 误扣和并发超卖：

```sql
UPDATE amz_inventory_batch
SET status = CASE WHEN available_quantity = #{qty} THEN 'DEPLETED' ELSE status END,
    available_quantity = available_quantity - #{qty},
    update_time = NOW()
WHERE id = #{id}
  AND shop_id = #{shopId}
  AND sku = #{sku}
  AND status = 'ACTIVE'
  AND available_quantity >= #{qty}
```

- 上述顺序是有意设计：MySQL 单表 `UPDATE` 的赋值从左到右求值，`status` 必须在 `available_quantity` 之前计算；若照最初审计稿先扣减再判断状态，`CASE` 会读取已扣减值并造成二次扣减。

**验证证据**

- `InventoryBatchMapperContractTest`：1 / 0F / 0E / 0S，锁定 SQL 守卫和赋值顺序。
- `FbaShipmentFifoAtomicDeductionTest`：100 轮双线程内存原子扣减回放，每轮恰好 1 次成功、1 次库存不足，最终可用量均为 2，0 负库存。
- 红灯对照：把服务临时还原为 `updateById` 后，同一并发测试失败为 2/2 成功；恢复原子实现后 1/2 成功，证明测试确实捕获原缺陷。
- procurement 模块回归：**68 / 0F / 0E / 0S**；与 amz-common 110 条合并为 **178 / 0F / 0E / 0S**。

**未证明与残余风险**

- 尚未在真实 MySQL InnoDB、多连接、多事务条件下执行 100 轮并发集成回放，因此未实测行锁等待、死锁/重试、事务回滚后库存守恒和连接池行为。
- 内存模拟器只验证服务算法和受影响行数处理，不等价于数据库隔离级别与锁语义验证。
- 当前并发冲突采用整体失败策略，不自动重读后续批次；高竞争下可能返回可解释的失败而不是成功重试。

**验收状态**

- 代码级 SQL 契约、服务失败关闭和 100 轮并发算法回放已通过。
- 审计原要求的“真实 MySQL 并发集成测试”尚未完成，不能标记为全部验收通过。

**指标**

- 代码级 `inventory_batch_negative_total = 0`
- 代码级并发出库总扣减误差 = 0
- 代码级失败请求可解释率 = 100%
- 数据库级指标待真实 MySQL 并发回放。

**需要真实数据**

不需要真实业务数据；需要独立 MySQL InnoDB 集成测试环境。
### P0-02 DistributedJobLock Redis 故障时 fail-open（代码级已修复，待真实 Redis 双实例回放）

**修复前证据**

- 原实现明确写有 fail-open。
- Redis 不可达时捕获异常并设置 `acquired = true`。
- `StringRedisTemplate == null` 时也直接执行 action。

**业务影响**

- 多实例部署且 Redis 故障时，同一个定时任务会在每个实例同时执行。
- 对读任务可能只是重复计算；对写任务会造成重复同步、重复扣减、重复调价、重复告警和重复外部 API 配额消耗。
- 这会放大 Amazon 429 风险，并让审计日志难以还原唯一执行者。

**当前实现（2026-09-26 代码级）**

- 默认策略已改为 **fail-closed**：RedisTemplate 缺失、Redis 访问异常或抢锁结果不确定时不执行业务 action，直接返回 fallback。
- 新增 `runIdempotentWithLock(...)` 显式降级入口，仅供已经证明幂等或只读的任务使用；不存在全局 fail-open 开关。
- 现有 11 个 scheduler 类中的 14 个生产调用点全部保持默认 `runWithLock(...)`，没有把现有任务批量切换为降级执行。
- 非法 `leaseSeconds <= 0` 在访问 Redis 前直接拒绝；释放锁使用 token 匹配 Lua 脚本，不会误删其他实例后来持有的锁。
- 释放锁异常不会覆盖已经完成的业务结果；当前同时记录 `amz.scheduler.lock.release.failed` 指标和 warn 日志。
- 指标：`amz.scheduler.lock.acquire.failed`、`amz.scheduler.lock.degraded`、`amz.scheduler.lock.skipped`、`amz.scheduler.lock.release.failed`，reason 包括 `redis_template_missing`、`redis_error`、`lock_held`。

**验收状态**

- 代码级已通过：Redis 异常默认不执行写 action；显式幂等任务才可降级；抢锁失败跳过；释放失败不覆盖业务结果；业务异常仍释放锁；非法租期拒绝。
- 真实环境未通过：尚未使用真实 Redis 做双实例同窗触发、断连、租期过期、进程崩溃接管、主从切换和长任务跨租期回放。
- 单实例无 Redis 的离线演示会被默认 fail-closed 拦截；如未来提供单实例直执开关，必须是显式配置且不能默认开启。

**指标**

- 同一调度窗口重复业务写入 = 0（待真实双实例验证）。
- Redis 故障期间非幂等写任务执行数 = 0（代码级语义已验证，待故障注入验证）。
- 降级、抢锁失败、跳过和释放失败事件有指标可追溯。

**需要真实数据**

不需要真实业务数据；需要独立 Redis 和至少两个应用实例的集成环境。

### P0-03 RDT 离线底座已实现，真实受限订单权限仍未联调

**当前状态**

- `TokensClient` 已按官方 `POST /tokens/2021-03-01/restrictedDataToken` 请求 RDT，并解析 `restrictedDataToken` / `expiresIn`。
- `RestrictedDataTokenManager` 按 `shopId + marketplaceId + canonical resource set` 缓存、刷新和精确失效，不持久化 token。
- `SpApiGateway` 已支持显式 `LWA` / `RDT` token source；受限调用失败时业务请求数为 0，不允许回退普通 LWA。
- `OrdersClient` 已提供受限订单与订单项方法；`OrderSyncScheduler` 的 PII 开关默认关闭，只有 RDT 与 PII 两个开关同时开启时才走受限路径。
- Outbox 已增加 token source 与受限资源摘要/密文，V5 迁移不保存 RDT 本体；重放前重新获取 token。
- `ConnectorRegistry` 当前为 26 条已实现 / 5 条未实现 / 31 条总数；RDT 不再列为未实现。

**已证明（离线 E2/E3）**

- 官方 Tokens 快照、byte count、sha256 和 1 req/s / burst 10 限流契约。
- 资源数量 1–50、method/path/dataElements 校验；缓存隔离与过期刷新。
- 401/403 只失效正确 token source；RDT 失败无 LWA 回退。
- Outbox 重放恢复 token source 且不保存 RDT；日志、异常、指标和响应不包含 token。
- 订单 PII 开关关闭时保持普通 LWA 行为，开启时才走受限调用。

**未证明（真实 E4/E5）**

- Amazon 应用审批、卖家授权、角色、marketplace、region、RDT/SPDS 权限。
- 沙箱/生产 Tokens/Orders PII 请求、真实 401/403/404/429/5xx、分页和字段契约。
- PII 加密、掩码、访问审计、保留/删除、备份恢复后的删除义务。
- 因此 A5 仍为 E0，不能写“已接通”或“可直接生产部署”。

**默认配置**

```yaml
spapi:
  restricted-data:
    enabled: ${SPAPI_RESTRICTED_DATA_ENABLED:false}
    refresh-skew-seconds: ${SPAPI_RESTRICTED_DATA_REFRESH_SKEW_SECONDS:60}
    max-cache-entries: ${SPAPI_RESTRICTED_DATA_MAX_CACHE_ENTRIES:1000}
  orders:
    pii-sync-enabled: ${SPAPI_ORDER_PII_SYNC_ENABLED:false}
```

**下一步**

1. 凭证和授权到位后先关闭两个开关部署，确认普通 SP-API 无回归。
2. 沙箱开启 RDT，只执行只读 Tokens/Orders 调用并保存脱敏证据。
3. 再开启订单 PII 同步，验证资源匹配、权限错误、限流、重试和 Outbox 重放。
4. 完成 PII 生命周期与生产观测后，才评估 E4/E5。

### P0-04 能力台账与源码曾发生漂移（代码级已收敛）

**证据**

- 修复前 `ConnectorRegistry.spapiOperations()` 只登记 13 条已实现 operation；扫描 `src/main/java/com/amz/client` 的实际出站调用点得到 25 条，共漏登记 12 条。
- 漏登记项为 Messaging 11 条：`messaging.getMessagingActionsForOrder`、`messaging.GetAttributes`、`messaging.confirmCustomizationDetails`、`messaging.createConfirmDeliveryDetails`、`messaging.createLegalDisclosure`、`messaging.createConfirmOrderDetails`、`messaging.createConfirmServiceDetails`、`messaging.CreateWarranty`、`messaging.createDigitalAccessKey`、`messaging.createUnexpectedProblem`、`messaging.sendInvoice`；Uploads 1 条：`uploads.createUploadDestinationForResource`。
- 现已把上述 12 条按官方 OpenAPI 快照补入能力台账；第 78 轮时为 **25 条已实现 / 6 条未实现 / 31 条总数**，第 79 轮新增 Tokens API 后当前为 **26 条已实现 / 5 条未实现 / 31 条总数**。`ConnectorRegistryTest.registryMatchesClientCallSitesBidirectionally()` 会扫描 `com/amz/client` 的 operationId 字面量，并断言其集合与 `IMPLEMENTED` 集合完全相等。
- Messaging 发送动作的 operationId 已补齐 `messaging.` 命名空间，能够命中 `SpiRateLimiter` 的官方 usage plan；`messaging.sendInvoice` 官方模型没有 usage plan，继续使用保守兜底，不伪造官方速率。
- 组合定向测试 `SpApiPathContractTest,ConnectorRegistryTest,AmazonMessagingRealClientContractTest` 共 26 条，0 failure / 0 error / 0 skipped。SP-API 全模块回归为 362 / 0F / 0E / 2S；全仓 19/19 模块 `BUILD SUCCESS`，Surefire 161 份 / 1055 / 0F / 0E / 2S。2 个 skipped 是需要真实凭证的 `SpApiIntegrationTest`，不能算通过。

**业务影响**

- 漂移期间，前端、验收和运维会低估真实存在的出站能力，并可能把未纳入台账的 Messaging/Uploads 客户端误当成已经验收。
- 收敛后，前端和运维看到的 operation 数量与 client 包真实调用点一致；但这只证明“代码级已登记”，不证明 Amazon 已接受请求，也不提升 A5 的真实联调证据。
- 能力目录目前仍只覆盖 `operationId + 官方路径 + 客户端调用说明 + 状态`。控制器、限流键、RDT 需求、证据等级和真实联调状态尚未全部结构化，不能把本次修复表述为完整生产验收。

**修复方向**

- 已完成：补齐 12 条漏登记 operation，增加双向集合测试，修正 Messaging operationId 命名空间。
- 后续：继续扩展单一能力目录，至少补充控制器、限流键、是否需要 RDT、证据等级和真实联调状态；新增或删除 client 调用点时必须同步修改台账并通过双向测试。
- 证据边界：A5 仍为 E0，`apiReady=false`、`reachable=false`，对外只能写“具备对接能力（未联调）”。

**验收**

- `ConnectorRegistryTest` 中 `IMPLEMENTED` 集合与 `com/amz/client` operationId 集合完全相等，数量为 25。
- 未实现能力为 6 条，且官方关键词在 `src/main/java` 中命中 0。
- 所有新增 Messaging/Uploads 路径通过官方 OpenAPI 快照契约测试。
- 不因台账补齐而抬高真实联调证据等级。

### P0-05 启动时全量加载店铺凭证

**证据**

- `amz-service/amz-service-spapi/src/main/java/com/amz/credential/ShopCredentialStore.java:69` 使用 `shopCredentialMapper.selectList(null)`。
- 该类已有按 `shopId` 查询 DB 并按需回填缓存的路径，因此启动时全量预加载不是唯一实现方式。

**业务影响**

- 店铺数量增长后，启动时间、JVM 内存和数据库扫描量线性增长。
- 大量密文在进程内长期驻留，扩大敏感数据暴露面。
- 单店凭证损坏或字段异常可能影响整个服务启动。

**修复方向**

- 改为按需加载 + 有界缓存 + TTL/失效策略。
- 启动时只预热显式配置的店铺或前 N 个店铺，N 必须有上限和容量测试。
- 保留生产 profile 的 fail-closed 语义：数据库故障时不能伪装成“无凭证”。
- 评估凭证轮换、吊销、版本号和最后更新时间，避免旧 token 长期复用。

**验收**

- 10,000 店铺模拟数据下，启动不执行全表加载。
- 单店查询在缓存未命中时能回源并回填。
- 数据库故障、密文损坏、店铺不存在、缓存淘汰均有明确行为。

## 3. P1 性能、分页和一致性风险

### P1-01 硬编码截断伪装成完整结果

**证据**

- `amz-service/amz-service-finance/src/main/java/com/amz/service/impl/FinanceServiceImpl.java:276`：`LIMIT 500`。
- `amz-service/amz-service-procurement/src/main/java/com/amz/service/impl/ProcurementServiceImpl.java:149`：`LIMIT 500`。
- `amz-service/amz-service-customer/src/main/java/com/amz/service/impl/CustomerEmailServiceImpl.java:165`：`LIMIT 1000`。
- `amz-service/amz-service-product/src/main/java/com/amz/service/impl/ProductServiceImpl.java:165,171`：`LIMIT 20`。
- `amz-service/amz-service-report/src/main/java/com/amz/service/impl/RealtimeProfitServiceImpl.java:361`：`LIMIT 10`。

**业务影响**

- 接口返回成功，但没有 `hasMore`、游标或截断标志。
- 财务对账、采购审批、客服邮件和利润快照可能漏单。
- 调用方无法区分“没有更多数据”和“被硬编码截断”。

**修复方向**

- 统一分页协议：`pageSize`、`cursor`、`hasMore`、`nextCursor`、`truncated`。
- 对导出、对账、审批等批量场景改用异步任务和分页下载。
- 保留小接口的默认上限，但必须在响应中显式说明。
- 增加“总数/截断”契约测试和边界测试。

**验收**

- 数据量超过限制时，接口不能返回看起来完整的成功结果。
- 所有截断路径都有 `truncated=true` 或明确错误。
- 分页翻页无重复、无遗漏、顺序稳定。

### P1-02 无界列表查询

**证据**

- 多个 `selectList(wrapper)` 路径没有显式上限，覆盖库存、出库单、调拨单、广告、客户邮件、AI 记忆等模块。
- 典型文件包括：
  - `WarehouseServiceImpl.listInventory`
  - `OutboundServiceImpl.listOutboundOrders`
  - `MultiWarehouseServiceImpl.listStock`
  - `LogisticsUpgradeServiceImpl` 多处 `selectList(wrapper)`
  - 广告和客服模块的多个列表接口

**业务影响**

- 大店铺一次加载全部数据，造成内存峰值、慢 SQL、网络传输放大和前端卡顿。
- 多租户环境下容易把单店大查询扩散为整个服务不可用。

**修复方向**

- 所有对外列表接口必须有分页上限和最大页大小。
- 大字段、明细字段按需投影，不在列表接口返回全字段。
- 为 `shop_id + status + create_time`、`shop_id + sku + status` 等高频条件建立组合索引。
- 导出、报表、批量对账异步化。

**验收**

- 单次请求返回行数不超过配置上限。
- 在 10 倍目标数据量下，列表接口延迟和内存占用满足预算。
- 慢查询日志中不出现无界全表扫描。

### P1-03 Upsert 并发竞态

**证据**

- `MultiWarehouseServiceImpl.saveStock`：先 `selectOne`，再 `updateById` 或 `insert`。
- `ListingMonitorServiceImpl.saveHealthCheck/checkListing`：同样存在先查后写。
- `MemoryServiceImpl.getOrCreatePreference`：先查后插。
- 相关表虽然有唯一键，但方法没有原子 upsert 或重复键恢复逻辑。

**业务影响**

- 并发首次写入可能触发唯一键冲突或覆盖更新。
- 多实例部署、重试和用户重复点击会放大问题。

**修复方向**

- 使用 `INSERT ... ON DUPLICATE KEY UPDATE` 或等价原子 upsert。
- 捕获重复键后重读并执行明确的合并策略。
- 对需要版本控制的记录增加 `version` 或 `updated_at` 条件更新。

**验收**

- 并发首次写入只产生一条记录。
- 重复请求不会覆盖更新更晚的数据。
- 唯一键冲突有明确重试或业务错误，不返回半成功。

### P1-04 循环内数据库操作

**证据**

- `SearchTermServiceImpl` 存在循环内逐条 upsert。
- `CustomerServiceImpl` 存在循环内逐条 insert。
- 部分财务、采购流程存在循环内 DB 调用。

**业务影响**

- 大输入量下形成 N+1 或批量放大。
- 事务时间变长，锁竞争和超时概率上升。

**修复方向**

- 先集合查询，再批量 upsert/saveBatch。
- 设置批大小上限和分片事务。
- 对外部 API 调用和数据库写入分开，避免长事务跨网络调用。

**验收**

- 1000 行输入时数据库调用次数与批次数成正比，而不是与行数线性增长。
- 事务时间、锁等待和失败回滚满足预算。

### P1-05 线程池缺少过载和指标

**证据**

- `amz-service/amz-service-ai/src/main/java/com/amz/agent/AgentChatStreamService.java:44`：固定 4 线程。
- `amz-service/amz-service-order/src/main/java/com/amz/service/impl/OrderAuditServiceImpl.java:182`：固定 2 线程。
- `amz-service/amz-service-product/src/main/java/com/amz/config/AsyncConfig.java:25-29`：核心 2、最大 5、队列 100、`CallerRunsPolicy`。

**业务影响**

- 队列无界或策略不匹配时，可能积压、超时或在调用线程执行导致请求线程被拖慢。
- 缺少活跃线程、队列长度、拒绝次数和任务耗时指标，无法定位过载。

**修复方向**

- 明确队列容量、拒绝策略、超时和任务隔离。
- 暴露线程池指标；对外部 API 任务和 CPU 密集任务使用不同池。
- 压测峰值并定义过载降级行为。

### P1-06 金额类型与搜索展示字段

**证据**

- 财务、广告、利润核心金额大量使用 `BigDecimal`，这是正确方向。
- `amz-service/amz-service-order/src/main/java/com/amz/model/pojo/Product.java:24` 的 `price` 为 `Double`。
- `amz-service/amz-service-search/src/main/java/com/amz/model/pojo/ProductDoc.java:43-44` 使用 `FieldType.Double` / `Double price`。
- `amz-service/amz-service-product/src/main/java/com/amz/model/pojo/Product.java:59-60` 已使用 `BigDecimal`。

**判断**

- 如果 `order.Product` 和搜索文档只用于检索、排序或展示，`Double` 是可接受的折中。
- 如果它们参与结算、利润、税费或对账，必须改为 `BigDecimal` 或明确转换为分/最小货币单位。
- 需要代码路径确认，不能仅凭字段类型下结论。

### P1-07 外部平台客户端尚未统一治理

**证据与风险**

- 仓库存在 Keepa、17TRACK、1688、金蝶、Temu、TikTok、Shein 等客户端。
- 它们不一定共享 SP-API 的 Outbox、限流、验签、错误分类、重试和脱敏规则。
- 多平台 webhook 主要是日志记录，尚未发现统一验签和幂等消费。
- RabbitMQ DLQ 只覆盖部分订单/财务流程。

**修复方向**

- 抽出统一的外部连接器契约：认证、超时、重试、限流、幂等、Outbox、错误分类、脱敏日志、健康检查和能力台账。
- 每个平台明确“可读/可写/需要人工确认”的边界。
- 所有 webhook 必须验签、去重、可重放、可审计。

### P1-08 占位数据和伪能力

**证据**

- `AdController.java` 的竞品价格监控存在占位数据路径。
- `ErpToolExecutor.java` 在无比价数据时使用确定性估算。
- `RerankService` 是词法实现，不是语义 rerank。

**风险**

- 占位数据可能被误读为真实市场数据。
- 估算值可能进入定价、补货或利润决策。

**修复方向**

- 所有占位和估算结果必须带 `source=mock/estimated`、`confidence`、`asOf` 和 `notForDecision` 标志。
- 生产 profile 禁止静默使用占位数据；无数据时返回明确不可用状态。

## 4. API-Ready 验收矩阵

这里的“API-Ready”定义为：**代码结构、配置、契约、错误处理、测试和运维入口已经准备好；拿到合规凭证后可以开始联调，不需要改业务代码。** 它不是“已经联调成功”。

| 能力 | 离线必须完成 | 真实凭证必须验证 | 当前判断 |
|---|---|---|---|
| 凭证存储 | 加密、按需加载、失败关闭、轮换接口 | 导入真实凭证、权限和区域正确 | 基础已有，需优化全量加载与轮换 |
| LWA 认证 | token 缓存、过期刷新、错误分类、脱敏 | 真实 client id/secret/refresh token 换取 token | 离线基础已有；未联调 |
| marketplace/region | 单一事实源、未知值失败关闭 | 真实 marketplace 授权和端点 | 已有 fail-closed 设计；未联调 |
| RDT | 资源校验、缓存隔离、无 LWA 回退、Outbox 重放 | 沙箱/生产受限 Orders 请求 | 离线 API-Ready 已实现；默认关闭，未联调 |
| 限流 | 官方 usage plan、按店铺隔离、429 观测 | 真实 429 和 `x-amzn-RateLimit-Limit` | 离线契约已有；未联调 |
| 重试/退避 | 429/5xx 有界重试、幂等保护 | 真实瞬时错误和长尾延迟 | 部分已有；需统一到所有连接器 |
| 分页 | cursor/nextToken、hasMore、无遗漏 | 大数据量翻页 | 部分已有；需统一 |
| 错误分类 | 400/401/403/404/429/5xx/网络分类 | 真实权限错误和限流错误 | 部分已有；需全链路 |
| 幂等/Outbox | 写请求持久化、重放、DLQ、无 token 落库 | 真实重复请求和重放 | SP-API 部分已有；跨平台未统一 |
| 契约快照 | 官方 OpenAPI 字节数、sha256、路径扫描 | 上游变更审查 | 已有 10 份 SP-API 快照 |
| 可观测性 | 指标、脱敏日志、审计、告警 | 真实延迟、错误、配额和权限 | 部分已有；需统一 |
| 能力台账 | 源码与目录双向一致 | 真实联调证据 | 已有双向测试；新增 API 仍需同步登记 |
| 部署配置 | 环境变量、密钥、健康检查、回滚 | 生产部署验证 | 已有基础；仍需逐项验收 |
| 多租户 | shopId 贯穿 SQL、缓存、消息、搜索、文件、导出、AI 工具 | 跨店越权测试 | 已有部分守卫；需要系统审计 |
| PII 生命周期 | 最小化、保留、删除、审计、导出限制 | 法务和合规确认 | 尚未闭环 |

### 4.1 “有 API 后可以直接使用”的最小验收清单

必须全部满足，才可以对用户说“代码层面可以直接联调”：

- [ ] 凭证可以通过受控接口或部署密钥导入，不需要改代码。
- [ ] 生产 profile 不会自动启用 mock，也不会静默返回空数据。
- [ ] 每个已实现 operation 都有官方路径、方法、限流、错误分类和契约快照。
- [ ] RDT 受限调用有显式 token source，失败不回退普通 LWA。
- [ ] 所有写请求经过 Outbox、幂等键和重放策略。
- [ ] 所有列表接口有分页、上限和截断标志。
- [ ] 429、5xx、401、403、超时和网络错误有统一分类和可观测指标。
- [ ] token、client secret、refresh token、RDT、PII 不进入日志和指标标签。
- [ ] 能力台账与源码双向一致，且 `reachable=false` 时前端不显示“已接通”。
- [ ] 有真实凭证后，可以按 runbook 完成沙箱只读联调，不需要修改业务代码。

## 5. 分阶段升级路线

### Wave 0：冻结基线与证据

目标：确认当前改动边界，避免在脏工作区上误覆盖。

- 保留现有暂存的 Messaging 删除文件，不执行 `git add .`、不清理暂存区。
- 记录当前 `git status`、测试报告、合成数据快照和契约快照。
- 将本审计、执行矩阵和 RDT 计划作为后续变更门禁。

退出标准：所有后续改动都能追溯到明确的审计项或计划任务。

### Wave 1：P0 正确性修复

目标：先修数据正确性和任务重复执行风险。

- FIFO 原子扣减与并发测试。
- DistributedJobLock 默认 fail-closed；仅显式证明幂等/只读的任务可按任务分级降级，真实 Redis 双实例回放待完成。
- 全量凭证加载改为按需加载和有界缓存。
- 统一分页和截断协议，先覆盖财务、采购、客服、库存和利润接口。
- 对高并发 upsert 路径改为原子 upsert。

退出标准：P0 并发测试通过；没有负库存、重复写任务和静默截断。

### Wave 2：API-Ready 能力补齐

目标：把“有 API 后可直接联调”从口号变成契约。

- 实施 RDT 计划。
- 将 Messaging、Uploads、Sellers 等客户端统一纳入能力目录和证据等级。
- 增加 Notifications、Listings Items、Product Pricing、Catalog Items、FBA Inbound 的官方契约快照和离线客户端骨架；在真实联调前保持未实现或未联调状态。
- 统一连接器接口：认证、限流、重试、分页、错误分类、Outbox、脱敏和健康检查。

退出标准：每个能力都有实现/未实现状态、官方证据、离线测试和联调待办。

### Wave 3：生产加固

目标：把离线正确性提升为可运维性。

- 指标、日志、追踪、告警、DLQ、重放和回滚演练。
- 多租户贯穿 SQL、缓存、搜索、消息、文件、导出和 AI 工具。
- 大店铺容量测试、慢查询治理、索引覆盖和异步导出。
- 凭证轮换、吊销、审计和最小权限。
- PII 生命周期：最小化、保留、删除、导出限制和审计。

退出标准：故障演练、容量测试、越权测试和回滚演练全部有证据。

### Wave 4：真实联调与生产发布

目标：在真实凭证下完成 E4/E5。

- 单店、只读、非 PII 优先。
- 再开启受限数据，先只读 Orders PII。
- 观察 429、401/403、token 过期、重放、限流和配额。
- 通过后再接入写操作、批量任务和自动化决策。
- 每个 operation 单独记录请求 ID、响应、错误、延迟、限流和脱敏证据。

退出标准：E4/E5 证据完整，生产监控和回滚可用。

## 6. 成本、变量和偏差提醒

- **Amazon 审批不是代码问题**：应用注册、角色、卖家授权、DPP/SPDS、PII 用途和 marketplace 授权都可能阻塞联调。
- **RDT 不等于权限**：能申请 RDT 不代表 Amazon 已授予对应数据权限。
- **API 配额是共享资源**：多实例、重试、分页、定时任务和人工操作会共同消耗配额。
- **沙箱覆盖有限**：沙箱通过不等于生产通过，尤其是权限、限流、真实订单和财务数据。
- **合成数据不能证明业务正确性**：只能证明 schema、引用和确定性；不能证明真实费用、税费、汇率、退款和结算逻辑。
- **开源实现只能作为参考**：第三方客户端可以帮助对照分页、限流和错误分类，但不能替代 Amazon 官方 OpenAPI 和实际联调。
- **迁移成本被低估**：把 `Double`、无界查询、全量凭证、硬编码截断以及仍待迁移的 fail-open 锁改掉，会触及多个模块和回归测试；`DistributedJobLock` 已完成代码级迁移，但不代表其他同类组件已排查完毕。
- **脏工作区风险**：当前有大量未提交改动和暂存删除；后续实施必须逐文件确认，不能整体提交。

## 7. 参考来源

官方与开源参考：

- [Amazon SP-API models](https://github.com/amzn/selling-partner-api-models)：官方 OpenAPI 模型，仓库已锁定 10 份快照并校验 sha256。
- [Amazon SP-API documentation](https://github.com/amzn/selling-partner-api-docs)：官方文档源。
- [Amazon SP-API samples](https://github.com/amzn/selling-partner-api-samples)：官方示例。
- [python-amazon-sp-api](https://github.com/saleweaver/python-amazon-sp-api)：第三方客户端，可用于对照认证、限流、分页和错误处理模式，不作为 Amazon 官方规范。
- [Connecting to the SP-API](https://developer-docs.amazon.com/sp-api/docs/connecting-to-the-selling-partner-api)
- [Authorization with Restricted Data Token](https://developer-docs.amazon.com/sp-api/docs/authorization-with-restricted-data-token)

仓库内证据：

- `amz-service/amz-service-spapi/src/test/resources/contracts/README.md`
- `docs/superpowers/runbooks/connector-acceptance-runbook.md`
- `docs/superpowers/plans/2026-09-24-connector-api-ready-phase0.md`
- `docs/superpowers/plans/2026-09-26-spapi-rdt-api-ready.md`

## 8. 下一步建议

按风险优先级，建议先执行 Wave 1，而不是继续增加客户端数量：

1. FIFO 原子扣减和并发测试。
2. DistributedJobLock 写任务 fail-closed（代码级已完成，待真实 Redis/双实例回放）。
3. 凭证按需加载。
4. 分页/截断协议统一。
5. 能力台账双向一致性。
6. 再批准并实施 RDT 离线部分。

在真实凭证到位前，不能把任何 operation 从“具备对接能力（未联调）”提升为“已接通”。真实凭证到位后，按 connector acceptance runbook 逐项取证，只有 E4/E5 证据齐全才允许改变对外状态。
