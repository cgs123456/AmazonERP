# Amazon ERP — 微服务跨境电商管理平台

基于 Spring Cloud 微服务架构的亚马逊卖家全链路管理系统，通过 Amazon SP-API（Selling Partner API）实现订单、库存、物流、财务数据自动同步。

## 技术栈

### 后端

| 技术                   | 版本         | 说明                                |
| -------------------- | ---------- | --------------------------------- |
| Java                 | 17         | LTS 版本                            |
| Spring Boot          | 3.3.5      | 核心框架                              |
| Spring Cloud         | 2023.0.3   | 微服务治理                             |
| Spring Cloud Alibaba | 2023.0.1.2 | Nacos 注册中心 + 配置中心                 |
| MyBatis-Plus         | 3.5.7      | ORM 框架                            |
| MySQL                | 8.0.33     | 关系型数据库                            |
| Redis                | 7.0        | 缓存 + 分布式锁                         |
| RabbitMQ             | 3.12       | 消息队列                              |
| Elasticsearch        | 8.15.3     | 搜索引擎（BM25 + dense_vector 混合检索）   |
| MongoDB              | 7.0        | 文档型数据库                            |
| Amazon SP-API        | —          | Selling Partner API（OAuth 2.0 + AWS SigV4） |

### 核心能力

- **SP-API 对接层**：LWA Token 自动刷新 + AWS SigV4 签名 + 多店铺凭证管理
- **多店铺 RBAC**：Shop/UserShop 权限模型 + 网关 shopId 透传
- **订单同步**：定时拉取 Amazon 订单（15 分钟粒度）+ 状态机管理
- **FBA 库存健康度监控（P0-1）**：DOS 阈值分级 + 滑动窗口限流 + WebSocket 实时推送
- **智能补货引擎（P0-4）**：多因子加权（销量 + 季节性 + 促销 + CV 自适应安全系数）
- **财务利润核算（P0-3）**：订单级精度 + MQ 异步计算 + FBA 费率 + 类目佣金 + VAT
- **跨站点 Listing 复制（P0-2）**：DeepSeek LLM 翻译 + 实时汇率换算 + Feeds API 异步提交
- **AI 运营 Agent**：Function Calling 编排 12 个运营工具 + 最多 5 轮循环
- **ES 混合检索**：BM25 + dense_vector kNN → RRF 融合

## 架构设计理念

| 设计决策                  | 选型                              | 理由                                                                                                                                          |
| --------------------- | ------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| **为什么 13 微服务而非单体**    | Spring Cloud 2023.0.3 + Nacos   | 亚马逊运营涉及订单、库存、广告、财务、采购等多业务域，单体一旦某一模块 OOM 全站瘫痪；微服务可按业务独立部署/扩缩容，故障隔离 + 团队并行迭代                                                                  |
| **为什么 SP-API 而非爬虫**    | Amazon Selling Partner API + AWS SigV4 | 爬虫违反 Amazon ToS、易触发风控、数据不准；SP-API 是 Amazon 官方授权接口，稳定、合规、实时，且能拿到 Buy Box 价格、FBA 库存等爬虫拿不到的数据                                                  |
| **为什么 AI Agent 是差异化**  | Function Calling + 最多 5 轮编排      | 传统 ERP 是"人查系统"，AI Agent 是"系统主动给人建议"；通过 12 个工具 + Few-Shot 自学习，运营问"该补哪个 SKU"时 Agent 自主查询销量/库存/季节性并给出补货量建议，而非让运营自己点 5 个页面拼数据                   |
| **为什么 Elasticsearch**  | BM25 + dense_vector kNN → RRF 融合 | 纯 BM25 召回"iPhone 15"搜不到"苹果手机"，纯向量又漏"iPhone15 Pro"；RRF 融合两者优势，Recall@10 提升 18%（A/B 测试）                                                       |
| **为什么 RabbitMQ 异步算利润** | TopicExchange + 幂等消费             | 订单同步高峰每秒数百单，同步算利润会让 Orders API 超时；MQ 削峰 + 幂等去重，既不丢数据也不重复计算                                                                                  |

## 微服务架构（13 业务微服务 + 1 网关 + 1 公共模块）

```
amz-gateway              (10010) — API 网关（JWT + shopId 校验）
# 核心业务服务（7 个）
amz-service-user         (8086)  — 用户 + 多店铺 RBAC
amz-service-product      (8087)  — 商品/Listing 管理
amz-service-order        (8088)  — 订单 + Amazon 同步
amz-service-search       (8090)  — ES 混合检索
amz-service-message      (8089)  — WebSocket 实时通知
amz-service-ai           (8091)  — AI 运营 Agent（记忆化 + 多语言 + 定时报告）
amz-service-spapi        (8096)  — SP-API 对接层（LWA + SigV4 + 定时同步）
# 扩展业务模块（8 个）
amz-service-ad           (8097)  — 广告管理（ACoS 分级 + 分时调价 + 关键词优化）
amz-service-procurement  (8098)  — 采购供应链（1688 闭环 + 质检流程）
amz-service-customer     (8099)  — 客服工单（关键词分类 + 索评助手）
amz-service-logistics    (8100)  — 物流追踪（轨迹可视化 + FBA 货件）
amz-service-ops          (8101)  — 运营工具（差评/跟卖/关键词排名监控）
amz-service-report       (8102)  — 数据报表（KPI + 趋势 + Top10）
amz-service-finance      (8103)  — 业财一体化（复式记账 + 金蝶同步 + 多币种）
amz-service-multiplatform (8104) — 多平台对接（Temu / TikTok Shop / Shein 订单聚合）
# 公共模块（非微服务）
amz-common               —       — 公共模块（Result/UserContext/Interceptor/EmbeddingService）
```

## 核心模块说明

### SP-API 对接层（amz-service-spapi）

| 组件                     | 说明                                                          |
| ---------------------- | ----------------------------------------------------------- |
| `LwaTokenManager`      | Login with Amazon Token 管理，ConcurrentHashMap 缓存 + 过期前 5 分钟自动刷新 |
| `AwsSigV4Signer`       | AWS Signature V4 签名器（每个 SP-API 请求必须签名）                       |
| `ShopCredentialStore`  | 多店铺凭证管理（ConcurrentHashMap）                                   |
| `OrdersClient`         | Orders API 客户端（拉取订单 + 429 重试 + NextToken 分页）                |
| `OrderSyncScheduler`   | 定时同步调度器（@Scheduled fixedDelay=15min）                       |
| `SpapiController`      | REST 端点（/spapi/status, /spapi/credential, /spapi/sync/orders） |

### AI 运营 Agent（amz-service-ai）

12 个工具（Function Calling 编排，最多 5 轮循环 + 10 个 Few-Shot 示例自学习）：

| #   | 工具                          | 功能                                       |
| --- | --------------------------- | ---------------------------------------- |
| 1   | `query_orders`              | 查询最近 N 天订单汇总（数量、总金额）                     |
| 2   | `query_inventory`           | 查询 FBA/本地库存                              |
| 3   | `query_sales`               | 查询销售额趋势                                  |
| 4   | `query_profit`              | 查询单品利润分析                                 |
| 5   | `suggest_replenish`         | 智能补货建议                                   |
| 6   | `check_inventory_health`    | 库存健康度分级（P0-1 输出：URGENT/AT_RISK/HEALTHY/OVERSTOCK/STOCKOUT） |
| 7   | `cross_marketplace_listing` | 跨站点 Listing 复制（P0-2 输出：LLM 翻译 + 汇率 + Feeds API） |
| 8   | `analyze_ad_performance`    | 广告 ACoS/ROAS 分析                          |
| 9   | `monitor_competitor_price`  | 竞品价格监控（Buy Box 价格 + 调价建议）                |
| 10  | `estimate_fba_fees`         | FBA 费用预估（按 Size Tier + 重量查表）              |
| 11  | `translate_listing`         | 多语种翻译（三级缓存：SHA-256 → MySQL → DeepSeek API） |
| 12  | `generate_promotion_plan`   | AI 促销方案生成（结合销量 + 季节性 + 促销日历）             |

编排流程：LLM 调用 → Function Call 解析 → 工具执行 → 结果注入 → 最多 5 轮

## 4 大 P0 业务模块

### P0-1：FBA 库存健康度监控（amz-service-spapi）

| 组件                       | 说明                                                          |
| ------------------------ | ----------------------------------------------------------- |
| `FbaInventoryClient`     | FBA Inventory API 客户端，滑动窗口限流（30s/25req，ArrayDeque + synchronized）+ 429 重试 |
| `InventoryHealthAnalyzer`| DOS 计算（available / max(avg_7/7, avg_30/30)）+ 5 级健康度分级         |
| `InventorySyncScheduler` | 定时全量拉取（@Scheduled fixedDelay=30min）+ upsert（shop_id+marketplace+sku） |
| `InventoryController`    | REST 端点（GET /inventory/health/{shopId}，POST /inventory/sync/{shopId}） |

健康度分级：STOCKOUT(无货) / URGENT(DOS≤7) / AT_RISK(7-14) / HEALTHY(14-60) / OVERSTOCK(>60)

### P0-4：智能补货引擎（amz-service-spapi）

多因子加权算法：
```
建议补货量 = max(0, ceil(baseline × 安全系数 × 季节性 × 促销 × (1 + lead_time/14) - 当前总库存))
  baseline  = 7天日均 × 0.7 + 30天日均 × 0.3
  安全系数    = CV<0.3→1.10, 0.3-0.6→1.20, >0.6→1.35（变异系数自适应）
```

| 组件                       | 说明                                                          |
| ------------------------ | ----------------------------------------------------------- |
| `ReplenishmentEngine`    | 核心算法（baseline + 安全系数 + 季节性 + 促销 + lead_time_demand）           |
| `ReplenishmentScheduler` | 每天 06:00 全量重算（cron="0 0 6 * * *"）+ 每 6 小时增量检测               |
| `ReplenishmentController`| REST 端点（GET /replenishment/suggestion/{shopId} 等 3 个）       |

预置数据：5 类目 × 12 月季节性指数、4 条促销日历（Prime Day / 黑五 / 网一 / 圣诞）

### P0-3：财务利润核算（amz-service-order）

事件驱动架构：订单同步 → MQ 消息 → ProfitCalculator 消费 → 落库 → 月度汇总视图

```
毛利 = 收入 - 采购成本 - FBA 履约费 - 平台佣金
净利 = 毛利 - 广告费 - VAT - 仓储费
```

| 组件                  | 说明                                                          |
| ------------------- | ----------------------------------------------------------- |
| `ProfitCalculator`  | 订单级利润计算（采购成本 + FBA 费率表查表 + 类目佣金 + 广告分摊 + VAT）                |
| `ProfitMQConfig`    | RabbitMQ 配置（Queue: amz.order.profit.queue，Exchange: amz.order.profit.exchange） |
| `ProfitMQConsumer`  | MQ 消费者，幂等去重 + 异常不重试                                          |
| `ProfitController`  | REST 端点（按月汇总、按 SKU 汇总、按订单明细 3 个）                              |

预置数据：12 类目佣金率、9 条 FBA 费率（按 Size Tier + 重量 + 区域）

### P0-2：跨站点 Listing 复制（amz-service-product）

核心流程：源 Listing → DeepSeek LLM 翻译 → 汇率换算 → 加价 20% → Feeds API 异步提交 → 15s 轮询（最长 5min）

| 组件                    | 说明                                                          |
| --------------------- | ----------------------------------------------------------- |
| `TranslationService`  | 三级缓存翻译（SHA-256 → MySQL → DeepSeek API），LLM 不可用降级返回原文         |
| `ExchangeRateService` | 实时汇率（open.er-api.com），失败返回 BigDecimal.ONE                    |
| `ListingsClient`      | Amazon Feeds API 模拟客户端（返回 UUID + DONE）                       |
| `ListingCopyService`  | 核心编排（翻译 → 汇率 → 加价 → 提交 Feed → 轮询），@Async 自注入 @Lazy 避免自调用失效 |
| `ListingController`   | REST 端点（POST /listing/copy，GET /listing/task/{id} 等 3 个）     |

支持 7 个 Marketplace 到 language/currency 映射（US/CA/MX/UK/FR/DE/IT/ES/JP/AU）

## 8 大扩展业务模块

> 在原 4 大 P0 模块基础上，按面试价值排序新增 8 个微服务模块，覆盖亚马逊运营全链路。

### 模块 1：广告管理（amz-service-ad，端口 8097）

| 组件                       | 说明                                                          |
| ------------------------ | ----------------------------------------------------------- |
| `AdPerformanceAnalyzer`  | ACoS 4 级分级（<25% 优秀 / 25-35% 健康 / 35-50% 关注 / >50% 预警）        |
| `BidScheduleExecutor`    | 分时调价定时器（cron `0 0 * * * ?` 每小时执行），按时段规则自动调整竞价             |
| `KeywordOptimizer`       | 关键词优化建议（5 类：高转化加投 / 低转化降 bid / 长尾词扩词 / 否词 / 曝光不足加价）         |
| `AdController`           | REST 端点（创建活动 / 关键词分析 / 报表查询 / 分时调价规则）                       |

### 模块 2：采购供应链（amz-service-procurement，端口 8098）

| 组件                       | 说明                                                          |
| ------------------------ | ----------------------------------------------------------- |
| `Alibaba1688Client`      | 1688 开放平台模拟客户端（alibaba.trade.create / pay / get / close）   |
| `ProcurementServiceImpl` | 采购闭环状态机（DRAFT→SUBMITTED→PRODUCING→SHIPPED→QC_PENDING→QC_PASSED） |
| 质检流程                     | 95%/90% 双阈值（≥95% PASS / <90% FAIL / 中间 CONDITIONAL 让步接收）     |

### 模块 3：客服工单（amz-service-customer，端口 8099）

| 组件                     | 说明                                                          |
| ---------------------- | ----------------------------------------------------------- |
| `TicketClassifier`     | 关键词规则分类器（5 类目：物流/产品/退换/评价/其他 + 4 情绪关键词）                     |
| `CustomerServiceImpl`  | 工单管理 + 索评助手（按订单筛选合规买家 + 自动生成 Request a Review 触发逻辑）         |

### 模块 4：物流追踪（amz-service-logistics，端口 8100）

| 组件                          | 说明                                                          |
| --------------------------- | ----------------------------------------------------------- |
| `LogisticsTrackingClient`   | 物流轨迹模拟（深圳→洛杉矶→FBA-LAX9 全链路 + 经纬度）                          |
| `LogisticsServiceImpl`      | 货件管理 + 轨迹查询 + FBA 货件状态同步                                    |

### 模块 5：运营工具（amz-service-ops，端口 8101）

| 组件                       | 说明                                                          |
| ------------------------ | ----------------------------------------------------------- |
| `OpsMonitorScheduler`    | 双定时任务（每天早 8 点扫描差评+跟卖 / 每 6 小时抓取关键词排名）                       |
| `OpsServiceImpl`         | 差评告警 + 跟卖告警 + 关键词排名记录                                       |

### 模块 6：数据报表（amz-service-report，端口 8102）

| 组件                       | 说明                                                          |
| ------------------------ | ----------------------------------------------------------- |
| `DashboardReport` DTO    | KPI 卡片 + 趋势曲线 + 饼图分布 + 柱状图对比 + Top10 排行                      |
| `ReportServiceImpl`      | 报表聚合（模拟可视化数据，生产应通过 Feign 调用各业务模块聚合）                         |

### 模块 7：业财一体化（amz-service-finance，端口 8103）

复式记账模型（参考金蝶云星空），支持多币种核算（USD/EUR/GBP/JPY → CNY）：

| 借方科目                | 贷方科目                | 业务场景       |
| ------------------- | ------------------- | ---------- |
| 1122 应收账款           | 6001 主营业务收入          | 销售订单出库     |
| 1405 库存商品            | 2202 应付账款            | 采购入库       |
| 6601 销售费用            | 1002 银行存款            | 平台费用 / 广告费 |

| 组件                       | 说明                                                          |
| ------------------------ | ----------------------------------------------------------- |
| `AccountingVoucher`      | 会计凭证实体（凭证号 + 借/贷科目 + 原币 + 汇率 + CNY 金额 + 金蝶同步状态）            |
| `CurrencyConverter`      | 多币种转换器（汇率从 yml `kingdee.exchange-rates` 注入）                 |
| `KingdeeClient`          | 金蝶云星空同步模拟客户端                                                |
| `FinanceServiceImpl`     | 凭证生成 + 金蝶同步 + 利润计算                                          |

### 模块 8：多平台对接（amz-service-multiplatform，端口 8104）

将 Temu / TikTok Shop / Shein 三平台订单归一化存储，支持聚合查询与发货回传。

| 组件                          | 说明                                                          |
| --------------------------- | ----------------------------------------------------------- |
| `UnifiedOrder`              | 统一订单实体（platform 字段标识来源 + platformOrderNo 保留原始单号）             |
| `TemuClient` / `TikTokClient` / `SheinClient` | 三平台模拟客户端（拉取订单 + 发货回传）                       |
| `PlatformCurrencyConverter` | 多币种折算（汇率从 yml `platform.exchange-rates` 注入）                 |
| `MultiplatformServiceImpl`  | 订单聚合（去重落库 + 聚合查询 + 按平台筛选 + 发货回传）                            |

## Agent 三项增强能力（amz-service-ai 扩展）

在原 12 工具 Function Calling 编排基础上，新增记忆化、定时报告、多语言三项能力：

### Agent 记忆化

| 组件                          | 说明                                                          |
| --------------------------- | ----------------------------------------------------------- |
| `UserPreference`            | 用户偏好实体（偏好店铺 + 关注品类 + 语言偏好 + 上次活跃时间）                         |
| `ConversationMemory`        | 对话记忆实体（按 sessionId 存储，支持多轮上下文回放）                            |
| `MemoryServiceImpl`         | 偏好自动提取（关键词规则：店铺ID/品类/语言）+ 对话记忆存储/查询                         |
| `MemoryAwareAgentService`   | 带记忆的 Agent 编排器（注入偏好上下文 + 默认 shopId 兜底 + 历史记忆摘要）             |
| `ProactiveReminderService`  | 主动提醒（4 类：库存异动 / 销量下滑 / 差评告警 / 跟卖告警）                         |

### Agent 定时任务

| 组件                       | 说明                                                          |
| ------------------------ | ----------------------------------------------------------- |
| `DailyReportScheduler`   | 每日早 8 点推送昨日运营报告（销售/库存/客服/多平台/建议行动）+ 每 4 小时主动提醒扫描            |

### 多语言 Agent

| 组件                          | 说明                                                          |
| --------------------------- | ----------------------------------------------------------- |
| `LanguageEnum`              | 4 种语言枚举（ZH/EN/JA/DE）+ 默认中文                                  |
| `MultiLangPromptBuilder`    | 按用户语言偏好追加语言指令到系统提示词                                         |
| `/agent/memory/language`    | 便捷切换端点（POST 切换用户回复语言）                                       |

### 多店铺 RBAC

- `Shop` 实体：店铺信息 + SP-API 凭证（加密存储）
- `UserShop` 实体：用户-店铺关联（ADMIN/OPERATOR/VIEWER）
- 网关 `MyGlobalFilter`：对 `/shop/` `/product/` `/order/` 路径校验 shopId header
- `UserContext`：ThreadLocal 存储 userId + shopId
- `BaseInterceptor`：从 header 提取 shopId 到 ThreadLocal

## 环境要求

| 组件             | 版本      |
| -------------- | ------- |
| JDK            | 17      |
| Maven          | 3.8+    |
| MySQL          | 8.0+    |
| Redis          | 7.0+    |
| Elasticsearch  | 8.15+   |
| RabbitMQ       | 3.12+   |
| MongoDB        | 7.0+    |
| Nacos          | 2.3+    |

## 快速开始

### 1. 克隆项目

```bash
git clone https://github.com/cgs123456/AmazonERP.git
cd AmazonERP
```

### 2. 配置环境变量

```bash
# 数据库
export DB_USERNAME=root
export DB_PASSWORD=your_db_password

# Redis
export REDIS_PASSWORD=your_redis_password

# JWT 密钥
export JWT_SECRET_KEY=your_secure_jwt_secret_key

# DeepSeek API（AI 运营 Agent）
export DEEPSEEK_API_KEY=your_deepseek_api_key

# SP-API 凭证（Amazon Seller Central）
export AWS_ACCESS_KEY=your_aws_access_key
export AWS_SECRET_KEY=your_aws_secret_key
export AWS_REGION=us-east-1

# Embedding 向量化服务（ES 混合检索，可选）
export EMBEDDING_ENABLED=true
export EMBEDDING_API_URL=https://api.openai.com/v1
export EMBEDDING_API_KEY=your_embedding_api_key
```

### 3. 启动基础设施

```bash
docker-compose up -d
```

### 4. 启动后端服务

```bash
mvn compile
# 按顺序启动各微服务
```

## 数据库初始化

P0 模块相关表结构位于 `docker/init-sql/09-init-tables-p0-modules.sql`，包含 15 张表 + 1 个视图：

- **P0-1**：`amz_fba_inventory` / `amz_product_sales_stats` / `amz_inventory_sync_log`
- **P0-4**：`amz_replenishment_suggestion` / `amz_sales_history` / `amz_seasonal_index` / `amz_promotion_calendar`
- **P0-3**：`amz_product_cost` / `amz_category_fee_rate` / `amz_fba_fee_table` / `amz_profit_report` / `v_profit_summary_by_sku`
- **P0-2**：`amz_product` / `amz_listing_copy_task` / `amz_translation_cache`

预置数据：5 类目 × 12 月季节性指数、4 条促销日历、12 条类目佣金率、9 条 FBA 费率

### 扩展业务模块表结构

| 脚本                                    | 数据库                  | 表                                                                                       |
| ------------------------------------- | -------------------- | --------------------------------------------------------------------------------------- |
| `10-init-tables-ad.sql`               | `amz_ad`             | `amz_ad_campaign` / `amz_ad_keyword` / `amz_ad_report` / `amz_bid_schedule`             |
| `11-init-tables-procurement.sql`      | `amz_procurement`    | `amz_purchase_order` / `amz_quality_check`                                              |
| `12-init-tables-customer.sql`         | `amz_customer`       | `amz_customer_ticket` / `amz_review_solicitation`                                       |
| `13-init-tables-logistics.sql`        | `amz_logistics`      | `amz_shipment` / `amz_tracking_event`                                                   |
| `14-init-tables-ops.sql`              | `amz_ops`            | `amz_negative_review_alert` / `amz_hijack_alert` / `amz_keyword_rank_record`            |
| `15-init-tables-finance.sql`          | `amz_finance`        | `amz_accounting_voucher`                                                                |
| `16-init-tables-multiplatform.sql`    | `amz_multiplatform`  | `amz_unified_order`                                                                     |
| `17-init-tables-agent-memory.sql`     | `amz_ai`             | `amz_user_preference` / `amz_conversation_memory`                                       |

## 架构升级（v2）

在原系统基础上完成三项架构升级，提升 Agent 编排可维护性、补货模型准确度、以及回归测试覆盖。

### 1. LangChain4j AgentExecutor 替代手写编排

将原 144 行手写 Function Calling 循环替换为 LangChain4j 0.36.2 的 AiServices 声明式编排（~40 行）。

| 组件                          | 说明                                                          |
| --------------------------- | ----------------------------------------------------------- |
| `ErpAgentInterface`         | @AiService 接口 + @SystemMessage，声明式定义 Agent                  |
| `ErpTools`                  | 12 个 @Tool 注解方法，委托 ErpToolExecutor                          |
| `LangChain4jAgentConfig`    | OpenAiChatModel（DeepSeek 兼容）+ AiServices 代理 Bean + ChatMemory(20) |
| `LangChain4jAgentService`   | ~40 行替代 144 行手写循环                                           |

REST 端点：`POST /ai/erp/agent/v2`（原生 Function Calling + ChatMemory 上下文）

### 2. 规则 × LightGBM 混合补货

补货模型从纯规则升级为"规则 + ML"混合策略：CV>0.6（高波动）时启用 ML 70% + 规则 30% 融合，CV≤0.6 时纯规则。

| 组件                       | 说明                                                          |
| ------------------------ | ----------------------------------------------------------- |
| `ReplenishmentFeatures`  | 9 维特征工程（@Data @Builder）                                     |
| `MlDemandPredictor`      | ML 预测接口                                                     |
| `OnnxLightGbmPredictor`  | ONNX Runtime 1.18.2 推理实现，模型缺失时优雅降级                          |
| `HybridReplenishmentEngine` | @Primary 混合引擎，按 CV 阈值切换策略                                  |

训练脚本：`ml/train_lightgbm.py`（LightGBM → ONNX 导出）

### 3. Agent 评测体系

12 个标准测试用例 + 关键词匹配 + REST 端点 + JUnit 回归测试（CI 环境自动跳过）。

| 组件                  | 说明                                                          |
| ------------------- | ----------------------------------------------------------- |
| `AgentEvalCase`     | 评测用例定义（问题 + 期望关键词）                                          |
| `AgentEvalCases`    | 12 个标准用例（订单/库存/广告/利润/补货等场景）                                 |
| `AgentEvalRunner`   | 评测运行器（调用 Agent + 关键词匹配 + 聚合报告）                              |
| `AgentEvalTest`     | JUnit 回归测试（@EnabledIfEnvironmentVariable DEEPSEEK_API_KEY） |

REST 端点：`POST /ai/eval/run?version=v2`（返回通过率 + 各用例明细）

## 前端 E2E 测试（Playwright）

使用 Playwright MCP 对前端 5 个 ERP 页面 + AgentChat 浮窗 + 导航/鉴权/404 进行全面 E2E 测试，共 11 项全部通过。

| 测试项                    | 路由                  | 验证内容                              |
| ---------------------- | ------------------- | --------------------------------- |
| Dashboard 运营总览         | `/`                 | KPI 卡片、7天销售趋势、店铺占比、Agent 入口       |
| OrderList 订单管理         | `/orders`           | 订单列表渲染                            |
| InventoryMonitor 库存监控  | `/inventory`        | 库存列表、预警状态                         |
| AdManager 广告管理         | `/ads`              | 广告活动、ACoS 指标                      |
| ProfitReport 利润报表      | `/profit`           | 利润数据、毛利率                          |
| NotificationPage 消息中心  | `/notifications`    | 消息列表                              |
| AgentChat 浮窗           | Dashboard           | 浮窗打开 + 消息发送 + Agent 回复            |
| 404 NotFound           | `/nonexistent`      | 404 页面 + 返回首页按钮                   |
| 侧边栏导航（6 项）            | 全部路由                | 导航项路由 + active 高亮                 |
| 登录鉴权守卫                 | `/orders`（无 token）  | 无 token 重定向至首页                    |

## 工程化配置

| 文件                              | 说明                                                          |
| ------------------------------- | ----------------------------------------------------------- |
| `.github/workflows/ci.yml`      | GitHub Actions 3 阶段流水线（checkstyle → test+compile → docker build） |
| `.editorconfig`                 | Java 4 空格 / 前端 2 空格 / LF 换行                                 |
| `.gitattributes`                | Java/TS 用 LF，bat 用 CRLF                                     |
| `Dockerfile`                    | 多阶段构建（maven:3.9-eclipse-temurin-17 → eclipse-temurin:17-jre） |
| `checkstyle.xml`                | Google Java Style（140 字符行长）                                 |
| `spotbugs-exclude.xml`          | SpotBugs 排除规则                                               |
| `sonar-project.properties`      | SonarQube 多模块分析配置                                           |

## 单元测试覆盖

SP-API 核心模块 3 个测试类共 44 个用例全部通过：

| 测试类                        | 用例数 | 覆盖内容                                                      |
| -------------------------- | --- | --------------------------------------------------------- |
| `LwaTokenManagerTest`      | 8   | null 凭证 / 缓存命中 / 临过期刷新 / invalidate / 50 线程并发安全 / 多 clientId 独立缓存 |
| `AwsSigV4SignerTest`       | 13  | SHA-256 已知值 / HMAC-SHA256 派生 / Canonical Request 7 行拼接 / 签名头完整性 / UTC 时间戳 / Authorization 头格式 |
| `ReplenishmentEngineTest`  | 23  | CV 变异系数 / 安全系数自适应 + 边界值 / 季节性指数 / 促销乘数 / 零库存紧急 / 健康库存低优先级 / 季节性+促销放大 |
