# 亚马逊 ERP 改进方案（可行性设计）

> 版本：2026-08-17 ｜ 范围：项目二 `amazon-erp` 八项"仍可改进"项
> 基线：用户已闭环项（4 个造假 AI 工具→真实 Feign、ListingsRealClient→真实 SP-API Feeds、TikTok/Shein/Temu 真实签名、FeedsController IDOR、`SP-API` 非 200 抛异常、ReDoS 防护、`ListingCopyService` 正确 feed 信封、工具 19–28 `[演示估算]` 标识、采购计划去随机、汇率/连接测试日志与命名修正）已作为现状基线，本文不再重复。
> 状态（2026-08-18）：**全部 8 项已完成**，里程碑 A/B/C/D 全部闭环，546 单测全绿，BUILD SUCCESS。

---

## 1. 八项改进总览

| 编号 | 改进项 | 优先级 | 工作量(人日) | 主要依赖 | 核心难点 | 状态 |
|------|--------|--------|--------------|----------|----------|------|
| 1 | 工具 19–28 真实数据接入 | 高 | 8–15 | 2、现有数据表 | 需新后端（listing monitor / 库龄 / 销售聚合） | ✅ 已完成 |
| 2 | 1688 开放平台真实对接 | 中 | 3–5 | 5、6 | 签名+token+沙箱凭证 | ✅ 已完成 |
| 3 | 汇率静态表→实时 | 中 | 1–2 | 无（独立） | 三套实现合并去重 | ✅ 已完成 |
| 4 | 多平台签名按官方校准 | 中 | 2–4 | 平台沙箱凭证 | 联调校准，非架构问题 | ✅ 已完成 |
| 5 | HTTP 重试/熔断/超时 | 中 | 3–4 | 无（独立） | 替换散落的 `new RestTemplate()` | ✅ 已完成 |
| 6 | 凭证管理（密管+多租户） | 中 | 3–5 | 无（独立） | 多店铺凭证建模 | ✅ 已完成 |
| 7 | Feign 端点 shopId 越权收口 | 低-中 | 2–3 | 现有 `isShopAllowed` | 全量端点审计 | ✅ 已完成 |
| 8 | P0/P1 回归测试补齐 | 中 | 3–4 | 1–7 各改动 | 覆盖 Feign/SP-API/ReDoS | ✅ 已完成 |

**优先级逻辑**：6（凭证）与 5（弹性客户端）是基础设施，为 2/4/7 铺路，应先于真实后端对接；3（汇率）为低风险高价值快赢；1 是价值最高但成本最大，放最后分层推进。

---

## 2. 逐项方案

### 2.1 工具 19–28 真实数据接入（高）

**现状与根因**
- 两套工具实现并存：LangChain4j 的 `amz-service-ai/.../langchain4j/ErpTools.java`（28 个 `@Tool`，非文档误写的 32 个）与旧 `agent/ErpToolExecutor.java`（含 `DEMO_DATA_NOTE = "[演示估算·非真实业务数据]"`，采购计划 `:920` 等返回占位估算）。
- 工具 19–28（按 `ErpTools.java` 顺序）为分析/决策类：`Listing 健康度分析`、`搜索词分析`、`销售趋势深度分析`、`库龄分析与滞销预警`、`广告优化建议`、`Listing SEO 优化`、`发货仓库推荐`、`库存跨仓调拨`、`AI 创建采购计划`、`AI 自动生成买家消息回复`。
- 这些工具依赖的后端（listing monitor、销售聚合、库龄计算、1688 报价）部分尚未真实存在，故只能返回演示估算。

**推荐方案：分层接入（Tiering）**
1. **确认生效实现**：先判定当前 Agent 实际走 `ErpTools`（LangChain4j）还是旧 `ErpToolExecutor`，统一演示标识与数据接入点，避免双轨漂移。
2. **Tier A（数据已在库，立即可接）**：
   - 销售额趋势 / 销售趋势深度分析 → 复用 `amz-service-order` / `amz-service-report` 既有聚合。
   - 库龄分析与滞销预警 → 由库存表 + 入库/订单日期计算（无需新后端）。
   - 库存健康度 / 搜索词分析 / 广告优化 → 由现有广告、库存数据聚合。
   - 采购计划 → 接 2（1688 真实报价）后升级为真实估算；在 2 完成前保留确定性占位并标注。
3. **Tier B（需新后端，建服务或保留标识）**：
   - `Listing 健康度分析` / `Listing SEO 优化` → 需 listing monitor（标题/五点/图片/搜索词评分），建议新建轻量分析服务或对接现有 SP-API `getListingQuality`）)。
   - `竞品价格监控`（非 19–28 但同类）→ 接 `KeepaRealClient`（已存在）。
   - `买家消息回复` → 由 LLM 生成，非数据缺口，可直接标为"AI 生成"而非"演示估算"。
4. **标识策略**：真实数据接通前保留 `[演示估算]` 前缀；接通后**移除**前缀并补真实返回结构。严禁将 mock 当作真实业务数据呈现。

**验收**：工具 19–28 中 Tier A 全部返回真实数据（无 `[演示估算]`），Tier B 要么接通要么显式标注缺失后端；双轨工具实现收敛为一。

---

### 2.2 1688 开放平台真实对接（中）

**现状与根因**
- `amz-service-procurement/.../client/Alibaba1688RealClient.java` 为骨架：4 个方法（`createOrder`/`queryOrderStatus`/`queryTrackingNo`/`closeOrder`）均 `log.warn` + 返回 `1688_MOCK_` 占位，无 HMAC-SHA1 签名、无 `access_token` 刷新、无真实 HTTP。Javadoc 自述"待实现项"。
- 接口 `Alibaba1688Client` 与 `Alibaba1688MockClient` 已存在，切换机制（`@Profile("!mock")`）就绪。

**推荐方案**
1. 实现 **HMAC-SHA1 签名器**（1688 开放平台规范：`sign = md5(appKey+timestamp+format+...)` 类签名，按官方文档精确实现），放 `AbstractPlatformClient` 同级或 1688 专用 signer。
2. 实现 **`access_token` 管理器**：`aliexpress.postponeToken` / `alibaba.user.get` 类授权 + 刷新，token 缓存（Redis）。
3. 真实调用：`alibaba.trade.create`、`alibaba.trade.get`、`alibaba.trade.close`、物流单号查询，错误码映射为领域异常。
4. 复用里程碑 A 的**共享弹性 HTTP 客户端**（见 2.5）替代 `new RestTemplate()`。
5. 凭证走 **`PlatformCredentialService`**（见 2.6），按 `shopId` 取 1688 凭证，支持多店铺。

**验收**：`!mock` 环境下 `createOrder` 调用真实 1688 并返回真实订单号（非 `1688_MOCK_`）；单测覆盖签名与 token 刷新；无沙箱凭证时诚实降级并告警。

---

### 2.3 汇率静态表→实时（中，快赢）

**现状与根因**
- `amz-service-multiplatform/.../finance/PlatformCurrencyConverter.java` 仅靠 `@Value("#{${platform.exchange-rates}}")` 静态 yml，无实时刷新。
- 仓库内已存在**三套**汇率实现，应合并：
  - `amz-service-finance/.../finance/CurrencyConverter.java`：已接 `exchangerate.host` 实时 + 每小时刷新 + yml 兜底（实现良好）。
  - `amz-service-product/.../service/ExchangeRateService.java`：接 `open.er-api.com`。
  - 多平台 `PlatformCurrencyConverter`：纯静态。

**推荐方案**
1. 抽取 **`amz-common` 共享 `ExchangeRateService`**：实时拉取（优先 ECB 免费免 key 或 `exchangerate.host`）+ 定时刷新 + 内存缓存 + yml 兜底，`BigDecimal` 精度。
2. `amz-service-multiplatform` 的 `PlatformCurrencyConverter` 改为依赖共享服务（或直接复用 `CurrencyConverter` 经 Feign 调 finance 模块——但多平台模块独立运行更宜本地共享 bean）。
3. 删除重复实现，统一口径（语义：1 原币 = rate CNY）。

**验收**：多平台模块汇率随实时源更新，未知币种告警降级；仓库仅保留一套汇率实现；补充 `PlatformCurrencyConverter` 实时拉取单测。

---

### 2.4 多平台签名按官方校准（中）

**现状与根因**
- `AbstractPlatformClient` 提供 `md5Hex` / `hmacSha256Hex`。`TemuRealClient.signTemu`（`MD5(secret+排序kv+secret)` 大写）、`SheinRealClient.signShein`（同）、`TikTokRealClient`（HMAC-SHA256，appSecret 为 key）均为 best-effort 近似。
- 架构已合理（公共基类 + 各平台子类 sign 方法），问题在**字段顺序/时间戳/nonce/charset/签名拼装**是否符合各平台最新规范。

**推荐方案**
1. 逐平台获取开放平台官方签名文档 + 沙箱凭证。
2. 为每个平台补 **沙箱联调集成测试**（请求/响应录制对照），校准：
   - Temu：`sign = MD5(appKey+timestamp+format+... )`（严格按 Seller Center Open API 文档）。
   - Shein：MD5 拼装字段集与排序规则。
   - TikTok Shop：HMAC-SHA256 的 `sign_method`、`timestamp`、`shop_id`/`access_token` 位置。
3. 不在无凭证时改动生产逻辑；校准后以测试固化。

**验收**：三平台沙箱测试通过，签名与官方示例一致；无沙箱凭证时保持 best-effort 并显式标注"未校准"。

---

### 2.5 HTTP 重试/熔断/超时（中，基础设施）

**现状与根因**
- `AbstractPlatformClient.restTemplate = new RestTemplate()` 无连接/读取超时、无重试、无熔断；`Alibaba1688RealClient` 同样裸 `RestTemplate`。
- SP-API 调用无 429 退避与节流指标。

**推荐方案**
1. 在 `amz-common` 提供 **共享弹性 HTTP 客户端 Bean**（基于 `RestTemplate` 或 `WebClient`）：
   - 连接超时 3s / 读取超时 10s（可按外部 API 调优）。
   - Spring Retry：对幂等 GET 指数退避（如 3 次，base 500ms）。
   - Resilience4j 熔断：失败率阈值 + 半开探测，熔断时走 fallback。
2. 所有 `RealClient`（多平台 ×3、1688、Keepa、Kingdee、Messaging、Advertising）统一改用该 Bean，删除 `new RestTemplate()`。
3. SP-API 专项：读取 `Retry-After` 做 429 退避；用 Micrometer 暴露 `spapi.throttle.count` 等指标。

**验收**：外部 API 抖动下自动重试/熔断且不雪崩；SP-API 429 有退避与指标；单测覆盖重试与熔断触发。

---

### 2.6 凭证管理（中，安全 + 多租户）

**现状与根因**
- 约 20+ 处 `@Value` 明文注入：DeepSeek `api_key`(×4)、OSS `accessKeyId/secret`、`spring.redis.password`、SP-API `lwa.access-token`、`keepa.api-key`、`kingdee.app-secret`、`platform.tiktok/temu/shein.{app-key,app-secret,access-token}`、`alibaba.{app-key,app-secret}` 等。
- `platform.*` 为**全局** `@Value`，无法支撑多店铺各自凭证（多租户阻断项，直接影响 2/4/7 的多店场景）。

**推荐方案（分阶段）**
- **短期（快赢）**：密钥从 yml 明文迁移到**环境变量 / Spring Cloud Config（`{cipher}` 加密）**；确保本地含密 yml 被 `.gitignore` 覆盖，禁止明文提交。
- **中期（密管）**：接入密钥管理器（Vault / 阿里 KMS / AWS Secrets Manager）或 Nacos 配置加密，提供 `SecretResolver` Bean 统一解析。
- **多租户建模**：将各平台凭证按 `shopId` 存入数据库（新增 `shop_platform_credential` 表，复用 SP-API 已有的 `ShopCredentialStore` 思路），运行时由 **`PlatformCredentialService`** 按店铺加载；`RealClient` 不再 `@Value` 全局凭证，改为注入 `PlatformCredentialService.get(shopId)`。
- 日志脱敏已具备（`AbstractPlatformClient.mask`），保持。

**验收**：无任何密钥明文出现在 yml/代码中；多店铺可各自配置平台凭证；`RealClient` 经 `PlatformCredentialService` 取密。

---

### 2.7 Feign 端点 shopId 越权收口（低-中，安全）

**现状与根因**
- `UserContext.isShopAllowed` 已覆盖 5 处：`OrderController:190`、`SpapiController:77`、`FeedsController:47/70`、`ProductController:89`、`ErpToolExecutor:95`。
- `FeignAuthRelayConfig`（前期阶段）已让 Feign 调用携带 JWT，下游拦截器重建 `UserContext`。但 **16 个 Feign 目标端点的服务端 shopId 校验未统一审计**——部分接收 `shopId` 或操作店铺数据的控制器可能未校验。

**推荐方案**
1. 编写**静态审计脚本**（类前期 `mapping_conflict_check.py`）：枚举 16 个 `@FeignClient` 的目标路径 → 对应控制器方法 → 是否含 `isShopAllowed` / `@ShopScoped` / `ShopIdGuardAspect`。
2. 对缺口控制器补齐校验（优先含 `shopId` 入参或店铺数据写操作的端点）。
3. 统一收口：扩展 `ShopIdGuardAspect` 或新增基类 helper `assertShopAllowed(shopId)`，减少逐处拷贝。
4. 白名单机制（前期 `/internal/**`）保持不变，内部端点不强制。

**验收**：脚本输出 0 个未校验的店铺敏感端点；新增统一收口 helper 被复用；回归测试覆盖跨店访问被拒。

---

### 2.8 P0/P1 回归测试补齐（中，质量）

**现状与根因**：已存在 21 个测试类（含 `AwsSigV4SignerTest`、`CurrencyConverterTest`、`MultiplatformServiceImplTest` 等），但缺本次 P0/P1 修复的针对性回归。

**推荐方案（按改动补齐）**
1. **ReDoS 回归**：`OrderAuditServiceImpl.safeRegexMatch(177–187)` 补测试——超长/畸形正则被拒绝或安全匹配，恶意回溯模式不卡死。
2. **Feign 鉴权中继**：`FeignAuthRelayConfig` 单测——有请求上下文时中继 token+shopId；无上下文且 `UserContext` 有 userId 时按 `JwtUtil` 重塑；fallback 行为正确。
3. **SP-API 签名**：扩展 `AwsSigV4SignerTest` 覆盖非 200 抛异常路径。
4. **汇率实时**：`PlatformCurrencyConverter`/`ExchangeRateService` 实时拉取 + 失败的缓存保留。
5. **1688 / 多平台签名**：真实实现后补签名与沙箱测试（见 2.2/2.4）。

**验收**：上述每类至少 1 个回归用例；CI 中 `mvn test` 全绿；覆盖率不下降。

---

## 3. 分阶段路线图

```
里程碑 A（基础设施，1–1.5 周）  里程碑 B（数据准确，3–5 天）  里程碑 C（真实后端，1.5–2.5 周）  里程碑 D（收敛+质量，1 周）
┌─────────────────────┐  ┌─────────────────────┐  ┌─────────────────────┐  ┌─────────────────────┐
│ 5 弹性 HTTP 客户端  │  │ 3 汇率合并为共享服务│  │ 2 1688 真实对接     │  │ 7 Feign shopId 审计  │
│ 6a 密钥去明文+多租户│  │ 4 三平台签名校准    │  │ 1 工具19-28 分层接入│  │ 8 回归测试补齐       │
│    凭证建模         │  │                     │  │                     │  │   + ErpAgentService清理│
└─────────────────────┘  └─────────────────────┘  └─────────────────────┘  └─────────────────────┘
   为 2/4/7 铺路             独立快赢                依赖 5/6                依赖 1-7 各改动
   ✅ 已完成                ✅ 已完成                ✅ 已完成                ✅ 已完成
```

**依赖关系**：A(5,6) → C(2,1)；A(5) → B(4 健壮性)；B(3) 独立；D(7,8) 在 1–7 改动后收口。

**每阶段执行纪律**（沿用既有迭代约定）：完成一个阶段即 `clean compile` + 相关单测 + 静态审计（如映射冲突/Feign 收口脚本），review 通过后再进入下一阶段；禁止将 mock 当真实、禁止演示标识在未接通时移除。

---

## 4. 跨项架构建议（一次到位，避免重复）

1. **共享弹性 HTTP 客户端**（`amz-common`）：5、2、4、6 的 RealClient 全部复用，消灭 `new RestTemplate()`。
2. **共享汇率服务**（`amz-common`）：合并 finance / product / multiplatform 三套，3 直接受益。
3. **统一凭证服务 `PlatformCredentialService`**（`amz-common` + DB 表）：6 的密管与多租户、2/4 的多店铺凭证统一出口。
4. **统一 shopId 收口 helper**（`amz-common`）：7 的 Feign 端点审计与补齐的标准件。

---

## 5. 建议启动项

按"低风险高价值 + 为后续铺路"原则，**建议从里程碑 A 的 5（弹性 HTTP 客户端）与 3（汇率合并）并行启动**：
- 3 是独立快赢，半天~1 天即可合并汇率实现并补测试；
- 5 是后续所有外部对接的底座，先做可避免 2/4 重复造轮子。

如需我立即开工，请确认启动顺序（建议：先 3 后 5，再 6a/2/4/1，最后 7/8），我将按"实现→review→下一阶段"的节奏逐段交付。
