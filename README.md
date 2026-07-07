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

## 微服务架构（9 服务）

```
amz-gateway          (10010) — API 网关（JWT + shopId 校验）
amz-service-auth     (8086)  — 用户 + 多店铺 RBAC
amz-service-product  (8087)  — 商品/Listing 管理
amz-service-order    (8088)  — 订单 + Amazon 同步
amz-service-search   (8090)  — ES 混合检索
amz-service-message  (8089)  — WebSocket 实时通知
amz-service-ai       (8091)  — AI 运营 Agent
amz-service-spapi    (8096)  — SP-API 对接层（LWA + SigV4 + 定时同步）
amz-common           —       — 公共模块（Result/UserContext/Interceptor/EmbeddingService）
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
git clone https://github.com/cgs123456/xiaohongshu.git
cd amazon-erp
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

## 改造溯源

本项目从「仿小红书社交电商平台」改造而来，复用率 80%+：
- 网关 / 订单 / 搜索 / Agent / MQ / Redis 架构完全复用
- 删除社交模块（note/comment）
- 新增 SP-API 对接层 + 多店铺 RBAC
- 新增 4 大 P0 业务模块（FBA 库存 / 智能补货 / 财务利润 / 跨站点 Listing）
- AI Agent 从「购物助手」改造为「运营数据分析」，工具数 5 → 12

## 数据库初始化

P0 模块相关表结构位于 `docker/init-sql/09-init-tables-p0-modules.sql`，包含 15 张表 + 1 个视图：

- **P0-1**：`amz_fba_inventory` / `amz_product_sales_stats` / `amz_inventory_sync_log`
- **P0-4**：`amz_replenishment_suggestion` / `amz_sales_history` / `amz_seasonal_index` / `amz_promotion_calendar`
- **P0-3**：`amz_product_cost` / `amz_category_fee_rate` / `amz_fba_fee_table` / `amz_profit_report` / `v_profit_summary_by_sku`
- **P0-2**：`amz_product` / `amz_listing_copy_task` / `amz_translation_cache`

预置数据：5 类目 × 12 月季节性指数、4 条促销日历、12 条类目佣金率、9 条 FBA 费率
