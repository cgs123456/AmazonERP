# AmazonERP 生产化升级执行矩阵

- 文档日期：2026-09-26
- 仓库：`C:\Users\Administrator\Desktop\AmazonERP`
- 分支：`codex/api-ready-connectors`
- 基线提交：`aee00c6`
- 当前工作区状态：**不是干净仓库**，存在大量未提交修改、未跟踪文件和暂存删除；本矩阵不代表这些改动已合并或已发布。
- 本文用途：把“可生产部署”拆成可实施、可验收、可计价的工程矩阵；不修改生产代码，不替代安全评审、财务评审或 Amazon 审核。
- 状态口径：`已实现`、`部分实现`、`未实现`、`需真实凭证`、`需生产运行证据`。

## 0. 先纠正错误前提：有 API 不等于直接可用

“有 API 就可以直接使用”这句话不准确，不能作为采购、工期或上线承诺。

正确表述：

> 当前代码已经具备对接能力（未联调）；拿到合规 API 访问权后，正常接入不应需要重写整体架构，但仍必须完成应用审批、卖家授权、角色/市场/RDT 权限配置、沙箱联调和生产验收。联调通过前不能称为“已接通”，更不能称为“可直接生产部署”。

仅提供凭证不足以接通，至少还受以下变量约束：

1. **Amazon 应用审批**：开发者档案、应用审核、用途和数据访问范围；沙箱和生产是不同证据阶段。
2. **卖家授权**：卖家必须授权应用，并授予正确角色；店铺、市场和国家站点必须匹配。
3. **区域与端点**：NA/EU/FE 端点、marketplaceId、AWS region、货币和时区必须一致。
4. **敏感数据权限**：订单买家信息/地址需要 RDT；部分数据还需要 SPDS 或额外审批，普通 LWA access token 不能替代。
5. **配额与限流**：官方 usage plan 按 operation 和卖家维度生效；429、`Retry-After`、分页和 SQS 重复/乱序必须实测。
6. **数据保护**：PII 加密、掩码、访问审计、保留/删除、密钥轮换和 DPP 合规必须落地。
7. **业务事实正确性**：订单、库存、结算、退款、费用、FIFO 成本和总账必须能对账；接口成功不等于经营数据正确。
8. **生产运行条件**：HA、备份/PITR、DR、SLO、告警、值班、发布回滚和安全事件响应必须经过演练。

当前连接器事实：

- `ConnectorRegistry` 登记 **90 条已实现 operation / 0 条未实现**：其中 26 条来自既有类型化客户端，64 条来自统一 `SpApiOperationCatalog` + `SpApiOperationClient`；Notifications、Listings Items、Product Pricing、Catalog Items、FBA Inbound 均进入离线 API-Ready 范围，真实联调仍待凭证。
- 当前证据声明为：A1=E2，A2/A3/A4/A6=E1，A5=E0，A7/A8=E3。
- 因此当前计算结果为 `apiReady=false`、`reachable=false`，对外只能显示 **“具备对接能力（未联调）”**。
- `SpApiGateway` 已支持显式 `LWA` / `RDT` token source；普通调用默认 LWA，受限调用只能显式使用 RDT，RDT 失败时业务请求数为 0。
- `SpApiRequestFactory` 已接受显式 access token 并注入 `x-amz-access-token`；RDT 缓存、失效和 Outbox 重放 token source 恢复已离线实现，但尚无真实 Amazon 联调证据。
- 真实凭证不能伪造 E4/E5。E4 至少需要真实或 Amazon 沙箱请求记录；E5 需要生产环境持续运行、告警、对账和事故/恢复证据。

## 1. 可复现基线与证据边界

### 1.1 代码规模基线

| 指标 | 2026-09-26 实测 |
|---|---:|
| `amz-service` 子模块目录 | 15 |
| Java 文件（排除 `target`） | 875 |
| `*Test.java` 文件（排除 `target`） | 161 |
| `*Controller.java` 文件 | 56 |
| `*Scheduler.java` 文件 | 11 |
| `k8s/**/*.yaml|yml` | 31 |
| `docker-compose.yml` services | 31 |

### 1.2 已验证的离线基线

前一轮全量记录：

- 后端 19/19 Maven 模块构建成功，161 份测试报告，1053 tests，0 failures/errors，2 skipped（真实凭证测试）。
- 前端 19 个测试文件、144 tests，0 failures；Vite production build 成功。
- 这些结果证明“离线工程基线可构建、可回归”，**不证明真实 Amazon 连通性**。

本轮重新执行：

```powershell
python tools\synthetic-data\verify.py --tier ci --dataset tools\synthetic-data\out\ci
python tools\synthetic-data\verify.py --tier demo --dataset tools\synthetic-data\out\demo
python tools\synthetic-data\snapshot_schema.py --check
mvn -o test -fae
```

结果：

- ci 113 张表 / 25484 行，demo 113 张表 / 225734 行；结构、引用、标记、确定性、快照均 PASS。
- 两次运行 229 个文件 byte-identical。
- schema snapshot 与仓库 DDL 一致：113 张表、14 个数据库（drift=0，alter_table=22，add_column=14）。
- 引用列与父键 DDL 类型不一致已收敛为 0：4 处加宽为 BIGINT（第 4 处 `amz_order.coupon_id` 是补齐门禁后才暴露的），4 处保留 VARCHAR 并重命名消歧（存的是平台/外部订单号，改类型会使真实结算数据无法落库）；回滚见 docs/superpowers/runbooks/reference-key-convergence-rollback.md。
- 门禁补漏：`verify.py` 的 `FK_TARGETS` 原缺 `coupon_id`/`platform_account_id`（生成器 14 个引用池，门禁只认 13 个），此前的 `id types OK (0)` 存在假阴性；已补齐并重跑，当即暴露第 4 处类型缺陷与 1 处假引用。
- 订单身份收敛（P0-40 关闭）：新增 V4 迁移 `V4__order_shop_scoped_identity.sql`，把 `amz_order` 的单列唯一键 `uk_amazon_order(amazon_order_id)` 换成 `uk_shop_market_order(shop_id, marketplace_id, amazon_order_id)`；`OrderServiceImpl.syncAmazonOrder` 的幂等查重同步带上 shop/marketplace（缺 shopId 时只告警，绝不退化成全局判重），消除“跨店同号订单被判重复而静默丢单”。残留：`marketplace_id` 可空，MySQL 唯一索引里 NULL 不参与去重，彻底解决要按规范 §3.1 把平台订单与本地购物车订单拆表。
- 订单明细表 V5：新增 `amz_order_item`（订单行，唯一键 `uk_order_item(shop_id, amazon_order_id, amazon_order_item_id)`）。此前 `amz_order` 只有一组单商品列、结构上装不下多商品订单，而同库 `amz_profit_report` 已按 `(shop_id, amazon_order_id, sku)` 建模、多平台侧用 `items_json` 变通不可聚合。当前状态：写入链路已补齐（SP-API `getOrderItems` → 保存消息 → `OrderConsumer` 解析 → `amz_order_item` 先查后写），详见 runbook 7.24.2；残留：`product_id` 仍为 NULL（ProductClient 无 SKU 反查），`amz_order` 的单商品列仍是重复事实源。
- 门禁规模修复：`verify.py` 的校验规模改为“显式参数 > `--dataset` manifest > tier”，并新增 `--max-orders=20000` 上限；此前默认固定 200 单/1 店铺，大数据集只做 hash、不做事实行级校验，属于假阴性。代价：默认（tier ci）行级规模从 200 单升到 1000 单，单次耗时上升。另：`snapshot_schema.py` / `verify.py` / `generate.py` **未被 `.github/workflows/ci.yml` 调用**，合成数据门禁目前只在本机手动执行，是否接入 CI 待决策。
- Flyway 基线契约：`FlywayBaselineContractTest` 期望表数由 112 改为 113（V5 新增表触发，说明门禁有效，不是放宽门禁）。
- 合成数据只用于开发、回归和演练；不得进入生产业务库，也不得被当作真实经营数据。

### 1.3 证据等级

| 等级 | 含义 | 是否需要真实 API |
|---|---|---|
| E0 | 无证据或仅口头声明 | 否 |
| E1 | 仓库内自证、单元测试或代码检查 | 否 |
| E2 | 桩回放、契约测试、故障注入 | 否 |
| E3 | 官方 OpenAPI/文档快照 + hash 锁定 | 否 |
| E4 | Amazon 沙箱或真实卖家请求/响应记录 | 是 |
| E5 | 生产持续运行、SLO、对账、告警和恢复演练证据 | 是 |

重要约束：同一能力有多项证据时取最弱项；局部 E3 不能抬高整体 E4。RDT、PII、订单、库存、财务和写操作在 E4 前不得标记“已接通”。

## 2. 生产升级执行矩阵

说明：

- `验收证据` 必须是机器可复现或有时间戳、请求 ID、脱敏后的外部证据，不能只写“已测试”。
- `成本/风险` 是相对量级，不是报价；真实成本取决于部署形态、店铺量、订单量、合规要求和团队值班能力。
- `需真实凭证` 指达到 E4/E5 是否必须；离线实现本身通常不需要真实凭证。

### 2.1 上线阻断项（REL）

| ID | 优先级 | 当前证据 | 必须实现 | 验收证据 | 依赖、成本与风险 | 需真实凭证 |
|---|---|---|---|---|---|---|
| REL-01 生产模式与 mock fail-closed | P0 | 已有 `ProductionProfileGuard` 拒绝 `prod,mock` 混用；`DataSourceValidator` 对生产占位密码 fail-closed；但部署清单仍需全量证明不会默认落入 mock | 所有服务生产启动必须显式 `prod`；mock bean 在生产不可装配；无 profile、未知 profile、混合 profile 均拒绝；配置契约覆盖 Compose、K8s、Helm/后续发布方式 | 启动负向测试；Compose/K8s 配置扫描；prod 启动烟测；证据记录 profile、镜像 digest、配置 hash | 低到中；误拦截会导致部署失败，必须提供明确 runbook，不能靠放宽守卫解决 | 否 |
| REL-02 内部服务访问边界 | P0 | `/internal/**` 已改为服务令牌校验并支持白名单；仍需确认 mTLS、网络策略、端口暴露和服务身份轮换 | 内部接口只接受 mTLS + 短时服务 JWT；普通网关请求不可达；消息服务不暴露宿主机；服务身份按调用方最小授权 | 未带/错带/过期/跨服务令牌均拒绝；无 mTLS 拒绝；NetworkPolicy 和端口扫描；审计日志含调用方与目标 | 中到高；mTLS 证书签发、轮换和本地开发体验有运维成本 | 否 |
| REL-03 租户、店铺和字段权限 fail-closed | P0 | 已增加严格店铺判断、切面和部分跨店铺测试；但网关主要校验 `shopId` header，body/query/path 解析、类级注解、资源归属和服务间传播仍需全量证明 | 所有外部写入口必须有服务端资源归属校验；缺失 tenant/shop/role 上下文拒绝；字段权限服务不可用时拒绝敏感字段；SQL/缓存/搜索均带 tenant/shop 条件 | 跨租户/跨店铺负向测试；IDOR/BOLA 测试；SQL 拦截或静态扫描；字段权限故障注入；搜索/缓存泄漏测试 | 高；需要统一上下文、数据模型迁移和回归面广，漏掉一个旧接口就可能泄漏 | 否 |
| REL-04 用户注册、登录与会话 | P0 | 用户模型/DDL 漂移、短信通道和 refresh token 轮换仍需收口；当前不能把本地登录成功当成生产能力 | 实体与迁移单一事实源；真实短信或明确未配置失败；access/refresh token 轮换、撤销、设备/会话审计；密码策略和登录风控 | 全新建库后注册/登录/刷新/撤销测试；短信供应商沙箱或生产 smoke；并发刷新和重放测试 | 中；短信、身份提供商和合规地区会改变实现与费用 | 短信/身份供应商是 |
| REL-05 SSE、WebSocket 与 AI 工具边界 | P0 | SSE 已从 JWT 上下文取用户并要求认证，工具角色缺失时拒绝；仍需多实例、撤销、首帧鉴权和写操作审批的端到端证明 | 身份、tenant、shop、role、trace 一起传播；禁止查询参数覆盖身份；高风险写工具默认干跑/审批；连接建立即鉴权并支持撤销 | 多实例 SSE/WS 测试；角色/店铺越权负向测试；token 撤销测试；写工具审批与回滚测试 | 中；异步上下文和前端重连容易出现隐性越权 | 否 |
| REL-06 数据库迁移与 schema 单一事实源 | P0 | Flyway 基线和部署 Job 已有建设；合成数据验证发现 7 个引用类型不一致；全新建库与存量升级仍需同一门禁 | 所有库纳入唯一迁移入口；启动前检查 schema 版本；禁止手工建表分叉；引用类型、字符集、排序规则、时区和唯一键统一 | 空库迁移、旧库升级、重复执行、失败回滚测试；DDL 快照 hash；7 个类型问题全部清零 | 中到高；存量数据迁移和锁表窗口是主要风险 | 否 |
| REL-07 凭证存储、隔离与轮换 | P0 | 已有数据库凭证存储和密文路径；仍需确认多副本一致、KMS/信封加密、按店铺隔离、轮换和吊销 | 凭证按 tenant/shop/marketplace 隔离；KMS/Secret Manager 托管；加密密钥与数据密钥分离；轮换不重启；访问审计；不得写日志 | 多实例并发读取测试；跨店串用负向测试；密钥轮换/吊销演练；日志与堆转储扫描 | 高；KMS、密钥运维和合规审计是持续成本 | 生产环境需要 KMS 和真实凭证 |
| REL-08 PII 分类、加密、保留与删除 | P0 | RDT 离线底座已实施但未联调；PII vault/保留策略尚无端到端证据 | 订单买家信息、地址、消息、邮箱、电话分类；字段级加密/掩码；最小权限；保留/删除任务；备份同步删除策略；审计 | PII 数据地图；访问审计；掩码测试；删除请求端到端；备份恢复后再次删除测试 | 高；涉及 DPP、跨境、法务和备份系统，设计错误代价高 | 沙箱可验证字段，生产需真实授权 |
| REL-09 Webhook/事件入口安全 | P0 | 多平台 webhook 当前主要记录日志；未发现统一验签；去重键可能只有全局 eventId | 每平台验签和时间窗；事件唯一键至少 `(platform, shop_id, event_id)`；归属不确定拒绝；重放安全；原始事件加密留存 | 伪造签名、过期时间戳、重复事件、乱序事件、跨店事件测试；重放审计 | 中到高；各平台签名规范不同，必须逐平台实现 | 各平台沙箱/真实回调是 |
| REL-10 基础设施密码与公网暴露 | P0 | 已有生产占位密码校验；仍需验证 Compose/K8s 的实际 Secret、端口、TLS 和默认账号 | 删除默认账号/密码；基础设施只走内网或 Private Endpoint；TLS 全覆盖；管理端口限制；Secret 不进入镜像、Git、日志或前端 | Secret 扫描；端口扫描；TLS 配置检查；未授权访问测试；镜像层扫描 | 中；网络改造会影响部署拓扑和本地开发 | 生产环境是 |
| REL-11 备份、PITR 与灾难恢复 | P0 | 有基础设施组件，不代表 RPO/RTO 已达标 | MySQL PITR、Redis/MQ 持久化策略、Mongo/ES 备份、对象存储版本；跨故障域副本；恢复脚本和演练记录 | 从备份恢复订单/库存/财务；记录实际 RPO/RTO；单节点/可用区故障演练；恢复后对账 | 高；存储、跨区和演练人工成本持续存在 | 生产环境是 |
REL-12 财务事实与 DDL 引用完整性 | P0 | 已完成 4 处加宽（`amz_order.product_id`、`amz_order.user_id`、`amz_order.coupon_id`、`amz_ad_search_term.keyword_id`）和 4 处重命名（`amz_payment_collection`/`amz_settlement_detail`/`amz_shipment_routing` 的 `order_id→amazon_order_id`；`amz_platform_message.order_id→platform_order_no`）；`amz_order` 唯一键已由 V4 收敛为 `uk_shop_market_order(shop_id, marketplace_id, amazon_order_id)`，已闭环；残留为 `marketplace_id` 可空导致 NULL 不去重 | 内部主键统一 BIGINT；存外部订单号的列保留 VARCHAR 并重命名；订单唯一键包含 shop/marketplace；费用、退款、结算、支付、凭证可追溯；金额使用定点小数；币种和汇率显式 | 类型问题清零；财务试算平衡；订单/结算/支付三方对账；跨店同业务行不丢失；差异可建案关闭 | 高；一旦生产数据写入后再改主键/引用，迁移成本显著上升 | 否，生产对账是 |
| REL-13 调度任务分布式互斥 fail-closed | P0 | 代码级已修复：`DistributedJobLock` 默认 fail-closed；`runIdempotentWithLock(...)` 仅供已证明幂等/只读任务；现有 14 个生产调用点全部保持默认路径；9 个单测通过。真实 Redis 双实例、租期过期、崩溃接管和长任务跨租期尚未回放 | Redis 不可用时写任务不得执行；抢锁结果不确定不得执行业务；指标区分 `redis_template_missing`、`redis_error`、`lock_held` 和 `release_failed`；任务超租期必须有告警、续租或幂等保护 | 真实 Redis + 双实例同窗触发只允许一个业务副作用；断开 Redis 时写任务执行数为 0；崩溃后租期到期可接管；释放失败和降级事件可告警；长任务跨租期压力测试 | 中到高：Redis 故障会阻断全部现有调度；需要告警、runbook、锁续租/任务超时设计；无 Redis 的离线演示必须显式选择 mock/单实例方案，不得默认放开 | 否 |

### 2.2 SP-API 连接器闭环（SP）

| ID | 优先级 | 当前证据 | 必须实现 | 验收证据 | 依赖、成本与风险 | 需真实凭证 |
|---|---|---|---|---|---|---|
| SP-01 RDT / Tokens API | P0，离线 API-Ready 已实施；真实联调待凭证 | 官方模型已核实：`POST /tokens/2021-03-01/restrictedDataToken`，usage plan 1 req/s、burst 10；请求 `restrictedResources` 1–50 项；响应 `restrictedDataToken`、`expiresIn`；模型 sha256 `3cd09ae7f218c83f32536a894cb8c42f2191c94b9c27f6bcf0a164442089b061` | 已新增 `TokensClient`、按 `shopId + marketplaceId + resource set` 隔离的内存 token manager、显式 token source、过期前刷新、资源校验和 Outbox 重放恢复；不得落库、日志或响应 | 官方模型快照 + hash；单测覆盖响应缺失/畸形/过期/隔离/上限；桩回放断言 `x-amz-access-token` 为 RDT；401/403 只失效正确 token；无 LWA 回退；真实 RDT 请求仍待沙箱/生产证据 | 中；RDT 短时且权限按资源，缓存键设计错误会导致跨店串用或频繁申请 | E4/E5 是；E0–E3 否 |
| SP-02 受限 Orders PII | P0 | RDT 离线底座和 Orders 受限方法已实现；`OrderSyncScheduler` 的 PII 开关默认关闭；尚无真实权限/沙箱证据 | 对 `getOrders` 使用 `buyerInfo + shippingAddress` 的 RDT；对 `getOrderItems` 使用 `buyerInfo`；普通订单字段与 PII 字段分离；受限失败必须失败关闭 | 沙箱/生产请求日志脱敏后证明资源与方法匹配；无 RDT/错误 RDT 拒绝；重放重新申请；订单 PII 不进入明文 Outbox | 高；RDT 失效、重试、分页和 Outbox 重放相互影响 | 是 |
| SP-03 Notifications（管理 API 与事件消费） | P1 | Notifications 管理 API 已离线 API-Ready（10 条 operation，官方快照、路径/限流契约、统一控制器）；SQS/SNS 消费、验签、去重、DLQ 与映射未实现，订单和授权变化仍靠轮询 | 已具备订阅管理 API；仍需 SQS/SNS 消费、签名/来源校验、去重、乱序、死信、重放、通知到订单/授权/Listing 的映射 | 官方模型快照；重复/乱序/丢包/毒消息测试；沙箱订阅和真实消息证据；消费延迟和 DLQ 指标 | 中到高；需要 AWS 账号、SQS/SNS 权限和消息治理；不是只加一个客户端 | 沙箱/生产是 |
| SP-04 Listings Items API | P1 | 5 条官方 operation 已离线 API-Ready（统一客户端、路径/限流/控制器契约）；真实读写、类目校验和版本冲突待凭证 | 官方 Listings Items 读写；SKU/ASIN/类目属性校验；版本冲突；提交状态和错误逐项可查询；与 Feed 路径明确分工 | OpenAPI 快照 + 路径契约；沙箱创建/更新/查询；类目校验错误可回放；版本冲突测试 | 高；类目属性动态、站点差异和写操作风险高 | 是 |
| SP-05 Product Pricing | P1 | 2 条官方 operation 已离线 API-Ready；真实价格查询、分页和批次行为待凭证 | 官方 Pricing 客户端；分页、batch、marketplace 隔离、限流、价格历史和异常检测 | 官方快照；沙箱读取；价格快照与 Listing/订单对账；429/分页测试 | 中；批次上限、站点差异和缓存一致性是主要风险 | 是 |
| SP-06 Catalog Items | P1 | 2 条官方 operation 已离线 API-Ready；真实 ASIN 查询、搜索和 product type 约束待凭证 | Catalog Items 查询、搜索、类目/属性约束；与商品主数据和 Listing 校验共享事实源 | 官方快照；沙箱 ASIN 查询；未知类目拒绝；占位 product type 清零 | 中到高；需要处理字段爆炸、分页、搜索配额和多站点语言 | 是 |
| SP-07 FBA Inbound | P1 | 45 条官方 operation 已离线 API-Ready（统一目录、客户端、限流契约）；真实入库计划状态机待凭证 | Inbound plan、shipment、box/item、transportation、状态同步；幂等和失败补偿 | 官方快照；沙箱全流程；取消/修改/部分成功测试；库存与计划对账 | 高；跨系统状态机长、写操作多、失败恢复复杂 | 是 |
| SP-08 已实现 90 个 operation 的生产化 | P0/P1 | 能力台账已与 `com/amz/client` 真实 operationId 调用点双向一致；90 条均进入离线 API-Ready（26 条既有类型化客户端 + 64 条统一 operation 目录）；仍缺真实联调 | 统一 user-agent、region/marketplace 单一事实源、按店限流、幂等、重试、429、分页、错误分类、Outbox/DLQ/显式重放；Sellers `/account` 决定是否纳入范围 | 官方模型双向契约；每 operation 至少一次桩回放；沙箱请求 ID/响应头；429/5xx/401/403 故障注入；分页边界测试 | 中；旧客户端与新统一层并存会产生两套行为 | E4/E5 是 |
| SP-09 非 SP-API 连接器统一治理 | P1 | Keepa、17TRACK、1688、Kingdee、Temu、TikTok、Shein 有真实客户端，但未全部接入共享 Outbox、限流、验签和联调证据 | 统一凭证模型、HTTP 客户端、超时、重试、限流、熔断、审计、Outbox/Inbox、验签和状态映射；不支持的能力从 UI/README 撤回 | 每个连接器契约测试；沙箱请求证据；超时/重试/重复回调测试；能力清单与实现双向校验 | 高；第三方平台签名、分页和状态语义不同，不能假定与 SP-API 同构 | 是 |
| SP-10 多平台 webhook 修复 | P0 | 端点存在但处理主要写日志；未发现统一签名验证；事件去重可能只有全局 `eventId`；`shopId` 缺失时可能反查错误店铺 | 逐平台验签；`(platform, shop_id, event_id)` 唯一；shop 归属不确定即拒绝；事件落库后可重放并产生真实业务动作 | 伪造/重放/跨店/乱序测试；处理结果和 DLQ 可查询；至少一个平台端到端沙箱回调 | 中到高；需要各平台开发者账号和公网回调基础设施 | 是 |

### 2.3 业务事实模型与经营闭环（BIZ）

| ID | 优先级 | 当前证据 | 必须实现 | 验收证据 | 依赖、成本与风险 | 需真实凭证 |
|---|---|---|---|---|---|---|
| BIZ-01 订单主数据与状态机 | P0 | 现有表主要是订单头；唯一键和行、费用、退款、事件模型不完整；状态变化可能被去重阻止 | 订单头/行/地址/费用/退款/事件分层；唯一键含 shop/marketplace；增量游标、状态机、迟到事件、取消/退货；通知优先、轮询兜底、报表对账 | 官方字段契约；订单创建/变更/取消/退款回放；重复和乱序测试；与 Amazon 报表逐单对账 | 高；状态机和唯一键变更会影响库存、财务、客服全部下游 | 真实订单字段需是 |
| BIZ-02 库存台账与原子扣减 | P0 | FBA FIFO 已改为 MySQL 条件更新并检查受影响行数，代码级 100 轮内存并发回放通过；真实 MySQL 并发回放待执行。整体库存仍主要是 FBA 快照；平均销量未完整填充；null 可能被当 0；upsert 先查后写 | 外部快照层 + 内部库存台账层；入库/出库/预占/释放/调拨/退货/盘点；条件更新或行锁；批次/库位；可售与补货分离 | 真实 MySQL 并发超卖集成测试待执行；重复事件不重复扣减；故障注入后对账；FBA 快照与内部台账差异可追踪 | 高；并发正确性和历史库存迁移风险最大 | 沙箱可部分验证，生产对账是 |
| BIZ-03 采购、供应商与三单匹配 | P1 | 1688 客户端自述未校准；token 未按店隔离；FBA FIFO 已代码级原子扣减，但真实 MySQL 并发回放待执行；多批次只取首条 | 供应商生命周期、采购单、收货、发票、付款、三单匹配、容差、批次和审批；1688 token 按店隔离 | 采购到付款全链路测试；差异/超收/短收/退货；批次成本可追溯；供应商和店铺越权测试 | 高；业务流程需与真实采购和财务制度对齐 | 1688 和真实采购数据是 |
| BIZ-04 WMS、物流与轨迹 | P1 | 有仓库和库存表，但拣货、打包、面单、轨迹、调拨不完整；多仓保存非原子 | 入库/上架/拣货/复核/打包/出库/调拨/退货；库位和库存移动账；承运商、面单、轨迹、异常件；幂等作业单 | 仓储作业状态机测试；多仓并发调拨；面单和轨迹沙箱；库存移动账与台账对账 | 高；依赖承运商、17TRACK 和仓库流程 | 是 |
| BIZ-05 客服、RMA 与 PII 生命周期 | P1 | 工单/RMA 表存在但店铺归属、状态机、PII 生命周期不完整；旧 Messaging 客户端存在端点/多店 token 风险 | RMA 收货/检验/退款/补发；SLA；邮箱/聊天/电话 PII vault；保留和删除；客服动作审计 | RMA 全链路；跨店访问负向测试；PII 删除/导出/保留；消息发送回执和失败补偿 | 高；PII 和退款是高风险动作 | 是 |
| BIZ-06 财务、结算、税务与总账 | P0/P1 | 结算解析宽松；一次加载全店明细；加权平均成本；VAT 硬编码；金蝶真实客户端有占位号和失败状态问题；结算 `row_key` 可能跨店冲突 | 双分录账本；费用/退款/汇率/税/广告成本；FIFO/批次成本；结算和支付对账；期末关账；金蝶等总账幂等同步和重试 | 试算平衡；订单-结算-支付三方对账；跨店同业务行不丢失；汇率/税规则测试；总账回放和差异清单 | 极高；会计口径、税务辖区和总账供应商必须由业务负责人确认 | 真实结算/总账是 |
| BIZ-07 广告、OAuth 与自动化护栏 | P1 | 广告真实客户端为骨架；全局单套 profile/token；分页/429/Retry-After 不完整；服务可能返回硬编码 campaign | 按店/profile OAuth；统一限流和分页；日指标唯一键；归因窗口；预算/竞价自动化护栏；干跑、审批、审计、回滚 | 多店隔离；分页/429/重试；指标与 Amazon 报表对账；自动化预算上限和回滚演练 | 高；广告写操作直接影响花费，必须默认保守 | 是 |
| BIZ-08 Listing、Feed 与搜索派生同步 | P1 | Feed `DONE` 后未完整下载结果报告；超时任务可能永久停留；`DEFAULT_PRODUCT_TYPE` 占位；ES/Mongo/MySQL 无一致性机制 | 版本化 Listing；Feed 结果闭环；类目属性校验；Outbox 派生 ES/搜索；索引版本和重建；租户/店铺过滤强制化 | Feed 结果逐行可查询；ES 重建一致；跨租户搜索负向测试；Listing 版本冲突测试 | 中到高；搜索索引重建和类目动态变化是主要风险 | 沙箱/生产是 |

### 2.4 安全、可靠性与运维（OPS）

| ID | 优先级 | 当前证据 | 必须实现 | 验收证据 | 依赖、成本与风险 | 需真实凭证 |
|---|---|---|---|---|---|---|
| OPS-01 统一租户与数据所有权 | P0 | 当前大量表以业务键和 shopId 隔离，未证明所有表都有 tenant/shop；跨租户负向测试覆盖不全 | 统一 `tenant_id/shop_id` 规范；资源归属服务端解析；数据库行级隔离或等价查询守卫；缓存、ES、Mongo、对象存储同样隔离 | 跨租户/跨店测试矩阵；SQL 扫描；缓存/搜索泄漏测试；权限绕过测试；迁移前后数据归属对账 | 极高；涉及所有表、所有查询和历史数据迁移 | 否，生产验证是 |
| OPS-02 Outbox、Inbox、DLQ 与重放 | P0 | SP-API 已有持久化 Outbox、状态和显式重放；其他连接器和通用业务事件尚未统一；Inbox 仍主要是设计 | 统一事件信封、Outbox/Inbox、幂等键、重试策略、DLQ、人工重放、事务边界和审计；写操作不得自动重放 | 重复/乱序/崩溃恢复测试；支付/库存故障注入不重复扣减；DLQ 可查询和回放；恢复后对账 | 高；状态机、事务和业务补偿复杂 | 否，真实故障演练是 |
| OPS-03 SLO、告警和可观测性 | P1 | 有 Prometheus/Grafana/SkyWalking/Actuator 基础；缺少生产 SLO、错误预算、告警覆盖和值班闭环证据 | 每个关键链路定义 SLI/SLO；请求 ID、trace、tenant/shop/operation 维度；外部 API、队列、DB、限流、对账、DLQ 告警；runbook 和升级路径 | 监控面板截图/配置；告警触发和恢复演练；错误预算报表；故障注入后定位时间记录 | 中到高；指标基数和日志成本需要治理 | 否，生产 SLO 是 |
| OPS-04 容量、性能与背压 | P1 | 缺少基于目标店铺/SKU/订单量的压测和容量模型；多服务同步调用和全店加载存在风险 | 分页和游标；异步任务；缓存和索引；连接池；批量写入；队列背压；限流；容量水位和扩容策略 | 峰值压测；长稳测试；429/超时/DB 慢查询测试；p95/p99、吞吐、积压和恢复时间记录 | 高；真实容量需业务量数据，测试环境成本也高 | 真实/接近生产数据是 |
| OPS-05 Secrets、KMS 与最小权限 | P0 | 已有密码守卫和部分密钥工具；缺少 KMS、轮换、权限边界和泄露响应闭环 | Secret Manager/KMS；工作负载身份；按服务最小权限；密钥轮换和吊销；镜像/日志/CI secret 扫描；应急泄露流程 | 轮换演练；旧密钥失效；未授权读取拒绝；secret 扫描无高危；泄露响应演练 | 中到高；云产品、权限和合规成本 | 生产环境是 |
| OPS-06 发布、回滚与供应链安全 | P1 | CI 已有构建/测试基础；Checkstyle、迁移测试、secret/依赖/镜像扫描、SBOM、签名和回滚验证仍需形成硬门禁 | 全模块 CI；静态检查；SCA/SBOM/镜像签名；数据库 expand/contract；canary/blue-green；回滚点；发布记录 | 失败构建阻断；镜像签名校验；迁移回滚；金丝雀指标；按 digest 回滚演练 | 中；流水线时间和工具维护成本 | 否，生产发布是 |
| OPS-07 DR、RPO/RTO 与业务连续性 | P0 | 有基础设施编排不等于灾备达标；缺少实测 RPO/RTO | 备份、PITR、跨故障域、恢复顺序、依赖启动、数据一致性检查、通信和人工接管；定期演练 | 单实例/可用区/区域故障演练；实际 RPO/RTO；恢复后订单/库存/财务对账；演练复盘 | 高；跨区和人力成本显著 | 生产环境是 |
| OPS-08 成本与运维模型 | P1 | 当前没有按店铺/订单/广告/日志/跨区流量估算的正式模型 | 单位经济模型：每店/每千订单/每百万事件/每 TB 日志/每 region 的成本；容量和成本告警；保留策略；第三方 API 费用 | 成本预算与实测对比；峰值账单模拟；成本异常告警；按租户归集 | 中到高；成本假设会随规模和供应商变化 | 是 |

### 2.5 测试、模拟数据与验收（QA）

| ID | 优先级 | 当前证据 | 必须实现 | 验收证据 | 依赖、成本与风险 | 需真实凭证 |
|---|---|---|---|---|---|---|
QA-01 合成数据正确性与覆盖率 | P0 | 113/113 表已覆盖（ci 25484 行 / demo 225734 行，unique_key_repairs=0），结构/引用/标记/确定性 PASS；DDL 引用类型不一致已收敛为 0；校验规模已与 tier/dataset 联动（不再固定 200 单） | 修复类型和覆盖率缺口；所有数据带 `SYNTHETIC`；固定 seed；覆盖异常、退款、取消、乱序、重复、FX、税务和跨店场景 | 生成两次 byte-identical；引用违规为 0；类型发现为 0；行级校验已在 4000 订单/9 店铺规模下复跑（105090 行、引用违规 0），并在 staging 上限规模（20000 订单 / 10 店铺 / 5 站点、487678 行）复跑通过；生成器对引用列不再静默降级（直接报错）；按业务子图验收；禁止生产库误灌 | 中；生成器随 schema 变化需持续维护 | 否 |
| QA-02 零凭证契约与官方快照 | P0 | 15 份官方快照和 hash 已锁定，106 条 Usage Plan 双向校验通过；5 条 FBA operation 官方无 Usage Plan，按无猜测值处理 | 每个目标 API 锁官方 OpenAPI 的 byte count + sha256；路径/字段/usage plan 双向校验；5 条无官方数值的 FBA operation 不登记猜测值 | 快照来源、时间、hash、解析测试；路径扫描；限流表双向契约；能力清单与实现一致 | 中；上游模型会更新，需要版本升级流程 | 否 |
| QA-03 沙箱联调与 E4 证据 | P0 | A5=E0；没有任何真实/沙箱请求证据 | 在 Amazon 沙箱/授权卖家执行 LWA、RDT、Orders、Notifications、Reports/Feeds、Listings、Pricing、Catalog、FBA 等目标路径；保存脱敏请求 ID、状态、限流头和结果 | 每个 operation 至少一条 E4 记录；失败路径（401/403/404/409/429/5xx）有证据；PII 脱敏审计 | 高；需要应用审批、卖家授权、沙箱环境和人工操作 | 是 |
| QA-04 故障注入与一致性测试 | P0 | SP-API Outbox 有单测和部分重放；没有全系统故障演练证据 | 网络超时、DB 中断、MQ 重复/乱序、Redis 故障、外部 429、部分成功、进程崩溃和重放；验证幂等和最终一致 | 故障矩阵；重复扣减为 0；丢失事件为 0；恢复后自动/人工对账；证据留存 | 高；测试环境复杂度高，需专人维护 | 否，沙箱故障是 |
| QA-05 安全、租户和隐私测试 | P0 | 有部分 IDOR/店铺/内部服务测试；缺独立渗透、数据泄漏、PII 删除和供应链测试 | OWASP ASVS 基线、IDOR/BOLA、SSRF、注入、XSS、CSRF、JWT 重放、Webhook 伪造、PII 导出/删除、依赖和镜像扫描 | 自动化安全测试 + 独立渗透报告；critical/high 为 0；修复复测；隐私删除证据 | 高；独立测试和整改需要外部资源 | 生产前必须 |
| QA-06 性能、容量与财务对账 | P1 | 有单元/集成测试，但没有生产量级压测和财务对账证据 | 按目标店铺/SKU/订单/广告/结算规模压测；订单-库存-结算-支付-凭证对账；差异阈值和人工处理 | p95/p99、吞吐、错误率、积压；对账差异率达标；差异可建案关闭；长稳测试 | 高；依赖真实量级或高质量合成数据 | 接近真实数据/真实环境是 |

## 3. 推荐实施波次

以下波次是依赖顺序，不是简单日历承诺。每个波次都必须保留可回滚点。

### Wave 0：冻结边界与修复离线阻断

- 完成 REL-01、REL-02、REL-03、REL-05、REL-06、REL-10。
- 修复 7 个 DDL 引用类型问题，并让合成数据引用违规为 0。
- 保持 API-Ready 证据口径：无真实凭证时最高只声明 E3。
- 退出条件：生产 profile 无法落入 mock；跨店/跨租户负向测试通过；空库和升级迁移通过；CI 门禁可阻断失败。

### Wave 1：SP-API 合规底座与 RDT

- 经明确批准后实施 SP-01、SP-02。
- 锁定 Tokens API 官方模型快照；实现 token source、内存缓存、失效和重放重新获取。
- 退出条件：RDT 单测/桩回放全通过；受限 Orders 无普通 LWA 回退；PII 不进入明文日志或 Outbox。

### Wave 2：外部事件与真实联调

- 实施 SP-03、SP-04、SP-05、SP-06、SP-07、SP-08、SP-09、SP-10。
- 以每个 API 的官方快照、usage plan、沙箱请求和错误路径为验收单元。
- 退出条件：目标 operation 均有 E3 离线契约；至少关键路径达到 E4；SQS/SNS 消费链路、真实授权与生产验收仍未完成。

### Wave 3：业务事实与财务闭环

- 实施 BIZ-01 至 BIZ-08，优先订单、库存、结算、财务。
- 退出条件：订单/库存/结算/支付/凭证可重放；财务试算平衡；差异可建案和关闭；并发不超卖、不重复扣减。

### Wave 4：多租户、可靠性和生产运维

- 实施 OPS-01 至 OPS-08，同步 QA-03 至 QA-06。
- 退出条件：跨租户隔离、Outbox/Inbox、SLO、备份/PITR、DR、发布回滚和故障注入全部有证据。

## 4. 拿到凭证后的最小验收路径

### 阶段 A：资质与授权（无代码或少量配置）

1. 确认部署形态、目标市场、店铺数和数据访问范围。
2. 完成 Amazon 开发者/应用注册与审核，明确沙箱和生产环境。
3. 卖家授权应用，核对角色、marketplace、region 和 RDT/SPDS 权限。
4. 生成凭证并注入 Secret/KMS，不写入 Git、镜像或聊天记录。
5. 使用 `docs/examples/spapi-credentials.example.json` 和首部署 runbook 做脱敏配置核对。

### 阶段 B：沙箱联调（E4 起点）

1. LWA token 获取与刷新；错误 client、refresh token、过期/撤销均 fail-closed。
2. 获取 RDT，逐项验证 `restrictedResources` 与真实请求匹配。
3. Orders 受限字段、Reports/Feeds、Sellers、Messaging、Uploads 至少各跑通一条读写或读取路径。
4. 记录脱敏请求 ID、HTTP 状态、`x-amzn-RateLimit-Limit`、`Retry-After`、分页 token 和错误分类。
5. 对 401/403/404/409/429/5xx、超时、重复事件和重放做故障注入。
6. 确认受限调用没有普通 LWA 回退，RDT 不落库、不打印、不出现在响应。

### 阶段 C：生产试点（E5 起点）

1. 只选一个店铺、一个 marketplace、只读优先；限制操作角色和预算。
2. 观察订单、库存、报表、限流、错误率、Outbox 积压和对账差异。
3. 达到观察窗口后再开启低风险写操作，最后才允许财务、退款、广告预算和 Listing 写操作。
4. 每个写操作必须幂等、可审计、可回滚，且有人工审批或明确护栏。
5. 记录实际 SLO、RPO/RTO、告警响应、对账结果和事故复盘。

## 5. 仍需用户决策的信息

1. **部署形态**：单租户私有化、SaaS 多租户，还是两者都要；这决定隔离、计费和运维成本。
2. **云/区域**：云厂商、区域、是否允许托管 MySQL/Redis/RabbitMQ/ES/KMS；是否有数据驻留要求。
3. **规模**：店铺数、marketplace、SKU、日订单、广告量、结算量、峰值和三年增长。
4. **Amazon 资质**：是否已有开发者档案、应用审核状态、卖家授权、沙箱/生产配额、SPDS/RDT 权限和 Amazon 联系人。
5. **财务制度**：会计制度、税务辖区、币种、汇率来源、关账周期、金蝶/其他总账要求和审计口径。
6. **外部供应商**：短信、邮件、广告、AI、对象存储、日志、Keepa、17TRACK、1688、Temu/TikTok/Shein 等。
7. **合规要求**：GDPR/PIPL/CCPA、DPP、数据保留、删除、跨境和备份删除要求。
8. **团队与预算**：研发、SRE、财务、客服、法务/安全人员；值班能力；云和第三方 API 预算。
9. **数据迁移**：是否有旧生产数据、迁移窗口、可接受停机时间和历史数据对账责任人。
10. **上线目标**：首发业务范围、成功率/对账率阈值、可接受人工处理量和 Go/No-Go 决策人。

## 6. 成本、风险和被忽略的变量

- **凭证不是全部成本**：Amazon 应用审核、沙箱资格、卖家授权、SPDS/RDT 审批和安全问卷都有时间成本。
- **联调不是一次成功**：限流、分页、时区、币种、市场差异、重复事件、迟到事件和 Amazon 端临时错误都会反复出现。
- **合成数据不能替代生产对账**：它只能证明生成器和离线结构；真实金额、税费、退款、汇率和结算必须以 Amazon 数据为准。
- **最危险的变更不是接口，而是事实模型**：订单唯一键、库存扣减、结算行唯一键、FIFO 成本和 PII 归属一旦上线后修改，迁移和审计成本很高。
- **多租户是横切风险**：只修网关不够，必须覆盖服务、SQL、缓存、搜索、文件、消息、日志、导出和 AI 工具。
- **自动化会放大错误**：广告、Listing、退款、库存和财务自动化必须默认干跑、限额、审批、审计和可回滚。
- **RDT 是合规红线**：RDT 短期、按资源、按店铺使用；不得持久化，不得记录，不得用普通 LWA 降级。
- **“可部署”与“可运营”不同**：能启动不等于有备份、告警、值班、恢复、SLO、对账和事故处理能力。
- **工期数字是量级**：原设计文档的阶段 0/1/2/3 约 2–6 / 6–12 / 8–16 / 12–24 周，前提是团队、范围、凭证和业务规则都确定；缺少这些条件时不能把区间当作承诺。

## 7. 结论

- 当前项目是一个**规模较大、离线测试基础较强、但仍处于生产化改造中**的 Amazon ERP 代码库。
- 当前最准确的连接器状态是：**具备对接能力（未联调）**。
- 拿到合规 API 访问权后，已有 90 条离线 operation、统一客户端、限流、契约和 SP-API Outbox 可以复用，正常接入不需要整体重写；但外部事件消费（SQS/SNS）、真实沙箱联调、PII、财务事实和多租户隔离仍是硬门槛。
- 在完成本文 REL/SP/BIZ/OPS/QA 的 P0 验收前，不应对外宣称“已接通”“生产就绪”或“有 API 即可直接使用”。
- Wave 1 的离线 RDT 底座已实施；真实 RDT/Orders PII 联调仍须在凭证、授权和沙箱就绪后按 runbook 执行。

## 附录 A：关键代码与文档证据

- `amz-service/amz-service-spapi/src/main/java/com/amz/connector/ConnectorRegistry.java`
- `amz-service/amz-service-spapi/src/main/java/com/amz/connector/ConnectorEvidencePolicy.java`
- `amz-service/amz-service-spapi/src/main/java/com/amz/client/SpApiGateway.java`
- `amz-service/amz-service-spapi/src/main/java/com/amz/connector/SpApiRequestFactory.java`
- `amz-service/amz-service-spapi/src/main/java/com/amz/outbox/SpApiCallOutboxService.java`
- `amz-service/amz-service-spapi/src/main/java/com/amz/outbox/SpApiOutboxReplayExecutor.java`
- `amz-service/amz-service-spapi/src/test/resources/contracts/README.md`
- `docs/superpowers/specs/2026-09-24-amazon-erp-production-design.md`
- `docs/superpowers/plans/2026-09-24-connector-api-ready-phase0.md`
- `docs/superpowers/runbooks/connector-acceptance-runbook.md`
- `docs/superpowers/runbooks/first-deploy-bootstrap-runbook.md`
- `tools/synthetic-data/verify.py`
- `tools/synthetic-data/snapshot_schema.py`

## 附录 B：外部依据

- [Connecting to the SP-API](https://developer-docs.amazon.com/sp-api/docs/connecting-to-the-selling-partner-api)
- [Authorization with Restricted Data Token](https://developer-docs.amazon.com/sp-api/docs/authorization-with-restricted-data-token)
- [Amazon Tokens API OpenAPI model](https://github.com/amzn/selling-partner-api-models/blob/main/models/tokens-api-model/tokens_2021-03-01.json)
- [amzn/selling-partner-api-models](https://github.com/amzn/selling-partner-api-models)（Apache-2.0）
- [saleweaver/python-amazon-sp-api](https://github.com/saleweaver/python-amazon-sp-api)（MIT）
- [jlevers/selling-partner-api](https://github.com/jlevers/selling-partner-api)（BSD-3-Clause）
- [amzn/selling-partner-api-sdk](https://github.com/amzn/selling-partner-api-sdk)（Apache-2.0）
- [openoms-org/openoms](https://github.com/openoms-org/openoms)（Elastic License 2.0，source-available，不取用代码）
- [nplszfl/OmniTradeERP](https://github.com/nplszfl/OmniTradeERP)（无明确许可证，仅作结构参考）
- [penghaiping/amazon-sp-api](https://github.com/penghaiping/amazon-sp-api)（无明确许可证，仅作历史参考）

> 第三方项目的许可证和能力会变化；正式采用前必须重新核对许可证、提交时间、安全状态和维护活跃度。
## 附录 C：RDT API-Ready 实施与联调计划

- [SP-API Restricted Data Token（RDT）API-Ready 实施计划](../plans/2026-09-26-spapi-rdt-api-ready.md)
- 状态：离线 API-Ready 已实施，RDT 与订单 PII 默认关闭；真实 RDT/订单 PII 未联调。
- 已完成官方 Tokens 快照/hash、资源校验、缓存隔离、显式 token source、401/403 精确失效、无 LWA 回退和 Outbox 重放恢复。
- 仍待真实 Amazon 应用审批、卖家授权、RDT/SPDS 权限、沙箱/生产请求和 PII 生命周期验收。

