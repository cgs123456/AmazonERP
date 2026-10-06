# Amazon ERP — 微服务跨境电商管理平台

基于 Spring Cloud 微服务架构的亚马逊卖家全链路 ERP 系统，集成 SP-API 实现订单、库存、广告、采购、客服、物流、财务业务闭环，内置 AI 运营 Agent（29 工具）与可观测性三栈。

> ⚠️ **当前状态（2026-09-26 复核）**：本仓库**仍不能按现状视为可直接生产部署**。审计基线最初列出 **32 条 P0**，后续轮次继续追加到 **P0-58**；其中部分已修复、部分仅登记，**未重新逐项复核前不把编号总数当作剩余开放数**。P0-57 的默认 mock/部署 profile 问题已修复；第 74 轮新增 `ProductionProfileGuard`：默认 `prod`、离线演示必须显式 `mock`，`prod,mock` 混用会拒绝启动；第 75 轮新增 `DataSourceValidator`：`prod` 下已配置为空的密码，以及 `CHANGE_ME_*`、`your_*` 等占位密码，都会拒绝启动。但 SP-API 凭证、权限、端点和字段契约仍需真实沙箱/生产联调。
> **“有 API 凭据”不等于“即插即用”**：当前主链路的工程证据最高仍是 **E2/E3**（进程内桩 + 官方 OpenAPI 快照），不是 **E4 沙箱联调**或 **E5 生产联调**。真实接通还需要 LWA client id/secret + refresh token、SP-API 应用授权/订阅/角色权限、marketplaceId/region、官方端点版本与 usage plan 对账、字段契约验证，以及沙箱/生产联调。事实源见 [`docs/superpowers/specs/2026-09-24-amazon-erp-production-design.md`](docs/superpowers/specs/2026-09-24-amazon-erp-production-design.md)，API-Ready 实施计划见 [`docs/superpowers/plans/2026-09-24-connector-api-ready-phase0.md`](docs/superpowers/plans/2026-09-24-connector-api-ready-phase0.md)。
> 上文「业务闭环」指**模块与代码路径已具备**，不代表外部平台已完成真实对接或沙箱联调；当前复核仍未发现主代码 `*RealClient` 返回硬编码 `MOCK_*` 假成功，但 1688 签名/token、金蝶多币种字段、SP-API 权限与字段契约、多平台签名等仍未取得 E4/E5 联调证据，`ConnectorRegistry` 当前为 90 条已实现 / 0 条未实现：26 条来自既有类型化客户端，64 条来自统一 `SpApiOperationCatalog` + `SpApiOperationClient`，官方快照 15 份、Usage Plan 106 条；已实现清单与调用点双向一致。事实源见 [`docs/superpowers/specs/2026-09-24-amazon-erp-production-design.md`](docs/superpowers/specs/2026-09-24-amazon-erp-production-design.md)，API-Ready 实施计划见 [`docs/superpowers/plans/2026-09-24-connector-api-ready-phase0.md`](docs/superpowers/plans/2026-09-24-connector-api-ready-phase0.md)。

> **第 76 轮（2026-09-26）新增连接器状态中心**：前端 `/connectors` 已消费 `GET /api/connectors`、`POST /api/connectors/{code}/self-test` 与分层自检接口，集中展示 profile、真实/模拟客户端、凭证来源与数量、已实现/未实现操作、A1–A8 证据等级及最近自检结果。页面不会因“有凭证”或模拟自检成功显示“已接通”；只有后端 `reachable=true` 且证据达标才允许展示已接通。当前仍没有 E4/E5 真实联调证据，因此这是 API 对接能力的可视化运维入口，不是“拿到凭证即可跳过授权、权限、字段契约和联调验证直接生产”。
> **第 77 轮（2026-09-26）补齐 Sellers 只读能力**：新增 `SellersClient` 与 `GET /spapi/sellers/marketplace-participations`，接入统一 LWA/SigV4、官方限流、Outbox、RBAC 与店铺隔离；官方快照锁定 `GET /sellers/v1/marketplaceParticipations`，空 `payload` 合法，响应字段异常显式失败。该轮登记时 `ConnectorRegistry` 为 **13 条已实现 / 6 条未实现 / 19 条总数**，随后第 78 轮校准为 **25 / 6 / 31**。该能力已具备对接能力（未联调）：拿到真实凭证后可直接开始授权、marketplace、限流与字段联调，但在 E4/E5 证据完成前不能写“已接通”或“生产可用”。
> **第 78 轮（2026-09-26）校准连接器能力台账**：补齐 Messaging 11 条与 Uploads 1 条漏登记 operation，并新增 `ConnectorRegistryTest.registryMatchesClientCallSitesBidirectionally()` 双向校验；Messaging 发送动作补齐 `messaging.` 命名空间以命中官方限流表。组合定向测试 **26 / 0F / 0E / 0S**；SP-API 全模块 **362 / 0F / 0E / 2S**；全仓 19/19 模块 `BUILD SUCCESS`，Surefire **161 份 / 1055 / 0F / 0E / 2S**（2 skipped 为需要真实凭证的 `SpApiIntegrationTest`）。该轮结束时台账为 **25 条已实现 / 6 条未实现 / 31 条总数**。这仍是代码级收敛，不提升 A5 或 E4/E5 证据。

> **第 79 轮（2026-09-26）补齐 RDT API-Ready 底座**：新增 Tokens API 官方快照与限流契约，`SpApiGateway` 支持显式 LWA/RDT token source，RDT 内存缓存按店铺、marketplace、资源集合隔离，Outbox 重放恢复 token source 且不持久化 RDT；`ConnectorRegistry` 在第 79 轮当时为 **26 条已实现 / 5 条未实现 / 31 条总数**。RDT 默认关闭，订单 PII 端到端接线、真实授权、沙箱与生产联调仍未完成；对外仍只能写“具备对接能力（未联调）”。
> **第 79 轮（2026-09-26）修复 FIFO 并发超卖（代码级，待真实 MySQL 回放）**：`InventoryBatchMapper` 新增带 `shop_id`、`sku`、`status = 'ACTIVE'`、`available_quantity >= qty` 守卫的条件扣减；`fifoOutbound` 不再使用 `updateById`，受影响行数为 0 时整体抛错并由事务回滚。SQL 先将扣至 0 的批次置为 `DEPLETED` 再相对扣减，避免 MySQL 从左到右赋值造成重复扣减。新增 SQL 契约测试和 100 轮双线程内存原子回放：旧实现实测 2/2 成功，新实现每轮恰好 1/2 成功；amz-common 110 + procurement 68，**0F / 0E / 0S**。真实 MySQL 多连接并发、锁等待和事务回滚尚未执行，因此只能标记代码级修复，不能标记生产验证。
> **第 80 轮（2026-09-26）修复调度锁 fail-open（代码级，待真实 Redis 双实例回放）**：`DistributedJobLock` 默认改为 fail-closed，RedisTemplate 缺失、Redis 异常或抢锁不确定时不再执行非幂等写任务；新增显式 `runIdempotentWithLock(...)`，只允许已证明幂等/只读任务在锁基础设施不可用时降级。现有 14 个生产调用点全部保持默认 fail-closed，并新增 `amz.scheduler.lock.acquire.failed`、`degraded`、`skipped`、`release.failed` 指标。锁单测 **9 / 0F / 0E / 0S**；全仓 19/19 模块 `BUILD SUCCESS`，Surefire 新鲜 XML **164 份 / 1068 / 0F / 0E / 2S**。真实 Redis、双实例同窗触发、断连、租约过期、崩溃接管和长任务跨租期仍未回放，因此只能标记代码级修复。
> **第 81 轮（2026-09-26）完成 SP-API 剩余能力离线 API-Ready 收口**：新增 Notifications 10、Listings Items 5、Product Pricing 2、Catalog Items 2、FBA Inbound 45，共 64 条官方 operation，进入统一 `GET /spapi/operations` 与 `POST /spapi/operations/{operationId}`；结合既有 26 条类型化能力，`ConnectorRegistry` 达到 **90 条已实现 / 0 条未实现**。官方快照 **15 份**、Usage Plan **106 条**；SP-API 模块 **428 / 0F / 0E / 2S**；全仓 19/19 模块 `BUILD SUCCESS`，新鲜 Surefire **175 份 / 1134 / 0F / 0E / 2S**；前端 19 文件 / 144 tests / 0F，生产构建成功。`mock` profile 可执行统一目录，真实凭证下无需改业务层即可切换真实执行器；但仍需 Amazon 应用审批、卖家授权、角色/RDT/SPDS 权限、SQS/SNS 消费链路、沙箱联调和生产验收，当前仍只能声明“具备对接能力（未联调）”。
> **第 87 轮（2026-09-27）连接器分层自检改为 POST 并补齐统一入口**：`POST /spapi/preflight/shop/{shopId}?forceTokenRefresh=...` 与 `POST /spapi/preflight?forceTokenRefresh=...&limit=...` 已锁定仅 OPERATOR/ADMIN、逐店铺严格授权、批量上限 50；网关新增 `/api/preflight/** -> /spapi/preflight/**` 别名，前端连接器中心展示 CREDENTIAL → LWA_TOKEN → READ_API 三段结果，SKIP 不会被当作 PASS。后端 SP-API 全量 **574 / 0F / 0E / 2S**，前端全量 **20 文件 / 163 / 0F**，生产构建成功。当前仍无真实凭证，`apiReady=false`、`reachable=false`，模拟自检或非官方端点全绿都不能提升到 E4/E5。

## 🏗 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Java | 17 | LTS |
| Spring Boot | 3.3.5 | 核心框架 |
| Spring Cloud | 2023.0.3 | 微服务治理 |
| Spring Cloud Alibaba | 2023.0.1.2 | Nacos 注册/配置中心 |
| MyBatis-Plus | 3.5.7 | ORM |
| MySQL | 8.0.33 | 读写分离（主从） |
| Redis | 7.0 | 缓存 + 分布式锁 |
| RabbitMQ | 3.12 | 消息队列（含 DLX） |
| Elasticsearch | 8.15.3 | BM25 + dense_vector RRF 混合检索 |
| MongoDB | 7.0 | 文档存储 |
| LangChain4j | 0.36.2 | AI Agent 编排 |
| ONNX Runtime | 1.18.2 | LightGBM 模型推理 |
| Vue 3 | 3.5.13 | 前端框架 |

## 📊 微服务架构（15 业务服务 + 网关 + 公共模块）

```
amz-gateway                — API 网关（JWT + Sentinel 限流 + shopId 校验）
# 核心业务（7 个）
amz-service-user            — 用户 + 多店铺 RBAC + JWT 双 Token
amz-service-product         — 商品/Listing + Keepa 竞品监控
amz-service-order           — 订单 + SP-API 同步 + 智能审单
amz-service-search          — ES 混合检索
amz-service-message         — WebSocket + Amazon Messaging
amz-service-ai              — AI 运营 Agent（29 工具）
amz-service-spapi           — SP-API 对接层（LWA + 条件签名；SigV4 非必需）
# 扩展业务（8 个）
amz-service-ad              — 广告管理（ACoS + 搜索词 + 自动规则 + 日报 02:00 同步/趋势聚合）
amz-service-procurement     — 采购供应链（供应商 + 1688 + FBA 货件）
amz-service-customer        — 客服（邮件 + 差评匹配 + RMA）
amz-service-logistics       — 物流（全子域看板 + 双入口取数 + 商比价 + 调拨 + 头程分摊 + 签收差异）
amz-service-ops             — 运营工具（差评/跟卖/关键词监控）
amz-service-report          — 数据报表（利润/周转/经营看板）
amz-service-finance         — 业财一体（复式记账 + 金蝶 + VAT）
amz-service-multiplatform   — 多平台（Shopify/eBay/Walmart/Shopee/Lazada）
# 公共模块
amz-common               —        — 公共（Result/UserContext/AOP/GlobalExceptionHandler/Flyway）
```

> 共 102 张表（Flyway 迁移重放后的活表集合，2026-10-05 死表清理后实测）、360+ REST 端点（方法注解实测）、AI Agent 29 工具

## 🤖 AI 运营 Agent（29 工具）

基于 LangChain4j AiServices 声明式编排，按用户意图自动调度工具链：

| 类型 | 工具 | 说明 |
|------|------|------|
| **基础查询（10）** | query_orders、query_inventory、query_sales、query_profit、suggest_replenish、check_inventory_health、query_purchase_orders、query_suppliers、query_advertising、query_knowledge_base | 覆盖订单/库存/销售/利润/补货/健康度/采购/供应商/广告/知识库 |
| **分析洞察（8）** | analyze_ad_performance、analyze_product_reviews、analyze_product_selection、analyze_listing_health、analyze_search_terms、analyze_sales_trend、analyze_inventory_aging、track_shipment | 覆盖广告/评论/选品/Listing/搜索词/销售趋势/库龄/物流 |
| **优化建议（8）** | optimize_ad_campaign、optimize_listing_seo、optimize_shipping_route、optimize_inventory_distribution、cross_marketplace_listing、monitor_competitor_price、estimate_fba_fees、translate_listing | 覆盖广告优化/Listing SEO/物流/库存调拨/跨站点/竞品/FBA 费用/翻译 |
| **操作执行（3）** | create_purchase_plan、auto_reply_message、generate_promotion_plan | 覆盖采购计划/消息回复/促销方案 |

> **店铺知识库 RAG**：SOP/政策文档上传解析（Tika）→ 分块 → 向量化 → ES（BM25 + dense_vector 混合检索 + RRF + 重排），`query_knowledge_base` 工具 + SOP 优先提示词 + 引用来源约束；配套 `/ai/knowledge/*` 接口与前端知识库管理页。
>
> **流式问答**：`GET /ai/chat-stream` SSE（fetch + ReadableStream，可透传 token/shopId 头）+ POST 兜底 + 工具调用时间线；身份只从 JWT 认证上下文读取，不接受 `userId` 查询参数覆盖。


## 📦 4 大 P0 核心模块

### P0-1：FBA 库存健康度监控
- DOS 阈值分级：STOCKOUT / URGENT(≤7d) / AT_RISK(7-14d) / HEALTHY(14-60d) / OVERSTOCK(>60d)
- 滑动窗口限流（30s/25req，SP-API 合规）

### P0-2：跨站点 Listing 复制
- DeepSeek LLM 翻译 → 三级缓存(SHA-256→MySQL→API) → 汇率换算 → 加价 20%
- Feeds API 异步提交 + 15s 轮询

### P0-3：财务利润核算
- MQ 异步 + 幂等：毛利=收入-采购-履约-佣金、净利=毛利-广告-VAT-仓储
- 小时级利润快照 + FIFO 成本法 + 费用智能分摊
- 金蝶对接 + VAT 8 国自动计算 + 月度结账
- **真实数据口径（详见「财务域」章节）**：收入与费用改由 SP-API 结算原表提供（平台实际扣费，非估算），
  成本取采购批次，再叠加索赔追回；成本缺失时利润显式标记为不完整而非按 0 计

### P0-4：智能补货引擎
- 多因子加权：7天日均×0.7 + 30天日均×0.3 × 安全系数(CV 自适应) × 季节性 × 促销
- CV>0.6：LightGBM(70%) + 规则(30%) ONNX 混合推理
- Cron 每天 06:00 全量 + 每 6h 增量

## 🚚 物流看板与双入口取数

**要解决的业务问题**：头程物流此前依赖人工——运营逐条点「同步」触发轨迹拉取，再自己比对 ETA 判断哪些货件出了状况。本模块把「人去查物流」改成「异常主动浮现」。

### 双入口取数（同一套落库核心）

| 入口 | 端点 | 适用场景 | 是否消耗第三方配额 |
|------|------|---------|:---:|
| **A 外部导入** | `POST /logistics/import/shipment`、`POST /logistics/import/tracking` | 承运商官网未开放 API；需人工核对后批量回填 | 否 |
| **B 第三方拉取** | 定时调度（默认 30 分钟）+ `POST /logistics/shipment/{id}/sync` | 接入聚合服务后无人值守更新 | 是 |

两条入口**共用落库核心** `TrackingIngestService`：状态映射、幂等去重、时间归一化、取数时间刷新只有一份实现。若各自落库会立刻出现两类问题——同一承运商文本在两条链路被解成不同状态；A 链路「先删后插」会把 B 链路刚写入的历史轨迹整段抹掉。因此采用**按 `(状态, 时间)` 指纹增量合并**，两条入口同时启用也不会互相覆盖。

### 四子域端点（比价 / 调拨 / 头程成本 / 签收差异）

| 子域 | 读 | 写 |
|------|----|----|
| 比价 | `GET /logistics/dashboard/quotes`、`GET /logistics/v2/quote/list/{shopId}`、`GET /logistics/v2/quote/compare/{shopId}` | `POST /logistics/v2/quote`、`POST /logistics/v2/quote/expire-outdated/{shopId}` |
| 调拨 | `GET /logistics/dashboard/transfers`、`GET /logistics/v2/transfer/list/{shopId}` | `POST /logistics/v2/transfer`、`/transfer/{id}/approve`、`/transfer/{id}/ship`、`/transfer/{id}/receive` |
| 头程成本 | `GET /logistics/dashboard/freight-cost`、`GET /logistics/v2/freight/{shipmentId}` | `POST /logistics/v2/freight/{shipmentId}/calculate` |
| 签收差异 | `GET /logistics/dashboard/receipts`、`GET /logistics/v2/discrepancy/list/{shopId}` | `POST /logistics/v2/discrepancy`、`/discrepancy/{id}/investigate`、`/discrepancy/{id}/resolve` |

写接口一律 `@RequireRole({OPERATOR, ADMIN})`，读接口只需 `@ShopScoped`（VIEWER 也可查询）。
`/xxx/{id}` 形态的端点没有 shopId 可供切面检查，归属校验落在服务层（见「安全与稳定性」）。

### 看板能力（`/logistics`，9 个标签页）

| 标签页 | 内容 |
|--------|------|
| **概览** | 在跟踪/延误/异常/7 天内到港四项 KPI；平均头程时效；三项「不作为就会被忽略」的指标——ETA 已过未标延误、取数过期、缺运单号；建单/送达趋势折线（手写 SVG，不引图表库）；状态分布与取数来源 |
| **待处理** | 6 类提醒按严重程度排序：延误 / 异常 / ETA 超期未标 / 临近到港 / 取数过期 / 缺运单号，每条附建议动作与快捷操作（同步、轨迹、关单） |
| **在途货件** | 按状态筛选的货件列表 + 轨迹时间线浮层（展示承运商原文与数据来源） |
| **承运商表现** | 各承运商的时效、延误率、异常率对比，支撑「换承运商/换线路」的决策 |
| **比价** | 有效报价 / 已失效未收口 / 30 天内失效 / 未填单价四项 KPI；航线覆盖表（按承运商数升序，标出单一来源航线）；内置运费比价计算器（按币种分组推荐）；可一键把过期报价标记为 EXPIRED |
| **调拨** | 待审批 / 已批待发 / 在途卡单三项 KPI；待跟进清单（区分「审批卡住」与「货卡在路上」）；调拨单列表可直接审批 / 驳回 / 发出 / 收货，操作入口随状态收敛 |
| **头程成本** | 头程成本合计、加权单件成本、分摊覆盖率；成本构成（运费/关税/保险/其他）占比；未核算成本货件清单（「有费用未摊」优先）；单件成本 Top |
| **签收差异** | 未结案少收件数（可直接索赔的差额）、少收/多收合计、差异率；少收最多的 ASIN；待处理差异可「开始核查 / 结案」 |
| **数据导入** | 粘贴 JSON 导入主单与轨迹，返回行级报告（含未匹配运单清单与错误行号） |

四个运营子域看板（比价/调拨/头程成本/签收差异）与货件看板拆成独立端点，且**按标签懒加载**：报价有效期与调拨卡单需要天天看，头程成本与签收差异通常周月对账时才看，进首页就把七八个接口全打一遍没有意义。

### 关键设计取舍

- **平均时效终点取送达事件时间**，而非货件行更新时间：后者是「系统某次同步的时刻」，用它算出的时效偏向同步频率而非实际运输快慢；无样本时返回 `null` 而非 `0`（`0` 会被读作「当天送达」）
- **取数时间独立于状态变更**：轨迹落库只在货件整体状态变化时才写货件行，因此新增一条 `last_track_time`；调度按它升序轮换，保证单轮限量时不饿死尾部货件
- **调度准入三规则**：只处理非终态、跳过 `IMPORT` 偏好货件（不与人维护的数据抢口径）、按最近取数时间轮换
- **状态机口径修正**：`ARRIVED`/`OUT_FOR_DELIVERY` 不再映射为「已送达」（到港≠送达，派送中可以失败），`DELIVERED` 不再直接跳到「FBA 已入库」（入库信息来自 FBA 入库渠道）；新增手工关单端点补齐 `CLOSED` 终点
- **无凭证安全降级**：第三方 API 默认关闭（`AMZ_TRACKING_ENABLED=false`），未配置凭证时不发起任何外部请求、调度整轮跳过，轨迹仍可通过入口 A 维护
- **报价「状态是 ACTIVE」不等于「报价有效」**：比价时额外按 `expiry_date` 过滤，看板单独统计「已失效但状态没收口」的条数——这段窗口里做出的选商结论是错的，不暴露就没人会发现
- **运费按重量价与体积价取高**：承运商的实际计费规则如此，只按重量算会得出低于账单的价格；重量与体积同时给出时取两者较高者
- **跨币种不给单一「最便宜」结论**：按币种分组分别推荐，全局推荐字段在多种币种并存时返回 `null`
- **分摊金额守恒**：逐行四舍五入后的尾差由基准量最大的一行吸收，并回传 `balanced` 标志供调用方核对「各行之和 = 总额」
- **调拨单状态只能单向推进**：草稿 → 待审批 → 已审批 → 运输中 → 已收货，跳过前置状态被服务端拒绝（否则会出现「没发货就收货」，库存账凭空多出来）；重复操作幂等
- **签收差异数量由服务端算**，不接受调用方自报：自报值可能与应收/实收两个数量互相矛盾，落库后看板统计就与原始数据对不上了
- **差异率按「绝对差异之和 / 应收合计」**：按净差异算会让少收与多收互相抵消（少收 100 件 + 多收 100 件 → 净差异 0），把量级很大的问题掩盖成「没有问题」

## 💰 财务域：结算取数 → 回款 → 索赔 → 真实利润

以**平台实际扣费**为唯一口径来源，打通「发现少给钱 → 把钱要回来 → 入账 → 算清真实利润」的闭环。
取数层在 `amz-service-spapi`（Reports / Finances / Fees），业务层在 `amz-service-finance`（经 Feign 调用）。

### 新增端点

| 域 | 端点 |
|----|------|
| 取数（spapi） | `POST /spapi/finance/report/request`、`GET /spapi/finance/report/{reportId}`、`GET /spapi/finance/document/{documentId}`、`GET /spapi/finance/events`、`POST /spapi/finance/fees/estimate`（全部 `@ShopScoped`） |
| 结算 | `POST /finance/settlement/sync`（+`@RequireRole`）、`GET /finance/settlement/list/{shopId}` |
| 回款台账 | `GET /finance/collection/list/{shopId}`、`GET /finance/collection/summary/{shopId}`、`POST /finance/collection/rebuild`（+`@RequireRole`） |
| 费用差异 | `POST /finance/discrepancy/scan`、`POST /finance/discrepancy/inbound-shortage`、`GET /finance/discrepancy/list/{shopId}`、`GET /finance/discrepancy/{id}`、`POST /finance/discrepancy/{id}/dismiss` |
| 索赔单 | `POST /finance/claim/from-discrepancy`、`POST /finance/claim/{id}/{submit\|accept\|reimburse\|reject}`、`GET /finance/claim/list/{shopId}`、`GET /finance/claim/summary/{shopId}`、`GET /finance/claim/reconcile/{shopId}` |
| 真实利润 | `GET /finance/profit/sku/{shopId}` |

### 新增数据表

`amz_settlement_detail`（结算原表明细，`row_key` 业务指纹唯一索引）、
`amz_payment_collection`（订单级回款台账）、`amz_fee_discrepancy`（费用差异 / 短收候选）、
`amz_reimbursement_claim`（索赔单）。表结构只由模块 Flyway 维护；`docker/init-sql/` 与 k8s 建库 Job 仅创建空库，不再复制表 DDL。

### 关键设计取舍

- **降级不返回空数据**：spapi 不可用时 Feign 降级返回 failure。财务域若静默返回空列表，上游会把「服务不可用」读成「本期没有结算」，利润 / 回款 / 索赔全部按 0 计算，且看起来像一个真实的业务结论
- **报表轮询有上限**：超过上限抛错并说明已尝试次数与最后状态，不静默返回空数据
- **幂等靠业务指纹**：结算表 `row_key` = `settlementId|orderId|sku|amountType|amount|depositDate` 的 MD5 唯一索引。结算报表按批次下发、同步窗口必然重叠，没有指纹就会重复入账
- **解析按表头名定位列**：结算原表列序会随报告类型与站点变化，按列号取值会在平台调整顺序时静默错位（数字仍是数字，含义却变了）
- **行级容错**：单行脏数据只记账不中断整批 —— 几千行的一批不能因一行全丢；错误带文件行号供人工核对
- **回款恒等式**：`实收 = 应收 - 平台费用 - 退款 + 赔付净额`。实收取「有符号金额总和」而非分项相减，避免漏掉某类交易时凭空产生差额
- **短款可为 null**：未完成费用比对时「少给了多少」这个数并不存在，用 0 代替会被读成「没有短款」这个结论
- **在途未回 ≠ 已回短款**：前者等待即可，后者需要索赔动作；合并成一个「差额」会同时抹掉两种处置路径
- **尺寸跳档优先于普通多收**：同一 SKU 判定为尺寸重测跳档后不再生成普通多收候选，否则同一笔钱会被索赔两次
- **索赔状态机白名单**：`CANDIDATE→SUBMITTED→ACCEPTED→REIMBURSED`（中途可 `REJECTED`），跳过前置一律拒绝 —— 否则「已赔付」能从前置任意状态一步跳到，统计出来的追回金额全是假的
- **赔付幂等**：重复赔付返回当前状态且不重复生成凭证（重复入账 = 虚增利润）
- **追回计入利润**：`calculateProfit` 新增 `REIMBURSEMENT` 分支（借 1122 应收 / 贷 6051 其他业务收入），此前该类型会被忽略导致追回的钱不进利润
- **双向核对分开列示**：平台 Adjustment 行（平台主动赔付，无需动作）与系统已赔付索赔单（缺失即账实不符，优先排查）分别列出，不合并成一个「差异笔数」
- **成本缺失不按 0 计**：单品利润拿不到采购成本时标记 `profitIsComplete=false`，绝不输出「利润率 80%」这种好看但错误的结论
- **头程运费不重复计**：采购批次成本里已含头程运费与关税，故不再叠加物流模块的头程分摊
- **成功率无样本返回 null**：0 个已结案样本算出 0% 会被读成「索赔全被驳回」

## 🎨 前端设计系统

- **Design Tokens**（`src/style.css` 全局）：单 accent indigo、语义色（成功/警告/错误仅用于状态）、圆角/字号/行高/阴影/等宽字体全 token 化，禁止硬编码
- **暗色模式**：`prefers-color-scheme` 整套 token 翻转（正文约 12:1、muted 约 5.5:1，WCAG AA）；`color-scheme: light dark` 照顾原生控件
- **加载与空态**：骨架屏（形状匹配最终布局，加载时独占内容区防跳动）+ 全局空态构图；`role="status"` 播报加载
- **无障碍**：全局 `:where` 焦点环（WCAG 2.4.7）、`prefers-reduced-motion` 降级
- **数据诚实**：聚合/趋势等后端无数据时用 mock 兜底并打“示例数据”标识

## 📊 可观测性三栈

| 组件 | 用途 | 配置 |
|------|------|------|
| **Skywalking** 10.1.0 | 分布式链路追踪 | Java Agent 9.3.0 自动注入 Dockerfile，traceId 注入日志 |
| **Prometheus** 2.54.0 | 指标采集 | 16 服务暴露 /actuator/prometheus，15s 间隔拉取 |
| **Grafana** 11.2.0 | 可视化面板 | 预置 JVM 面板（CPU/Heap/HTTP Rate/P95） |
| **ELK** 8.15.3 | 集中日志 | Logstash grok 解析 → ES 索引 amz-erp-YYYY.MM.dd → Kibana |
| **AlertManager** | 告警 | 6 条规则：服务宕机/高错误率/高响应/高堆内存/MQ 积压/磁盘 |

## 🔐 安全与稳定性

| 防护层 | 实现 |
|--------|------|
| **多店铺 RBAC** | `@ShopScoped` + `ShopIdGuardAspect`（200+ 方法）+ 网关 JWT+shopId 校验；切面覆盖不到的 `@RequestBody`/路径参数场景由各服务显式 `isShopAllowed` 校验 |
| **ID 型端点归属校验** | 以资源 ID 为入参的端点（物流货件同步/轨迹/关单，以及调拨审批/发货/收货、头程成本读取与分摊计算、签收差异核查/结案）在服务层按 `UserContext` 授权店铺校验，不改变签名以兼容内部 Feign 调用；提示语统一为「不存在或无权访问」避免借接口探测他店数据 |
| **请求体 shopId 二次校验** | 报价、调拨单、头程分摊明细、签收差异的 shopId 来自 `@RequestBody`，`@ShopScoped` 切面覆盖不到，写入前一律经 `UserContext.isShopAllowed` 校验，防止构造请求体往他店落数据 |
| **单据状态流转白名单** | 调拨单只允许 草稿 → 待审批 → 已审批 → 运输中 → 已收货 单向推进，跳过前置状态被拒绝（避免「没发货就收货」导致库存账凭空多出）；签收差异 待处理 → 核查中 → 已结案 同理；重复操作幂等，不产生二次写入 |
| **落库层租户过滤** | 轨迹落库按 `(货件编号, 运单号)` 匹配时强制附带 shopId，防止导入方填入他店运单号把轨迹写进他店货件（越权写入）；货件导入按编号 upsert 时校验归属店铺 |
| **接口级权限** | `@RequireRole` 注解 + AOP 切面（采购/运营等敏感端点 OPERATOR/ADMIN 校验） |
| **字段级权限** | `@FieldPermission` + 切面 + 前端 `***` 掩码 |
| **JWT 双 Token** | access_token(24h) + refresh_token(7d) + 前端 401 静默续期重试 |
| **登录安全** | 短信 60s 重发冷却＋每日 10 条上限＋验证码 5 次试错作废 |
| **OAuth 应用密钥** | SHA-256 存储校验＋轮换端点＋scope 取交集＋token 绑定归属店 |
| **写隔离加固** | 商品/多平台账号·消息·订单读写归属校验＋更新锁定 shopId 防跨店搬移 |
| **单号防碰撞** | `BizNoGenerator`（毫秒+3 位随机）统一 9 处 millis 单号 |
| **LWA Token 隔离** | 缓存键 `clientId:sha256(refreshToken)`，杜绝跨租户 token 串号 |
| **网关防伪造** | 全局过滤器剥离外部传入的 `userId`/`shopId` 请求头，身份仅取自 JWT |
| **CORS 配置化** | 白名单经 `amz.cors.allowed-origins` 环境变量注入，默认仅本地 |
| **网关限流** | Sentinel 1.8.6 + Nacos 规则数据源 + 基线 QPS 规则（user/ai/spapi） |
| **SP-API 客户端限流** | 滑动窗口 + `x-amzn-RateLimit-Limit` 动态收紧，401/403 自动驱逐缓存 token |
| **分布式事务** | Seata AT 2.0.0（条件启用 `SEATA_ENABLED=true`） |
| **TLS 可选** | SSL 配置块（默认关闭，需证书） |
| **MQ 死信队列** | save.order + login.notice + order.profit + finance.voucher → DLX/DLQ；消费幂等（Redis SETNX + 处理上限熔断）；订单同步后凭证改发 MQ（Seata 不再跨服务持锁） |
| **调度防重** | `DistributedJobLock` Redis 分布式锁默认 fail-closed；仅显式幂等/只读任务可选择降级，并记录抢锁、跳过、降级和释放失败指标 |
| **SQL 注入防护** | 全 MyBatis `#{}` |
| **全局异常处理器** | 统一 `@ControllerAdvice` 覆盖 16 服务 |
| **数据库迁移** | Flyway 10.20.0 是 14 个 MySQL 服务的唯一建表事实源（**102 张表**＝迁移重放（含 V-next DROP）后的活表集合，2026-10-05 实测；旧文的 113 已含 11 张死表，已清理；另有两个非迁移对象：`amz_order.v_profit_summary_by_sku` 是 V1 建的 VIEW，不计入表数，`amz_ops.amz_synthetic_dataset_registry` 由 `tools/synthetic-data/purge.py` 建，是唯一例外；baseline-on-migrate: false —— 存量库无 flyway_schema_history 时 fail-fast 拒绝启动，不自动打基线重放 V2..Vn）；Compose/k8s 只建 14 个空库 |
| **Docker 健康探针** | 16 服务 Actuator health/liveness/readiness |

## ⚠️ 已知限制

| 项 | 说明 |
|----|------|
| **Redis 单点** | 幂等（SETNX）、分布式锁、Sentinel 规则拉取、LWA token 缓存均依赖单实例 Redis，无 Sentinel/Cluster；生产建议部署哨兵或集群 |
| **MySQL 主从需手动建立复制** | docker-compose 从库仅预置只读 + GTID 参数，主从复制需按 `docker-compose.yml` 中注释手动执行 `CHANGE REPLICATION SOURCE` |
| **Swagger/OpenAPI 默认放行** | 网关默认放行 `/swagger-ui` 与 `/v3/api-docs`（本地/内网联调用）；生产环境设置 `GATEWAY_DOCS_ENABLED=false` 收紧 |
| **前端降级数据** | 前端各页在后端不可达时降级到内置样例数据（仅演示），生产环境建议关闭降级或展示明确的不可用态；趋势等聚合区用 mock 时标题旁有“示例数据”标识 |
| **广告趋势数据源** | `GET /ad/trend` 读 `amz_ad_daily_report`，由 02:00 调度逐天回补（含当天自愈归因延迟）；mock 环境写入相同 stub 行，趋势拉平属预期 |
| **第三方物流轨迹 API** | 17track 对接已实现但**默认关闭**（`AMZ_TRACKING_ENABLED=false` 且需 `AMZ_17TRACK_KEY`）。未配置时不发起外部请求、调度整轮跳过，轨迹改由 `/logistics/import/*` 导入维护；`carrier-codes` 映射留空时请求不带承运商代码，交由其按单号自动识别 |
| **物流承运商代码映射** | 承运商 → 17track 数字代码的映射需按官方文档填写（代码值随对端迭代变动，未预置以避免写入错误值）；留空不影响可用性，仅可能因识别歧义而匹配较慢 |
| **看板聚合方式** | 单店货件量级（千级以内）下采用「按店铺取出 + 内存聚合」，避免十余个指标各发一次 SQL；若单店量级升至数万，应将状态计数与趋势分桶下推为 SQL GROUP BY（对外契约不变） |
| **报价过期靠任务或手工收口** | 报价是否失效由 `expiry_date` 判定，但状态字段不会自动翻转。比价时已强制按日期过滤（不会用失效运价选商），看板也会单独统计「已失效未收口」并支持一键置为 `EXPIRED`；尚未接入定时任务自动收口 |
| **调拨收货不改动库存账** | `receiveTransfer` 只推进单据状态，尚未联动 `amz_warehouse_inventory` 的源仓扣减 / 目标仓增加。在补上库存联动之前，调拨单状态与仓库实物库存之间需要人工对齐 |
| **头程分摊的基准数据需人工登记** | 分摊明细（ASIN/SKU/数量/重量或体积）需先登记才能计算，系统不自动从货件或 Listing 推导；看板会把「未核算成本」的货件列出来提示补录，但不会代填 |
| **签收差异需人工登记** | 差异记录由人工或外部对账结果录入，尚未接入 FBA 入库报告自动比对；差异类型（少收/多收）与差异数量由服务端按应收/实收计算，不接受自报 |
| **运费比价不含附加费与时效权重** | 比价只算「重量价与体积价取高 + 最低收费 + 燃油附加费」，未纳入旺季附加费、偏远地区费、清关杂费；时效仅并列展示，不折算成成本（不同品类对时效的敏感度差异很大，不宜在系统里拍一个权重） |

| **SP-API 财务接口未做真实联调** | Reports / Finances / Fees 的端点与字段路径按官方文档实现，但无沙箱凭证未实际调用；`mock` profile 提供确定性样例数据（结算 TSV、四类财务事件、费用分档估价），可用 `SPRING_PROFILES_ACTIVE` 切到真实客户端 |
| **成本口径是 FIFO 近似** | 单品利润的销货成本用「批次加权平均单位成本 × 售出件数」。严格 FIFO 需要批次级出库与销售的逐笔关联，当前批次域只提供库存批次，无法归属到具体订单 |
| **入库短收金额需人工登记** | 索赔金额 = 少收数量 × 单位成本，单位成本属采购域、签收数量属物流域，跨域成本对齐前不做自动推算；接口接受显式单位成本，宁可让调用方把数字给准 |
| **「未回」订单无法枚举** | 回款台账由结算数据聚合而成，没有结算记录的订单不会出现在表中，因此 `PENDING` 状态只在接入订单域数据后才有意义（概览接口对此有显式提示） |
| **结算同步调度默认关闭** | `amz.finance.settlement.sync-enabled=false`，未配置凭证的环境不会反复报错；开启后按 `sync-shop-ids` 配置项 ∪ 已有结算数据的店铺逐店同步，单店铺用分布式锁防重 |
| **索赔提交未对接平台索赔 API** | 索赔单状态（已提交 / 已受理 / 已赔付）由人工推进并在系统内留痕，尚未自动向平台提交索赔请求与轮询结果 |

## 🚀 快速开始

### 1. 克隆

```bash
git clone https://github.com/cgs123456/AmazonERP.git
cd AmazonERP
```

### 2. 配置环境变量（复制模板）

```bash
cp .env.example .env
# 编辑 .env 填入真实凭据（DB_PASSWORD、REDIS_PASSWORD、JWT_SECRET_KEY 等必填）
```

### 3. 启动基础设施

```bash
docker-compose up -d
```

> `docker-compose.yml` 实测包含 **31 个 service**（基础设施 + 网关 + 业务服务 + 前端；2026-09-24 以文件为准，旧口径「17 服务」已过期）。注意：Compose 已为 16 个 Spring 服务逐段注入 `SPRING_PROFILES_ACTIVE`（缺省值 `prod`，离线演示可设为 `mock`；`prod,mock` 混用会被公共启动守卫拒绝）、`REDIS_HOST` 全域缺失、`MYSQL_HOST` 仅 spapi 有值，直接 `up -d` 只能用于本地演示，不能作为部署基线。

### 4. 启动业务服务

启动顺序：Nacos → Gateway → User → 其他业务服务

> 构建要求 JDK 17 + Maven 3.8+（`mvn -v` 确认；仓库无 wrapper，本机验证组合：Temurin 17.0.20 + Maven 3.9.9）

```bash
# 默认 prod（真实客户端，fail-closed）；离线演示请显式指定 mock；不要混用 prod,mock
mvn -pl amz-service/amz-service-user spring-boot:run

# real 模式（需真实 SP-API 凭证）
mvn -pl amz-service/amz-service-spapi spring-boot:run -Dspring.profiles.active=prod
```

> **Windows 本地脚本（作者机器专用，不是部署入口）**：`.start-backend-final.bat`、`.start-vite.bat`、`.start-mysql.bat` 仍含绝对路径或本机数据目录假设；生产与新机器请使用 Docker Compose / k8s，不要把这些批处理脚本当作可移植部署方案。

### 5. 首次部署导入 SP-API 凭证（一次性）

`prod` 实例要求 `amz_shop_credential` 中至少有一条结构完整的店铺凭证，缺凭证会拒绝启动。空库首次部署使用独立的 `bootstrap` profile，不要用 `mock` 或空配置绕过自检。

凭证示例：[`docs/examples/spapi-credentials.example.json`](docs/examples/spapi-credentials.example.json)。完整步骤、权限、失败分类与清理要求见 [`docs/superpowers/runbooks/first-deploy-bootstrap-runbook.md`](docs/superpowers/runbooks/first-deploy-bootstrap-runbook.md)。

Compose 一次性导入的核心命令：

```bash
docker compose -f docker-compose.yml -f docker-compose.bootstrap.yml \
  --profile bootstrap run --rm amz-service-spapi-bootstrap
```

Kubernetes 使用 [`k8s/jobs/amz-service-spapi-credential-bootstrap.yaml`](k8s/jobs/amz-service-spapi-credential-bootstrap.yaml) 创建一次性 Job，凭证通过只读 Secret 挂载。导入完成后立即删除 Secret 和本地临时文件，再以 `prod` profile 启动 `amz-service-spapi`。

> 这条路径只保证“凭证可到达进程并加密落库、缺失时 fail-closed”。它不代表 LWA 授权、SP-API 角色权限、端点字段契约或真实沙箱/生产联调已经完成；当前无真实凭据时证据仍封顶 E2/E3。

### 6. 接入 Amazon Ads API（有凭证后无需改代码）

Amazon Advertising API 与 SP-API 使用不同的授权和凭证体系，不能复用 SP-API 的 refresh token。完整申请、LWA 授权、profile 获取、区域端点、配置、排障与验收模板见 [`docs/superpowers/runbooks/amazon-ads-api-onboarding-runbook.md`](docs/superpowers/runbooks/amazon-ads-api-onboarding-runbook.md)。

单店铺通过 `AD_API_ENDPOINT`、`AD_TOKEN_ENDPOINT`、`AD_SHOP_ID`、`AD_CLIENT_ID`、`AD_CLIENT_SECRET`、`AD_REFRESH_TOKEN`、`AD_PROFILE_ID` 配置；多店铺通过外部配置 `advertising.credentials.<shopId>.*` 配置，`.env`/Compose 的变量只覆盖单店铺。Kubernetes 清单中的 Secret 键是空占位，真实密钥必须由 Secret/Vault/External Secrets/KMS 注入。

凭证到位后，广告模块使用真实客户端并可通过 `POST /ad/reports/sync` 触发报表同步。响应中的 `metadataWarnings` 表示报表已成功落库、但部分活动元数据未完整同步，不等于整店同步失败。真实凭证尚未在本仓库完成沙箱/生产联调，因此当前结论仍只能是 API-ready，不能宣称真实 Amazon API 已验证生产可用。

## 🧪 测试

| 层级 | 用例 | 通过率 |
|------|:----:|:-----:|
| 后端 JUnit 5（2026-10-05 fresh；`mvn test`） | 1999（0 失败 / 0 错误 / 17 跳过） | 20/20 模块 `BUILD SUCCESS` |
| 前端 Vitest（2026-10-05 fresh；`npm run test:run`） | 479（0 失败） | 43/43 文件通过 |
| 前端 Playwright 全交互 E2E（2026-10-05 串行复跑） | 40+（受影响套件） | 全过 |

> 最新整仓数字以 HANDOFF.md 为准（2026-10-05 整仓 1999 tests / 0F / 0E / 17S，BUILD SUCCESS）。

> 早期轮次（第 79~87 轮）的分模块计数（如 SP-API 428、Surefire 1134）为当时快照，已随功能增长过时；**全仓与前端最新数字一律以上表及 HANDOFF.md 为准**。


## 🗂 模拟数据工具链（没有真实数据时的演示 / 压测基线）

`tools/synthetic-data/` 提供确定性模拟数据集：**可生成、可加载、可清理、可识别**，全程不需要任何亚马逊凭证和网络。

| 命令 | 作用 | 当前实测 |
|---|---|---|
| `python apply_migrations.py --host 127.0.0.1 --port 3399 --user amz --reset` | 在 MySQL 8 上建 14 个库并应用 49 个 Flyway 迁移（真机导入的前置步骤；`--dry-run` 只看计划） | 14 库 / 49 迁移，failed=0 |
| `python generate.py --tier demo --reset` | 生成 102 张表 / 14 个库的数据集 | demo 档 225,734 行；ci 档 25,484 行 |
| `python verify.py --tier demo` | 结构 / 引用 / 标记 / 确定性 / DDL 快照校验 | PASS（229 文件两次生成字节一致） |
| `python purge.py --tier demo --emit --registry` | 生成 `cleanup.sql`（102 条 DELETE，对应 102 张活表）与库级登记表 | exit 0，0 表遗漏 |
| `python verify_cleanup.py --tier demo` | 在 SQLite 内实跑 `cleanup.sql` 证明能删干净 | 225,734 行删除、剩余 0 |
| `python verify_schema_load.py --tier demo --cleanup` | 用真实 DDL 快照重建 14 库 / 113 表再灌数，验证类型/长度/精度/NOT NULL/日期/JSON/唯一键，并可跑 `cleanup.sql` 闭环 | demo 档 225,734/225,734 行灌入，0 错误；cleanup 后 113 DELETE / 0 剩余 |
| `./load.ps1 -Tier demo -Container amz-mysql`（或 `./load.sh` / `-Server 127.0.0.1 -Port 3399 -User amz`） | 按库灌入 MySQL（支持远端 / 容器 / `-DryRun`） | 本机一次性 MySQL 8.0.39（端口 3399）：ci 档约 80s、demo 档约 7min，均 exit 0 |
| `python verify_import.py --tier demo --host 127.0.0.1 --port 3399 --user amz --baseline out/ci/baseline.json` | 连真实 MySQL 8 核对 `基线 + manifest` 行数与 `amz_ops` 登记表，并可跑 `cleanup.sql` 闭环 | ci 25,574/25,574、demo 225,824/225,824；cleanup 删除 25,484 / 225,734 行，剩余回到基线 |

标记方式：保留 ID 段 + `SYNTHETIC` 文本标记 + `amz_ops.amz_synthetic_dataset_registry(is_demo=1)`；schema 没有 `is_demo` 列，因此没有为演示去改 102 张表。完整步骤与安全规则见
[`docs/superpowers/runbooks/mock-data-seed-and-cleanup-runbook.md`](docs/superpowers/runbooks/mock-data-seed-and-cleanup-runbook.md)。

> 边界：模拟数据**不是** SP-API 联调证据，连接器中心不得因此显示"已接通"。证据分两层：
>
> - **离线三层**：数据自洽与确定性 `verify.py`、清理可删净 `verify_cleanup.py`、数据符合真实 DDL 列定义与约束 `verify_schema_load.py`。
> - **真机导入**：已在本机**一次性 MySQL 8.0.39 实例**（仓库外 `C:\tools\mysql8`，端口 3399；仅用于验证，不属于任何部署拓扑）完成 ci / demo 两档灌入、行数核对与 `cleanup.sql` 删除闭环（`verify_import.py`）。这是本机一次性实例的导入验证，**不是生产环境验证**，也没有接入任何亚马逊凭证或做过联调。
>
> 另外：批量 SQL 灌数**绕过**应用层校验、审计与租户钩子，小批量冒烟请走 API；`amz_ops.amz_synthetic_dataset_registry` 的登记行按设计**不会**被 `cleanup.sql` 删除（它是"此库装过模拟数据"的审计痕迹），需要彻底清空时额外执行 `DELETE FROM amz_ops.amz_synthetic_dataset_registry WHERE dataset_id='synthetic-amazon-erp-v1';`。

## 📐 项目结构

```
AmazonERP/
├── amz-common/           # 公共模块（Result/UserContext/AOP/GlobalExceptionHandler）
├── amz-gateway/          # API 网关（JWT + Sentinel + 路由）
├── amz-service/          # 15 个业务微服务
│   ├── amz-service-user/         # 用户 | 8080
│   ├── amz-service-product/      # 商品 + Keepa | 8095
│   ├── amz-service-order/        # 订单 + 审单 | 8105
│   ├── amz-service-search/       # ES 检索 | 8090
│   ├── amz-service-message/      # WebSocket + Messaging | 8889
│   ├── amz-service-ai/           # AI Agent 29 工具 | 8091
│   ├── amz-service-spapi/        # SP-API 对接 | 8096
│   ├── amz-service-ad/           # 广告 | 8097
│   ├── amz-service-procurement/  # 采购 | 8098
│   ├── amz-service-customer/     # 客服 | 8099
│   ├── amz-service-logistics/    # 物流 | 8100
│   ├── amz-service-ops/          # 运营工具 | 8101
│   ├── amz-service-report/       # 报表 | 8102
│   ├── amz-service-finance/      # 财务 | 8103
│   └── amz-service-multiplatform/# 多平台 | 8104
├── amz-frontend/         # Vue 3 前端 + Playwright E2E
├── docker/               # init-sql 仅建 14 空库；init-sql-legacy 仅存档，不参与初始化
├── prometheus/           # Prometheus 配置 + 告警规则
├── grafana/              # Grafana 预置面板
├── alertmanager/         # AlertManager 配置
├── logstash/             # Logstash 管道
├── docker/               # init-sql、rabbitmq 插件、filebeat 配置（Skywalking Agent 在根 Dockerfile 注入）
├── k8s/                  # Kubernetes 部署清单
├── scripts/              # 工具脚本（TLS 证书生成等）
├── ml/                   # LightGBM 训练脚本
├── loadtest/             # Gatling + JMeter 压测
├── docker-compose.yml    # 31 service 全栈编排（实测）
├── Dockerfile            # 多阶段构建
├── .env.example          # 环境变量模板（实测 152 行 / 110 个赋值键）
└── .github/workflows/    # CI（checkstyle + 全模块测试 + Docker build）
```

## 🔧 环境变量速查

| 变量 | 说明 | 必填 |
|------|------|:--:|
| `DB_USERNAME` / `DB_PASSWORD` | MySQL 凭证 | ✅ |
| `REDIS_PASSWORD` | Redis 密码 | ✅ |
| `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | RabbitMQ 凭证 | ✅ |
| `JWT_SECRET_KEY` | JWT 签名密钥 | ✅ |
| `AMZ_CRYPTO_KEY` | AES-256-GCM 密钥（base64） | ✅ |
| `DEEPSEEK_API_KEY` | DeepSeek API（AI Agent） | 推荐 |
| SP-API LWA 店铺凭证 | 通过 bootstrap JSON 导入 `amz_shop_credential`（clientId/clientSecret/refreshToken） | prod ✅ |
| `AD_API_ENDPOINT` / `AD_TOKEN_ENDPOINT` | Amazon Ads 区域端点与 LWA token 端点 | Ads prod ✅ |
| `AD_SHOP_ID` / `AD_CLIENT_ID` / `AD_CLIENT_SECRET` / `AD_REFRESH_TOKEN` / `AD_PROFILE_ID` | Amazon Ads 单店铺凭证；多店铺用 `advertising.credentials.<shopId>.*` 外部配置 | Ads prod ✅ |
| `AWS_ACCESS_KEY` / `AWS_SECRET_KEY` | 可选；仅历史集成/条件签名需要，SP-API 主链路使用 LWA | 可选 |
| `KEEPA_API_KEY` | Keepa 竞品数据 | 可选 |
| `SEATA_ENABLED` | 启用 Seata 分布式事务 | 可选 |
| `SSL_ENABLED` | 启用 TLS | 可选 |
| `GRAFANA_USER` / `GRAFANA_PASSWORD` | Grafana 登录 | 可选 |

详见 `.env.example`。

## 📄 License

MIT
