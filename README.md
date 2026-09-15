# Amazon ERP — 微服务跨境电商管理平台

基于 Spring Cloud 微服务架构的亚马逊卖家全链路 ERP 系统，集成 SP-API 实现订单、库存、广告、采购、客服、物流、财务全业务闭环，内置 AI 运营 Agent（29 工具）与可观测性三栈。

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
amz-service-spapi           — SP-API 对接层（LWA + SigV4）
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

> 共 54 张表（含广告日报表、统一订单明细列）、140+ REST 端点、AI Agent 29 工具

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
> **流式问答**：`GET /ai/chat-stream` SSE（fetch + ReadableStream，可透传 token/shopId 头）+ POST 兜底 + 工具调用时间线。


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
`amz_reimbursement_claim`（索赔单）。建表脚本三处镜像同步（模块 Flyway + `docker/init-sql/15` + `init_all_tables.sql`）。

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
| **调度防重** | `DistributedJobLock` Redis 分布式锁，多实例部署不重复执行 |
| **SQL 注入防护** | 全 MyBatis `#{}` |
| **全局异常处理器** | 统一 `@ControllerAdvice` 覆盖 16 服务 |
| **数据库迁移** | Flyway 10.20.0（14 MySQL 服务 V1__init.sql + multiplatform V2 明细列，baseline-on-migrate 兼容存量库） |
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

> docker-compose.yml 包含 17 服务：nacos + mysql(主+从) + redis + rabbitmq + mongodb + elasticsearch + prometheus + grafana + skywalking-oap + skywalking-ui + logstash + kibana + alertmanager + 网关 + spapi + 前端

### 4. 启动业务服务

启动顺序：Nacos → Gateway → User → 其他业务服务

> 构建要求 JDK 17 + Maven 3.8+（`mvn -v` 确认；仓库无 wrapper，本机验证组合：Temurin 17.0.20 + Maven 3.9.9）

```bash
# 默认 mock 模式（内置样例数据）
mvn -pl amz-service/amz-service-user spring-boot:run

# real 模式（需真实 SP-API 凭证）
mvn -pl amz-service/amz-service-spapi spring-boot:run -Dspring.profiles.active=real
```

> **Windows 本地一键全栈**：仓库根目录提供 `.start-backend-final.bat`（14 微服务按依赖顺序拉起）与 `.start-vite.bat`（前端），配套 `.start-mysql.bat` 初始化本地 MySQL/Redis。

## 🧪 测试

| 层级 | 用例 | 通过率 |
|------|:----:|:-----:|
| 后端 JUnit 5（15 有单测模块，gateway/product 暂无） | 527（0 失败，spapi 2 个集成跳过） | 100% |
| 前端 Vitest（16 文件） | 133（含接口映射、静默刷新、SSE 解析、知识库、店铺守卫、物流看板及四子域用例） | 100% |
| 前端 Playwright 全交互 E2E（连接真实后端栈：8 页导航 + KPI + Agent 对话 + 分页 + Tab 切换 + 弹窗 + 过滤 + 登录守卫 + 404） | 39 | 100% |
| **总计** | **699** | **100%** ✅ |

> E2E 通过 `.start-backend-final.bat` + `.start-vite.bat` 拉起本地全栈后运行 `npx playwright test`。

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
├── docker/               # Docker 配置 + init-sql（01-33）
├── prometheus/           # Prometheus 配置 + 告警规则
├── grafana/              # Grafana 预置面板
├── alertmanager/         # AlertManager 配置
├── logstash/             # Logstash 管道
├── skywalking/           # Skywalking Agent 配置
├── k8s/                  # Kubernetes 部署清单
├── scripts/              # 工具脚本（TLS 证书生成等）
├── ml/                   # LightGBM 训练脚本
├── loadtest/             # Gatling + JMeter 压测
├── docker-compose.yml    # 17 服务全栈编排
├── Dockerfile            # 多阶段构建
├── .env.example          # 环境变量模板 ≥20 项
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
| `AWS_ACCESS_KEY` / `AWS_SECRET_KEY` | SP-API 凭证 | real 模式 |
| `KEEPA_API_KEY` | Keepa 竞品数据 | 可选 |
| `SEATA_ENABLED` | 启用 Seata 分布式事务 | 可选 |
| `SSL_ENABLED` | 启用 TLS | 可选 |
| `GRAFANA_USER` / `GRAFANA_PASSWORD` | Grafana 登录 | 可选 |

详见 `.env.example`。

## 📄 License

MIT
