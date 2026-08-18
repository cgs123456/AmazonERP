# Amazon ERP — 微服务跨境电商管理平台

基于 Spring Cloud 微服务架构的亚马逊卖家全链路 ERP 系统，集成 SP-API 实现订单、库存、广告、采购、客服、物流、财务全业务闭环，内置 AI 运营 Agent（28 工具）与可观测性三栈。

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

## 📊 微服务架构（16 业务服务 + 网关 + 公共模块）

```
amz-gateway              (10010)  — API 网关（JWT + Sentinel 限流 + shopId 校验）
# 核心业务（7 个）
amz-service-user         (8086)   — 用户 + 多店铺 RBAC + JWT 双 Token
amz-service-product      (8087)   — 商品/Listing + Keepa 竞品监控
amz-service-order        (8105)   — 订单 + SP-API 同步 + 智能审单
amz-service-search       (8090)   — ES 混合检索
amz-service-message      (8889)   — WebSocket + Amazon Messaging
amz-service-ai           (8091)   — AI 运营 Agent（28 工具）
amz-service-spapi        (8096)   — SP-API 对接层（LWA + SigV4）
# 扩展业务（8 个）
amz-service-ad           (8097)   — 广告管理（ACoS + 搜索词 + 自动规则）
amz-service-procurement  (8098)   — 采购供应链（供应商 + 1688 + FBA 货件）
amz-service-customer     (8099)   — 客服（邮件 + 差评匹配 + RMA）
amz-service-logistics    (8100)   — 物流（商比价 + 调拨 + 头程分摊）
amz-service-ops          (8101)   — 运营工具（差评/跟卖/关键词监控）
amz-service-report       (8102)   — 数据报表（利润/周转/经营看板）
amz-service-finance      (8103)   — 业财一体（复式记账 + 金蝶 + VAT）
amz-service-multiplatform (8104)  — 多平台（Shopify/eBay/Walmart/Shopee/Lazada）
# 公共模块
amz-common               —        — 公共（Result/UserContext/AOP/GlobalExceptionHandler/Flyway）
```

> 共 51 张表、137+ REST 端点、AI Agent 28 工具 ｜ **改进计划（2026-08-17）全部 8 项已闭环，里程碑 A/B/C/D 已完成**

## 🤖 AI 运营 Agent（28 工具）

基于 LangChain4j AiServices 声明式编排，按用户意图自动调度工具链：

| 类型 | 工具 | 说明 |
|------|------|------|
| **基础查询（9）** | query_orders、query_inventory、query_sales、query_profit、suggest_replenish、check_inventory_health、query_purchase_orders、query_suppliers、query_advertising | 覆盖订单/库存/销售/利润/补货/健康度/采购/供应商/广告 |
| **分析洞察（8）** | analyze_ad_performance、analyze_product_reviews、analyze_product_selection、analyze_listing_health、analyze_search_terms、analyze_sales_trend、analyze_inventory_aging、track_shipment | 覆盖广告/评论/选品/Listing/搜索词/销售趋势/库龄/物流 |
| **优化建议（8）** | optimize_ad_campaign、optimize_listing_seo、optimize_shipping_route、optimize_inventory_distribution、cross_marketplace_listing、monitor_competitor_price、estimate_fba_fees、translate_listing | 覆盖广告优化/Listing SEO/物流/库存调拨/跨站点/竞品/FBA 费用/翻译 |
| **操作执行（6）** | create_purchase_plan、auto_reply_message、generate_promotion_plan | 覆盖采购计划/消息回复/促销方案 |

> 部分工具含 mock 降级，切 `real` profile + 配置外部 API 凭据后对接真实数据源。

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

### P0-4：智能补货引擎
- 多因子加权：7天日均×0.7 + 30天日均×0.3 × 安全系数(CV 自适应) × 季节性 × 促销
- CV>0.6：LightGBM(70%) + 规则(30%) ONNX 混合推理
- Cron 每天 06:00 全量 + 每 6h 增量

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
| **多店铺 RBAC** | `@ShopScoped` + `ShopIdGuardAspect`（41 方法）+ 网关 JWT+shopId 校验 |
| **字段级权限** | `@FieldPermission` + 切面 + 前端 `***` 掩码 |
| **JWT 双 Token** | access_token(24h) + refresh_token(7d) |
| **网关限流** | Sentinel 1.8.6 + Gateway 适配器 |
| **分布式事务** | Seata AT 2.0.0（条件启用 `SEATA_ENABLED=true`） |
| **TLS 可选** | SSL 配置块（默认关闭，需证书） |
| **MQ 死信队列** | save.order + login.notice → DLX/DLQ |
| **SQL 注入防护** | 全 MyBatis `#{}` |
| **全局异常处理器** | 统一 `@ControllerAdvice` 覆盖 16 服务 |
| **数据库迁移** | Flyway 10.20.0（14 MySQL 服务 V1__init.sql） |
| **Docker 健康探针** | 16 服务 Actuator health/liveness/readiness |

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

```bash
# 默认 mock 模式（内置样例数据）
mvn -pl amz-service/amz-service-user spring-boot:run

# real 模式（需真实 SP-API 凭证）
mvn -pl amz-service/amz-service-spapi spring-boot:run -Dspring.profiles.active=real
```

### 5. 访问

| 服务 | 地址 |
|------|------|
| 前端 | http://localhost:5173 |
| 网关 | http://localhost:10010 |
| Grafana | http://localhost:3000 |
| Prometheus | http://localhost:9090 |
| Skywalking UI | http://localhost:8081 |
| Nacos | http://localhost:8848/nacos |
| API 文档 | http://localhost:10010/swagger-ui.html |

## 🧪 测试

| 层级 | 用例 | 通过率 |
|------|:----:|:-----:|
| 后端 JUnit 5（19 模块） | 546 | 100% |
| 前端 Vitest（8 文件） | 57 | 100% |
| 前端 Playwright E2E（10 页面 + 404 + 守卫） | 14 | 100% |
| **总计** | **282** | **100%** ✅ |

## 📐 项目结构

```
AmazonERP/
├── amz-common/           # 公共模块（Result/UserContext/AOP/GlobalExceptionHandler）
├── amz-gateway/          # API 网关（JWT + Sentinel + 路由）
├── amz-service/          # 15 个业务微服务
│   ├── amz-service-user/         # 用户 | 8086
│   ├── amz-service-product/      # 商品 + Keepa | 8087
│   ├── amz-service-order/        # 订单 + 审单 | 8105
│   ├── amz-service-search/       # ES 检索 | 8090
│   ├── amz-service-message/      # WebSocket + Messaging | 8889
│   ├── amz-service-ai/           # AI Agent 28 工具 | 8091
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
