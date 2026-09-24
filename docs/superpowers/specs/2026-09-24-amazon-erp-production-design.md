# AmazonERP 生产化升级设计规格（Draft for Review）

- 文档日期：2026-09-24
- 审查基线：`master` / `fdbea6868ea6219bce7be3b94f69b5d7471592a7`（本地领先 `origin/master` 8 个纯文档提交，未 push；`origin/master` = `06769b77b291467216007bf9843211de3a0cf14e`）
- 当前状态：**设计草案，尚未修改任何业务源码**
- 目标：把现有“功能覆盖较广的可演示微服务原型”升级为**可审计、可恢复、可运维、可安全上线**的亚马逊 ERP；无真实数据时使用确定性模拟数据，所有模拟数据必须带 `SYNTHETIC` 标识。
- 重要结论：模拟数据可以替代缺失的业务数据用于开发、测试和容量验证，**不能替代亚马逊开发者资质、真实店铺授权、SP-API 沙箱/生产联调、税务与会计责任、渗透测试、灾备演练和业务验收**。

## 0. 结论先行

### 0.1 当前判断

当前项目**不能按现状视为可直接生产部署**。更准确的定位是：

> 功能覆盖面较广、模块边界初步形成、以自测和演示为主要目标的 Spring Cloud 微服务原型。

原因不是“缺少几个页面”，而是生产系统必须成立的四类事实源没有闭合：

1. **订单事实源**：只有订单头，没有订单行、完整费用、退款、事件序列和可重放同步；同步策略无法覆盖状态变化、取消和延迟结算。
2. **库存事实源**：以亚马逊 FBA 快照代替内部库存台账，没有预占、扣减、冲销、调拨、退货入库和可售承诺口径。
3. **财务事实源**：缺少借贷平衡凭证、真实 FIFO 成本层、多币种汇率、税务规则、结算对账和期末关账。
4. **集成事实源**：外部调用、Webhook、MQ、缓存和数据库之间没有统一的 Outbox/Inbox、幂等和失败重放机制。

同时存在必须先修复的生产阻断项。本轮把这部分从初稿的 12 条扩展为 **32 条（P0-01…P0-32）**，新增的证据来自 328 个端点的全量守卫矩阵（附录 F）、Maven 运行时依赖树与 OSV 配对查询、以及 AI/广告/物流/客服/选品/多平台六个域的逐方法核对。新增类别是：广告域零租户隔离、Agent 记忆 IDOR、SSE 身份+角色双丢失、刷新令牌链路失效、订单身份可伪造、物流/仓库/客服写操作缺归属校验、搜索与知识库索引无租户过滤、选品 IDOR 与硬编码店铺 1、Webhook 无验签且跨租户错配、可实测的供应链漏洞基线，以及**“Real 客户端”名不副实与失败静默降级**（P0-22）。第 10 轮针对“暂时没有 API，但必须做到有凭证就能直接用”这一目标又补了 8 条（P0-23…P0-30）：凭证表 DDL 无任何自动执行路径、SP-API 默认 profile 为 `mock` 使财务域三类客户端在部署形态下返回样例数据、Nacos 地址变量在 Compose 与代码之间不一致且 16 份 `bootstrap.yml` 在当前依赖下不生效、spapi 是订单与库存关键路径的单点却只实现 6 类客户端、`ReportsRealClient` 读错官方字段名使结算报表文档 ID 恒为 null、限流默认配额最高比官方宽松约 120 倍且 3 个在用 endpointTag 无策略、k8s 自带 Secret 的 `AMZ_CRYPTO_KEY` 解码为 34 字节使 spapi 启动即崩、Feeds 结果报告永不下载使被拒行永久丢失。第 13 轮用真 YAML 解析器（PyYAML 6.0.3）与 JVM 运行时探针再钉死两类部署期缺陷（P0-31、P0-32）：order/product 的 `RedissonConfig` 硬编码第三方公网 Redis `121.37.250.15:6379`，且所读 `spring.redis.host` 键在 Spring Boot 3 下已改名、全仓无任何 yml 或环境变量可覆盖（实测 45,292 ms 后抛 `RedisConnectionException`）；`docker-compose.yml` 实测 31 个 service、`env_file` 0 次、`REDIS_HOST` 0 次、`RABBITMQ_HOST` 仅 order+finance、`MYSQL_HOST` 仅 spapi，16 份 k8s Deployment 全部不注入 `SPRING_PROFILES_ACTIVE`（`JAVA_OPTS` 已逐字核对为纯 JVM 参数），使 15 个 `@Profile("mock")` 客户端在两种部署形态下都会伪造成功。

需要特别说明的一点自查：初稿曾把“AI 工具会写生产数据”当作整体结论，本轮逐行核对后收窄为**只有 `cross_marketplace_listing` 一条真实写入链路**（详见 1.3 节的诚实修正）。同样，“JWT 空密钥静默可用”的假设也被推翻——`JwtUtil.init()` 在密钥为空时直接让服务启动失败，这是正向设计。

另一个需要提前纠正的印象：**类名带 `Real` 不等于真实对接**。仓库里的 `KingdeeRealClient`、`Alibaba1688RealClient`、`SheinRealClient`、`TemuRealClient`、`TikTokRealClient`、`MessagingApiRealClient`、`AdvertisingApiRealClient`、`KeepaRealClient`、`LogisticsTrackingRealClient` 都带 `@Profile("!mock")`，但它们要么返回占位值、要么自述“未校准”、要么在失败时静默返回 null/空列表。默认 profile（未显式设置 `SPRING_PROFILES_ACTIVE`）就会加载这些实现，因此“生产环境已无 mock”这句话在语义上不成立——它们只是不再叫 Mock。完整盘点见 4.1 的现状表与 P0-22。

### 0.2 不能被 README 或旧计划证明的事情

- README 或旧计划中的“全部完成”“测试通过”“546 单测通过”不能证明生产成熟度；测试数量不是生产准入条件，安全、功能、恢复和数据正确性验证才是。
- 前端 `npm run test:run` 与 `npm run build` 曾在当前工作区通过，但这只能证明前端单元测试和构建通过。
- 后端已在本轮实测通过：19 个模块全量编译成功、后端单测 `527 执行 / 0 失败 / 0 错误 / 2 跳过`（明细见 1.2 节）。这只证明“主代码可编译、现有单测可跑绿”，**不证明**运行期行为、生产配置、多实例一致性或端到端启动正确。
- 本轮未启动完整 Compose、未做端到端联调、未跑 Playwright、未与真实 SP-API 联通；因此本文**不宣称**系统可端到端启动或已与亚马逊生产接口打通。
- 仓库文档声明的平台能力必须以真实客户端和真实联调为准。**已核对的结论**：`README.md:43` 声明多平台支持 “Shopify/eBay/Walmart/Shopee/Lazada”，但 `amz-service-multiplatform` 只有 **Shein / Temu / TikTok** 三套客户端（接口 + Real/Mock 实现），**不存在** Shopify/eBay/Walmart/Shopee/Lazada 的任何接口、实现或分发分支；`MultiplatformServiceImpl.java:632-635` 与 `706-712` 的 `switch (platform)` 只有 `TEMU/TIKTOK/SHEIN` 三个 case，其它值抛“不支持的平台”，`syncAll`（614-617）同样只同步这三家。因此该 README 条目属于**不实宣称**，必须修正或补齐实现后再对外发布（详见附录 G）。
- 其它“已声明但无实现证据”的能力同样只能当作待验证项：README 各模块的完成度措辞、`docs/es-native-rrf-check.md` 假定的 `amz_product` 索引已存在，都还没有证据支撑。

### 0.3 工作假设与需要确认的决策

本文先按以下工作假设设计，评审时可以推翻：

| 决策 | 本文假设 | 影响 |
|---|---|---|
| 首批部署形态 | 优先做**单租户私有化部署**，保留 `tenant_id` 作为未来 SaaS 扩展点 | 降低首期隔离复杂度，但不能省掉租户字段与隔离测试 |
| 技术栈 | 保留 Java 17 / Spring Boot / MyBatis-Plus / MySQL / Redis / RabbitMQ / ES | 不进行高风险重写；先修事实模型和运维基线 |
| 运行单元 | 目标收敛为 5 个逻辑运行单元；P0 阶段仍可保留现有微服务 | 收敛是运维目标，不是安全修复的前置条件 |
| 数据来源 | 无真实数据时使用固定 seed 的模拟数据，显式标注 `SYNTHETIC` | 可用于开发、测试、容量与演练；不可用于申报、报税或生产对账 |
| 多租户策略 | 首期单租户；未来 SaaS 采用“租户字段 + 强制拦截 + 高风险租户库隔离” | 需要将现有无租户索引和查询全部纳入迁移 |
| 合规责任 | Amazon DPP/SP-API 要求需由法务、财务、税务和安全负责人共同确认 | 本文给出工程控制，不代替法律和税务意见 |

---

## 1. 现状基线与生产准入门槛

### 1.1 已核对的代码规模与结构

- 主 Java 文件 666 个、测试 Java 文件 66 个，合计 732 个（`rg --files -g "*.java"` 与目录遍历双口径一致）；`@Test` 注解 527 处（与 1.2 节实测执行数一致，不是“断言数”）。
- 测试分布极不均衡：`amz-service-product` 有 50 个主文件、**0 个测试文件**；`amz-service-ai` 16、`amz-service-finance` 9、`amz-service-spapi` 8。也就是说，唯一确认会真实写入外部系统（SP-API Feeds）的商品模块，恰好是唯一完全没有测试的模块。
- `@TableName` 约 98 处，`@RestController` 约 53 处，`@ShopScoped` 约 258 处，`@RequireRole` 约 34 处，`@FieldPermission` 约 17 处。
- AI 工具声明 `@Tool(` 约 29 处。
- 表口径存在冲突：
  - `init_all_tables.sql` 约为 57 张唯一表；
  - `docker/init-sql` 约为 105 张；
  - README 写 54 张。
  这说明建表源、升级脚本和文档之间没有单一事实源。

### 1.2 本轮实测的构建与测试基线（可复现）

在评审基线上，使用仓库外的便携工具链（Microsoft OpenJDK 17.0.20.1 + Apache Maven 3.9.11，不污染仓库）实测：

| 项目 | 本轮实测结果 | 能证明什么 / 不能证明什么 |
|---|---|---|
| 全量编译 | 19/19 模块 `BUILD SUCCESS` | 证明主代码可编译；仅 deprecation/unchecked 警告 |
| 后端单测 | `Tests run: 527, Failures: 0, Errors: 0, Skipped: 2`（15 个模块产出报告，累计约 52 秒） | 证明现有单测在隔离环境可跑绿；不证明运行期与生产配置正确 |
| 跳过用例 | `SpApiIntegrationTest` 2 例跳过 | 由 `@EnabledIfEnvironmentVariable(RUN_INTEGRATION_TESTS=true)` 控制；即**真实 SP-API 联调在默认 CI 中并不执行** |
| 前端单测/构建 | `npm run test:run` 133/133 通过、`npm run build` 通过 | 只证明前端单测与构建；39 个 Playwright 用例仅枚举、未执行 |
| 前端依赖漏洞 | `npm audit --prefix amz-frontend` 实测 10 项（7 high / 3 moderate，0 critical；prod 52 / dev 260 / 总 311 依赖） | 未修复，属于发布前必须清零或书面豁免的项 |
| Maven 运行时依赖树 | `mvn -B -ntp dependency:tree -Dscope=runtime` 全 19 模块 `BUILD SUCCESS`（约 41s），解析出 427 个唯一坐标（含 17 个 `com.amz:*` 自身，第三方 410 个） | 说明依赖可完整解析；产物 `target/dependency-tree-runtime.out.log`、`target/maven-coords.txt` |
| Maven 依赖漏洞（OSV，paired 查询） | 410 个第三方坐标 → 210 组 `(坐标, advisory)` 命中、涉及 67 个坐标、190 条唯一 advisory；严重度分布 18 critical / 80 high / 90 moderate / 22 low；其中 206 组已有修复版本事件，4 个坐标在 OSV 中无修复版本 | 说明“可编译”与“可安全上线”是两件事；`fastjson 1.2.83`（CRITICAL RCE，1.x 无修复）仍随 Seata 进入 17 个模块的运行时类路径 |

复现命令（PowerShell，仓库根目录，工具链路径按本机实际替换）：

```powershell
$env:JAVA_HOME='<jdk17-home>'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"
mvn -B -ntp -fae test
```

**环境陷阱（不是项目缺陷，但会污染结论）**：在 Codex Desktop for Windows 的子进程环境中，JDK 的 NIO selector 初始化会失败：

```
java.io.UncheckedIOException: ... Unable to establish loopback connection
Caused by: java.net.SocketException: Invalid argument: connect
    at sun.nio.ch.UnixDomainSockets.connect0
```

该现象由宿主环境引起，与仓库代码无关（公开问题单：`openai/codex` issue #40902，状态 open）。它表现为 `com.amz.auth.LwaTokenManagerTest` 9 个用例报错、后续模块被跳过，从而把“527 全绿”误读成“9 errors”。同一 JDK 在外层普通 PowerShell 中可正常运行；在同类沙箱中执行测试时，需显式设置：

```powershell
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp'
```

**为什么不把“测试全绿”当作上线依据**：本基线中没有任何用例覆盖真实 SP-API、真实数据库迁移、多实例一致性、限流退避、Outbox/Inbox 重放、PII 留存或灾备恢复；这些恰恰是 P0/P1 缺口所在。测试全绿与生产可上线是两件事。

### 1.3 生产阻断项（P0）

以下是“必须修复后才能上线”的问题，而不是普通优化项。

| P0 | 已核实风险 | 证据方向 | 必须达到的状态 |
|---|---|---|---|
| P0-01 | 生产服务默认 mock（第 13 轮扩容取证：k8s 与 Compose 全域均不设 profile） | 8 个模块 `application.yml` 显式声明 `spring.profiles.active`，其中 **7 个默认 `mock`**（ad/logistics/multiplatform/procurement/product/report/spapi），finance 为空默认；`k8s/services/*.yaml` 全 16 份 `SPRING_PROFILES_ACTIVE` 命中 **0**，`docker-compose.yml` 同样 **0**；`k8s/configmap.yaml:47` 的 `JAVA_OPTS` 已逐字核对为纯 JVM 参数（`-Xms512m -Xmx1024m -XX:+UseG1GC …`），无 `-Dspring.profiles.active`；全仓 15 个 `@Profile("mock")` 类在部署形态下返回**伪造成功**而非空值，例如 `AdvertisingApiMockClient.java:43-47` 的 `updateKeywordBid` 无条件 `return true`、`ListingsMockClient.java:38-44` 的 `getFeedStatus` 恒返回 `DONE` + 随机 `resultDocumentId`、`LogisticsTrackingMockClient.java:33-46` 虚构含 `DELIVERED` 的 7 段轨迹；`OpsMonitorScheduler.java:22-27` 注释自述其落库数据“与真实告警无法区分，会污染生产数据” | 生产配置显式禁用 mock；启动时检测到 mock bean 直接失败（同一 connector 的 `@Profile("mock")` 与 `@Profile("!mock")` 不得同时被部署形态选中）；CI 对 16 份 k8s 清单 + Compose 做 profile 存在性契约检查 |
| P0-02 | `/internal` 被网关和业务拦截器放行，内部通知接口可被伪造 | `MessageNotifyController`、`BaseAuthInterceptor`、`MyGlobalFilter` | 内部接口只接受 mTLS + 服务 JWT；公网和普通网关请求不可达；消息服务端口不暴露到宿主机 |
| P0-03 | 租户与店铺校验 fail-open 且覆盖面被高估 | `UserContext.isShopAllowed()` 在 `shops` 为空时返回 `true`；`ShopIdGuardAspect` 在 shops 为 null/空时**放行**（只有切面自身异常时才 fail-closed，两者不对称）；网关只校验 `shopId` 请求头，**不校验 body/query/path**；`@ShopScoped` 仅覆盖 Long 型且名为 `shopId` 的 `@RequestParam/@PathVariable`，且只有方法级注解（无类级） | 所有租户/店铺/字段权限默认拒绝；缺少上下文视为认证失败；资源归属必须来自服务端解析，不能来自请求参数 |
| P0-04 | SSE Agent 身份与角色双丢失（越权 + 提权） | `AgentSseController` 的 `/ai/chat-stream` 从查询参数取 `userId`（`defaultValue="1"`）；`AgentChatStreamService.streamChat` 的固定线程池只传播 trace，不传播 `UserContext`；`ErpToolExecutor.execute` 入口校验因此 fail-open，`hasOperatePermission()` 在 role 为 null 时 `return true`，8 个 `OPERATE_TOOLS` 全部可被越权调用 | 身份、tenant、shop scope、**role**、trace 一起传播；禁止查询参数覆盖身份；写操作工具必须服务端鉴权 + 干跑/审批 |
| P0-05 | 新用户注册很可能失败 | `User` 实体未映射数据库 `username/password NOT NULL` 字段；登录服务插入字段不足 | 注册、登录、迁移使用同一用户模型；DDL 与实体自动校验 |
| P0-06 | 验证码并未真正发送短信 | `LoginServiceImpl.send()` 只写 Redis 并返回成功文案 | 接入真实短信供应商或明确返回“未配置”；生产启动时验证短信通道 |
| P0-07 | 报表服务数据库与初始化漂移 | 报表默认 mock；`amz_report` 未在 01 初始化中创建，升级脚本却直接 `USE amz_report`；Compose 未传 `MYSQL_HOST`/profile | 单一迁移入口；全新环境和存量环境都能迁移；启动前检查 schema 版本 |
| P0-08 | 任意用户可读取他人资料 | `GET /user/getUserById/{userId}` 无本人/ADMIN 校验 | 改为本人或明确授权角色；服务间调用使用服务身份；响应按字段最小化 |
| P0-09 | 字段权限异常时可见全部字段 | `FieldPermissionServiceImpl` 的降级策略、`FieldPermissionAspect` 的 null 跳过、`isFieldVisible` 的 null 返回 true | 权限服务不可用时拒绝敏感字段；规则缺失只允许“无敏感字段可读”，不能“全网可见” |
| P0-10 | WebSocket 握手未在首帧前完成鉴权 | `WebSocketHandler.channelRead0()` 先处理 ping，再处理 token | 握手或首帧鉴权失败即关闭连接；会话绑定 tenant/user/shop；支持撤销和单点清理 |
| P0-11 | 店铺凭证非多副本一致事实源 | `ShopCredentialStore` 用 `shopCredentialMapper.selectList(null)`（第 56 行）把**全部店铺凭证**一次性装入进程内缓存，DB 写失败只告警，无版本/失效机制 | 凭证以 KMS/Vault/数据库单一事实源为准；带版本、轮换、失效和审计；禁止全表加载到 JVM |
| P0-12 | 基础设施与内部端口公开、默认凭据占位 | Compose 暴露 3306/6379/5672/9200/8888/8889 等；`k8s/secret.yaml` 为已提交占位值；ES 无安全；Nacos/Grafana 默认配置 | 基础设施仅集群内访问；Secret 由外部密钥系统注入；TLS、认证、网络策略和默认拒绝全部生效 |
| P0-13 | 广告域完全没有租户/店铺隔离 | `AdServiceImpl` 返回硬编码 `camp-001/camp-002`；广告端点不校验 shopId 归属；指标无 `(shop_id, profile_id, date, entity)` 唯一键 | 广告数据按 `shop_id + profile_id` 建模；读写均校验归属；指标唯一键与归因窗口固定并可重放 |
| P0-14 | Agent 记忆与偏好可被任意用户读写（IDOR） | `AgentMemoryController` 六个端点从 query/path/body 取 `userId`，无本人/角色校验；`history` 会话名 `"sess-"+userId`；`updatePreference` 整段 body 落库 | 记忆/偏好归属从服务端会话派生；跨用户读写返回 403/404；写入字段白名单 + 大小上限 + 审计 |
| P0-15 | 订单接口可伪造下单人 | `OrderController.saveOrder` 无守卫 → `OrderServiceImpl.saveOrder` 把 body 中的 `userId` 发 MQ → `processOrderMessage` 用该值落库（`saveOrderInternal`）；与 `syncAmazonOrder`（shopId + `@GlobalTransactional` + afterCommit）模型不一致 | 订单写入身份来自认证上下文；B2C 与店铺订单共用同一事实模型（含 shop_id/marketplace_id）；MQ 消息带幂等键与来源校验 |
| P0-16 | 刷新令牌经网关必然失败，且无轮换/吊销 | `BaseAuthInterceptor` 白名单不含 `/user/refresh`；`parseToken` 对 `refresh:{id}` 做 `Integer.valueOf` 抛 `NumberFormatException` 返回 401；`JwtUtil.verifyRefreshToken` 本身实现正确，但无轮换、吊销或设备绑定，被盗 token 在 TTL（7 天）内可重放 | 刷新接口可达且语义正确；refresh token 一次性轮换、可吊销、绑定设备/会话并记录审计；重放被检出并拒绝 |
| P0-17 | 物流/仓库/客服写操作缺归属校验 | `LogisticsController.createShipment` → `LogisticsServiceImpl.createShipment` 直接 insert（对照同文件 `requireAccessible` 才是正向模式）；`WarehouseServiceImpl.decreaseInventory` 只按 `warehouseId + sku`，不含 shop；`CustomerController.receiveMessage` 信任 body 的 ticket/shopId，`replyTicket` 为 `selectById → updateById` | 所有写操作以 `shop_id` 为强制过滤条件；复用已有正向模式统一收口；越权返回 404 而非 403 以避免存在性泄漏 |
| P0-18 | 搜索与知识库索引无租户/店铺过滤 | `SearchServiceImpl.hybridSearch` 的 BM25（207–216 行）与 kNN（218–228 行）两条路径都固定在 `IndexCoordinates.of("amz_product")` 且无 filter，`bm25Search`（259–270 行）同样；`/search/search/{key}` 无守卫；知识库向量检索同构 | 索引文档必须含 `tenant_id/shop_id/marketplace_id/visibility`；BM25 与 kNN **两条路径**都要带服务端注入的过滤；索引别名切换而非在线删索引 |
| P0-19 | 选品模块可读写他人记录并硬编码店铺 1 | `ProductSelectionServiceImpl` 第 74、204 行 `UserContext.getShopId() != null ? ... : 1L`；`aiSuggestion` 第 220 行 `selectById` 无归属校验，231/263/269 行 `updateById` 写回；结果由 `keyword.hashCode()+marketplace.hashCode()` 确定性生成，**未标 `SYNTHETIC`**，外观上与真实经营数据无异 | 归属校验 fail-closed；模拟结果显式标记 `SYNTHETIC` 并与真实数据隔离；写回前再次校验归属 |
| P0-20 | 多平台 Webhook 无验签且跨租户错配 | `MultiplatformController.receiveWebhook`（132–137 行）无守卫、传 `shopId=null`；`MultiplatformServiceImpl.receiveWebhook`（395–443 行）**没有任何入站签名校验**（全仓库仅存在出站签名工具），`shopId` 为空时按 platform 反查“最新创建的账号”（408–420 行），去重只按 `event_id`，`handleWebhookEvent` 仅打日志 | 每平台独立验签实现 + 时间戳/重放窗口；事件唯一键 `(platform, shop_id, event_id)`；无法确定归属时拒绝落库；处理逻辑实现真实业务动作而非仅记录 |
| P0-21 | 依赖供应链存在未修复的严重漏洞且无 SCA 门禁 | 本轮实测：410 个第三方运行时坐标命中 210 组 `(坐标, advisory)`、67 个坐标、190 条唯一 advisory（18 critical / 80 high / 90 moderate / 22 low，详见 5.7 与附录 F）；`fastjson 1.2.83` 为 CRITICAL RCE 且 **1.x 无修复版本**，仍随 `seata-all 2.0.0` 进入 17 个模块类路径；`tika 2.9.2` 的 XXE 可由知识库文档上传触达；网关最外层 `netty-all 4.1.115.Final` 含 CRITICAL 级 SNI 绕过与 HTTP/2 DoS 修复项；`npm audit` 7 high / 3 moderate | 依赖升级到修复版本或移除（含排除传递依赖）；CI 强制 SCA + SBOM + 镜像扫描并在高危未豁免时阻断；网关、文件解析、反序列化三类路径额外做运行时缓解 |
| P0-22 | 外部连接器的“Real 实现”名不副实，失败时静默降级为空结果 | 逐类核对（详见 4.1 现状表）：`KingdeeRealClient` 在 48–51 行无条件返回 `KINGDEE_MOCK_` 占位号，`FinanceServiceImpl` 205–206 行据此把凭证置为 `SYNCING`，而认领条件（190–194 行）只接受 `PENDING/FAILED` → 该凭证**永远无法再次同步**、也不是“已过账”；`Alibaba1688Signer:24`、`Alibaba1688TokenManager:26`、`Alibaba1688RealClient:37-38`、`SheinRealClient:103`、`TemuRealClient:94`、`TikTokRealClient:177` 全部自述“未校准”；`MessagingApiRealClient` 127–128 行只发 `Bearer` + `x-amz-access-token`、**无 AWS SigV4**，`/messaging/v1/orders` 与真实 SP-API Messaging 资源模型不符；`AdvertisingApiRealClient` 用全局单套 `advertising.profile-id`/`spapi.lwa.access-token`（52–56 行），182 行 ClientId 在 profileId 不含 `:` 时返回空串，失败即 `emptyList()`；`KeepaRealClient`、`LogisticsTrackingRealClient` 缺凭证时返回 null/空轨迹 | 每个连接器在生产启动时校验“真实且已校准”，否则拒绝启动或显式标记不可用并向调用方返回明确错误；禁止把占位号写进业务状态机；失败必须分类重试或进入隔离队列，不允许静默返回空结果冒充“无数据”；凭证按 tenant/shop 隔离并支持轮换 |
| P0-23 | 凭证表 `amz_shop_credential` 没有任何自动建表路径，“给凭证就能用”缺前置条件 | 全仓该表只有 2 处 `CREATE TABLE`：`amz-service/amz-service-spapi/src/main/resources/db/schema.sql:11` 与 `init_all_tables.sql:459`；Compose 初始化目录 `docker/init-sql/`（32 个文件，编号 01–33、缺 03）对 `credential` **0 命中** → Compose 起库后该表不存在；spapi 自带 Flyway 迁移 `db/migration/V1__init.sql` 只建 7 张表（`amz_fba_inventory`、`amz_product_sales_stats`、`amz_inventory_sync_log`、`amz_replenishment_suggestion`、`amz_sales_history`、`amz_seasonal_index`、`amz_promotion_calendar`），不含凭证表；全仓 `spring.sql.init.schema-locations` 0 命中，且 `db/schema.sql` 不在 classpath 根（Spring Boot 默认只匹配 `classpath*:schema.sql`）；更关键的是 Flyway 已在 spapi 的 classpath 上（`flyway-core` + `flyway-mysql`，全仓无任何 `spring.flyway.*` 覆盖 → 默认启用，locations 默认为 `classpath:db/migration`），而 `db/migration/V1__init.sql`（113 行）只建 7 张表、**不含凭证表** → 修复点应是**新增 V2 迁移**并验证 Flyway 与 `dynamic-datasource` 组合确实落在 master 库，而不是再手抄一份 `schema.sql`；`ShopCredentialStore.loadFromDb()`（55–64 行）抛异常时只 `log.warn`，以空缓存继续启动 | 凭证表纳入唯一迁移入口并进入 clean-install 与 upgrade 流水线；凭证加载失败必须 fail-closed 拒绝启动；写入有唯一键、归属校验与审计 |
| P0-24 | SP-API 默认 profile 是 `mock`，部署形态下财务域客户端返回样例数据 | `amz-service-spapi/src/main/resources/application.yml:7` → `active: ${SPRING_PROFILES_ACTIVE:mock}`；`ReportsMockClient`/`FinancesMockClient`/`FeesMockClient` 标注 `@Profile("mock")`，这是**唯一**带 profile 分支的三对客户端（Orders/Feeds/FbaInventory 是单一实现，无 profile 切换）；`docker-compose.yml` 的 spapi 段与 `k8s/services/amz-service-spapi.yaml`（实测 24 个环境变量）**都不传** `SPRING_PROFILES_ACTIVE`（grep 命中 0）→ 部署后仍是 mock；与 finance 模块口径相反（`amz-service-finance/application.yml:10` 默认为空 → 加载 Real 实现） | 生产 profile 必须显式且默认非 mock；启动时检测到 mock 实现直接失败；CI 对生产配置做静态校验（见 1.9） |
| P0-25 | Nacos 配置多处断裂，Compose 路径下注册与发现实际不可用 | (a) `docker-compose.yml` 16 处只注入 `NACOS_SERVER_ADDR=nacos:8848`（269、301 等行），而代码统一读 `${NACOS_ADDR:localhost:8848}`（如 `amz-gateway/application.yml:9`）→ 容器内回落到 localhost 自指；(b) 16 份 `bootstrap.yml`（含 gateway）写 `${NACOS_ADDR:123.206.101.247:8848}`，但全仓无 `spring-cloud-starter-bootstrap`、无 `nacos-config`、无 `spring.config.import` → 在 Spring Cloud 2023.0.x 下这些文件**不会生效**；(c) `amz-common/src/main/resources/seata-default.yml:12/19` 硬编码同一公网 IP，全仓引用点 0，属随仓库分发的死配置；(d) 只有 k8s 路径正常（`k8s/configmap.yaml:10-11` 两个变量都定义，`k8s/services/amz-service-spapi.yaml:49-51` 双双注入）；(e) `.env.example` 不含 `NACOS_ADDR`/`NACOS_SERVER_ADDR` | 变量名全仓统一并加启动自检（注册/配置拉取失败即拒绝启动）；删除或修复死配置文件；任何环境不得内置第三方公网 IP |
| P0-26 | spapi 是订单/库存关键路径的单点，但只实现 6 类客户端 | 已实现：Orders、Feeds、FbaInventory、Reports、Finances、Fees（`client/` 目录 + `SpApiGateway`）；跨模块 Feign 双向核对通过（product `SpapiFeedsClient` ↔ `FeedsController`；finance `SpApiFinanceClient` ↔ `FinancialDataController`）；`SpapiFeedsClientFallbackFactory` 返回 `Result.failure` 不伪造成功（正向）；`SpapiController` 的 `/credential` 在无凭证时返回 `no credential for shopId=…`（94–96 行，正向）。缺 Notifications、RDT、Listings Items、Product Pricing、Catalog Items、Sellers、Inbound FBA（关键词命中全为 0，见 1.4.1） | 关键路径不得依赖无降级的单一模块；能力清单必须与实现一致，未实现能力对外显示“不可用”而不是留白 |
| P0-27 | `ReportsRealClient` 读错官方字段名，结算报表文档 ID 恒为 null | `ReportsRealClient.java:80` 用 `str(resp, "resultDocumentId")` 取文档 ID，而官方 `reports_2021-06-30` 模型的 `Report` 属性是 **`reportDocumentId`**；全仓 `reportDocumentId` 命中 0、`resultDocumentId` 命中 2（另一处是 product 模块 `ListingsMockClient.java:43`）；连锁影响：finance `SettlementServiceImpl.java:88/193` 拿 null 去 `downloadDocument`，结算原表下载链路整条失效，而报表状态仍被置 `DONE`（“报表完成了但取不到文件”） | 响应映射必须由官方 OpenAPI 模型生成或与之做契约测试，禁止手写字段名；报表 `DONE` 必须伴随可下载文档 ID，否则进入错误状态并告警 |
| P0-28 | `SpiRateLimiter` 默认配额最高比官方宽松约 120 倍，且不按店铺隔离策略 | 文件位于 `amz-service/amz-service-spapi/src/main/java/com/amz/ratelimit/SpiRateLimiter.java`（155 行）；70–73 行默认 `orders=30/30s`、`fba-inventory=25/30s`、`listings=10/30s`、`reports=5/60s`，注释自称“SP-API 官方默认配额（保守值）”，而官方 `getOrders` 为 0.0167 req/s（本实现宽松约 60 倍）、`createFeed` 为 0.0083 req/s（兜底策略宽松约 120 倍；`createFeedDocument` 官方为 0.5 req/s，本仓库同走兜底 1 req/s，宽松约 2 倍）；实际使用的 6 个 endpointTag 为 `orders`/`fba-inventory`/`feeds`/`fees`/`finances`/`reports`，其中 `feeds`/`fees`/`finances` **无策略**（落到 `DEFAULT_MAX_REQUESTS=30`/`DEFAULT_WINDOW=30s`），而 `listings` 策略**无任何调用方**；`updateLimit()`（126–148 行）只在更严格时收紧（139–143 行），进程生命周期内不会恢复；`windows` 按 `shopId:endpoint` 分键但 `policies` 只有 endpoint 维度 → 单店触发收紧会影响所有店铺；`acquire()` 在持有 `Deque` 锁的同步块内 `Thread.sleep`（96–113 行），`@Scheduled` 单线程池下多店串行阻塞 | 按官方 usage plan 逐 operation 配置 rate 与 burst；按 `shop_id + endpoint` 隔离预算；支持依据 `x-amzn-RateLimit-Limit` 动态调整且可恢复；限流等待不得持有锁、不得阻塞调度线程 |
| P0-29 | k8s 自带 Secret 的加密密钥长度非法，spapi 启动即崩 | `k8s/secret.yaml` 的 `AMZ_CRYPTO_KEY` base64 解码为 `change_me_32_bytes_key_please_256!`，**实际 34 字节**（注释自称 32 字节）；`amz-common/src/main/java/com/amz/util/CryptoUtil.java:62-65` 对 `keyBytes.length != 32` 直接抛 `IllegalStateException`；注入链：`k8s/services/amz-service-spapi.yaml:83-84` → `application.yml:83` 的 `crypto.key: ${AMZ_CRYPTO_KEY:}`；同文件其它占位值：`JWT_SECRET_KEY` 解码 63 字节、`AWS_ACCESS_KEY=AKIACHANGEME`、`AWS_SECRET_KEY=secretchangeme` | Secret 一律由外部密钥系统注入，并在启动时做长度/格式校验；占位密钥禁止进入任何可部署清单；密钥轮换有流程与演练 |
| P0-30 | Feeds 结果报告永不下载，被拒行永久丢失 | `amz-service-spapi/src/main/java/com/amz/client/FeedsClient.java`（378 行）只实现 4 步：createFeedDocument → PUT 上传 → createFeed → getFeedStatus；全类对 `resultFeedDocumentId` 与 `GET /feeds/2021-06-30/documents/{feedDocumentId}` 的命中为 **0**（`FEEDS_PATH`/`DOCUMENTS_PATH` 仅是 49–50 行的路径常量）；与 product 域 `ListingCopyService.java:236-242`（Feed `DONE` 即置 `SUCCESS`）是同一根因的两个断面：被拒的行没有任何地方能读到原因 | Feed 闭环必须包含结果报告下载、逐行错误解析与业务处置；未取到结果报告的 Feed 不得置为终态成功；失败行必须有可查询的错误清单与重提路径 |
| P0-31 | order/product 的 Redisson 硬编码第三方公网 Redis，且配置键在 Spring Boot 3 下失效 | `amz-service-order/src/main/java/com/amz/config/RedissonConfig.java` 与 product 同名文件字节完全相同（SHA256 `49A9179BE50381688E4588470F13FF6F0C49C8E86071A0B55EE8333CA756B72A`，35 行）：L16 `${spring.redis.host:121.37.250.15}`、L19 `${spring.redis.port:6379}`、L22 `${spring.redis.password:}`；该键全仓无定义——17 处 `redis:` 块父级全为 `spring.data.redis.*`，`SPRING_REDIS_*` 在 k8s / Compose / `.env.example` 命中 0，环境变量映射无法回填该 key；运行时探针（Redisson 3.37.0）实测 **45,292 ms** 后抛 `org.redisson.client.RedisConnectionException`（根因 `io.netty.channel.ConnectTimeoutException`，目标 `121.37.250.15:6379`），裸 TCP 对照 4,025 ms 不可达；使用点 order `OrderServiceImpl.java:68`（`@Autowired RedissonClient`）、product `TranslationService.java:68/153/157/166/170`；影响面**已收窄为 order/product 两模块**——spapi 虽引 `redisson-spring-boot-starter`（`pom.xml:127-131`）但无自定义 `RedissonConfig`，不受此硬编码影响 | 删除自定义 Bean 或改用 `spring.data.redis.*`；禁止任何默认值指向公网地址（默认只允许 `localhost` 或集群内 DNS，生产必须显式注入）；启动自检对 Redis 做一次带超时的连通性探测；补 `ApplicationContextRunner` 级别配置契约测试（断言解析出的 host/port/password 与本环境注入值一致） |
| P0-32 | 部署清单与代码占位符大面积不对齐，Compose 尤甚 | `docker-compose.yml` 实测 **31 个 service**、`env_file` **0** 次：`REDIS_HOST` 全仓 **0** 次、`RABBITMQ_HOST` 仅 order+finance、`MYSQL_HOST` 仅 spapi、`SPRING_PROFILES_ACTIVE` **0** 次；k8s 侧逐模块差集（spring 占位符 vs Deployment `env` 名）实测缺项：logistics 15 项（`AMZ_17TRACK_BASE_URL/KEY`、11 个 `AMZ_LOGISTICS_*`、`AMZ_TRACKING_ENABLED`、`NACOS_ADDR`、`SPRING_PROFILES_ACTIVE`）、search 10 项、product 7 项、user 5 项、procurement 5 项、ai 5 项、finance 4 项、ad 3 项、multiplatform 2 项、report 2 项、spapi 1 项、其余模块以 `NACOS_ADDR` 为主；`.env.example` 实测 71 行 / **36 个键**，缺 `NACOS_ADDR`、`MONGO_HOST`、`ES_URIS`、`OSS_*`、`KINGDEE_*`、`ALIBABA_*`、`AMZ_17TRACK_*`、`EMBEDDING_*` 等；反向亦成立——`amz-service-report` 的 `application.yml` 仅 52 行且无 datasource（靠 Feign 聚合），k8s 却注入 19 个 DB/Rabbit/Redis 变量，属过度注入（P0-07 已覆盖 report Compose 侧、P0-25 已覆盖 Nacos 变量名，此处**不重复计数**，只记“清单覆盖率”本体） | 以代码占位符为唯一事实源生成/校验清单：CI 加 `DeploymentManifestContractTest`，逐 Deployment 断言“代码读到的每个变量都有注入路径、且不存在未使用的注入项”；k8s / Compose / `.env.example` 三处同步；敏感值走外部密钥系统，仓库内只留合法长度的占位 |

> 关于 P0-04 的**诚实修正**：8 个 `OPERATE_TOOLS` 中，**只有 `cross_marketplace_listing` 已确认会真实写外部系统**（`ProductController.copyListing` → `ListingCopyService.createCopyTask` → `@Async executeCopyTaskAsync` → `listingsClient.submitFeed` → SP-API Feeds，且 `pollFeedStatus` 以 `Thread.sleep(15s)` 轮询最长 5 分钟）。`optimize_ad_campaign`、`optimize_listing_seo`、`optimize_shipping_route`、`optimize_inventory_distribution` 是只读查询 + 规则文本；`create_purchase_plan`（返回 `DRAFT` Map，`planNo=System.currentTimeMillis()`）与 `auto_reply_message`（返回草稿）都不落库；`generate_promotion_plan` 是 `@GetMapping("/promotion/plan")` + `@ShopScoped`，返回**硬编码**的 Lightning Deal 方案，**完全不写数据**。
>
> 这条修正很重要：它把“AI 工具可以写生产数据”的指控收窄到一条真实链路，同时也暴露了这条链路的两个新问题——`ListingCopyService` 第 55 行 `DEFAULT_PRODUCT_TYPE = "PRODUCT"` 是占位值，真实类目不符会被 Amazon 判为 FATAL；且该链路在 SSE worker 中执行时没有 `UserContext`，第 90 行的 `isShopAllowed` 校验会被 fail-open 绕过。

### 1.4 业务模型缺口

| 域 | 当前状态 | 生产缺口 |
|---|---|---|
| 订单 | 表主要是订单头；唯一键未包含 `shop_id/marketplace_id`；已有订单可能跳过更新；调度只拉有限时间窗和有限状态；事件去重可能阻止状态变化 | 订单头、订单行、地址、费用、退款、事件、状态机、增量同步、报表对账、Outbox |
| 库存 | `amz_fba_inventory` 主要是亚马逊快照；`avg7Days/avg30Days` 未完整填充；null 被当 0 可能导致 DOS 误判；upsert 先查后写 | 外部快照层、内部库存台账层、可售补货层；预占/扣减/冲销/调拨/退货；原子条件更新 |
| 采购 | 1688 真实客户端自述“未校准”（`Alibaba1688Signer:24`、`Alibaba1688TokenManager:26`、`Alibaba1688RealClient:37-38`）；token 未按店铺隔离（单值 `alibaba.refresh-token` + Redis 全局 key）；FBA FIFO 出库无行锁和条件更新；多批次取首条 | 供应商生命周期、收货、发票、付款、三单匹配、容差、批次和状态机 |
| 物流 | 有仓库和库存表，但出库、拣货、打包、面单、轨迹、调拨不完整；多仓保存非原子 | WMS 作业单据、库位、两阶段调拨、承运商和轨迹、库存移动账 |
| 客服 | 工单/RMA 表存在，但店铺归属校验和状态机不完整；PII 无生命周期；买家消息客户端 `MessagingApiRealClient` 无 SigV4 且端点与真实 SP-API Messaging 不符，缺 token 时静默返回空列表/false | SLA、RMA 收货/检验/退款/补发、邮箱与聊天 PII 治理、可审计操作 |
| 财务 | 结算解析要求过宽；一次加载全店明细；成本是加权平均；VAT 硬编码；金蝶“真实”客户端无条件返回 `KINGDEE_MOCK_` 占位号且凭证落 `SYNCING` 后无法重试（`FinanceServiceImpl:190-206`）；结算行 `row_key` 不含 `shop_id/currency` 却全局唯一，跨店同业务行静默丢失（`V2__settlement_detail.sql:22`） | 双分录账本、费用/退款/汇率/税务、FIFO、结算对账、期末关账、外部总账 |
| 广告 | 真实客户端为骨架：全局单套 profile/token（`AdvertisingApiRealClient:52-56`）、ClientId 可能为空串（182 行）、失败静默 `emptyList()`；未真正完成 refresh token 交换；分页、429、Retry-After、nextToken 不完整；服务返回硬编码 campaign | OAuth/profile 隔离、统一限流与分页、日指标唯一键、归因窗口、自动化护栏、变更审计 |
| 商品/Listing | 更新未同步 ES；MongoDB 属性与 MySQL 无一致性机制；A+ 永远 true；Feed `DONE` 即置 `SUCCESS` 但从不下载 `resultDocumentId` 结果报告、超时后任务永久停留 `SUBMITTED`（`ListingCopyService:236-257`）；`DEFAULT_PRODUCT_TYPE` 为占位值 | 版本化 Listing、Feed 结果闭环、Outbox 派生同步、ES 强制租户/店铺过滤 |
| 多平台 | Webhook 端点无守卫、无任何入站签名校验（全仓库仅有出站签名工具）；`shopId` 为空时按 platform 反查“最新创建的账号”造成跨租户错配；去重键仅为全局 `event_id`；`handleWebhookEvent` 只写日志；**真客户端只有 TEMU/TIKTOK/SHEIN**（`MultiplatformServiceImpl.java:632-635`、`706-712` 的 `switch(platform)` 只有这三个 case，其它平台串抛“不支持的平台”；`syncAll` 614-617 也只同步这三家），README 声明的 Shopify/eBay/Walmart/Shopee/Lazada **无任何实现**；默认 profile（非 `mock`）加载 `*RealClient`，且三家 RealClient 自述未校准、`fetchRecentOrders` 固定第 1 页（无翻页、无增量时间窗）、`markShipped` 内部 `cred(null)` 回退到该平台任意首个账号凭证（`PlatformCredentialService:23-24`） | 平台适配层（补实现或撤回宣称）+ 每平台验签与时间窗；事件唯一键 `(platform, shop_id, event_id)`；归属无法确定时拒绝落库；事件处理实现真实业务动作并可重放 |
| 消息/异步 | 部分消费者有手动 ack 与 DLQ，但通知消费者只记日志；分布式锁 Redis 不可用时 fail-open；异步线程丢上下文 | Inbox/Outbox、幂等消费、重试/DLQ/重放、锁 fail-closed、上下文传播 |

### 1.4.1 SP-API 能力缺口（第 10 轮关键词全仓扫描）

扫描范围：`*.java`、`*.yml`、`*.yaml`、`*.sql`，排除 `target/`。命中为 0 即代表仓库内**没有**该能力的代码、配置或依赖。

| 官方能力 | 关键词 | 命中 | 结论 |
|---|---|---|---|
| Notifications（SQS 事件） | `/notifications/v1` | 0 | 未实现；订单与授权变更只能靠轮询，与官方“优先 Notifications”的指引冲突 |
| Restricted Data Token | `restrictedDataToken` / `RestrictedDataToken` / `createRestrictedDataToken` | 0 / 0 / 0 | 未实现；订单 PII 字段无法走官方要求的 RDT 路径 |
| Listings Items API | `listings/2021-08-01` | 0 | 未实现；`ListingsRealClient`（94 行，`@Profile("!mock")`）只经 Feign 调 spapi `/spapi/feeds` 提交 `JSON_LISTINGS_FEED` |
| Product Pricing | `productPricing` | 0 | 未实现；价格与 competitive pricing 数据只能间接来自 Feed 或报表 |
| Catalog Items | `catalog/2022-04-01` | 0 | 未实现；类目与属性校验没有官方来源，`DEFAULT_PRODUCT_TYPE` 仍是占位值 |
| Sellers | `sellers/v1` | 0 | 未实现；无法校验授权范围内的 marketplace 参与状态 |
| FBA Inbound | `fba/inbound` | 0 | 未实现；补货建议无法落成真实入库计划 |
| 限流响应头 | `x-amzn-RateLimit-Limit` | 15 | 已有读取与解析（正向），但受 P0-28 的配额与隔离缺陷拖累 |
| LWA token 端点 | `amazon.com/auth` | 4 | 存在：`SpApiConfig.java:33`、spapi `application.yml:69`、`LwaTokenManagerTest.java:49`、`k8s/configmap.yaml:43`；其中 `k8s/configmap.yaml:43` 的 `AWS_LWA_ENDPOINT` **代码不读**（代码读 `spapi.lwa-endpoint`），是又一个死配置项 |

### 1.5 外部对标

下表 Star 数、最近推送时间与许可证于 **2026-09-24** 通过 GitHub REST API（`GET /repos/{owner}/{repo}`）实时核验；Star 数随时间变化，引用时应注明访问日期。它们只能作为工程参考，**不能**假定其许可证或能力适配本项目的商业使用。

| 项目 | Star | 最近推送 | 许可证 | 主要语言 | 可借鉴点 | 对“凭证即可用”做对了什么 |
|---|---|---|---|---|---|---|
| [amzn/selling-partner-api-models](https://github.com/amzn/selling-partner-api-models) | 912 | 2026-09-22 | Apache-2.0 | OpenAPI/Swagger | 字段名、枚举与 usage plan 的官方事实源 | 提供完整模型，可直接派生契约测试（P0-27 正是缺这一环） |
| [saleweaver/python-amazon-sp-api](https://github.com/saleweaver/python-amazon-sp-api) | 680 | 2026-09-22 | MIT | Python | 自动分页、throttle 感知重试、按端点限流 | 凭证 + 端点即可调用，限流表来自官方模型 |
| [jlevers/selling-partner-api](https://github.com/jlevers/selling-partner-api) | 437 | 2026-09-02 | BSD-3-Clause | PHP | 由 OpenAPI 生成客户端，避免手写 DTO | 生成式客户端天然跟随官方字段变更 |
| [penghaiping/amazon-sp-api](https://github.com/penghaiping/amazon-sp-api) | 139 | 2023-03-19 | 无 | Java | Java 侧早期参考实现 | 已停更，仅作历史参考 |
| [nplszfl/OmniTradeERP](https://github.com/nplszfl/OmniTradeERP) | 80 | 2026-09-24 | 无 | Java + Vue + TypeScript | 同类 Java ERP 的模块划分与页面组织 | 许可证缺失，不可直接取用代码 |
| [zach22-1999/lingxing-mcp](https://github.com/zach22-1999/lingxing-mcp) | 42 | 2026-07-14 | MIT | Python | 领星 ERP 的 MCP 封装形态 | 与 Amazon 直连无关，属第三方 ERP 集成 |
| [jackspeng/shop](https://github.com/jackspeng/shop) | 102 | 2020-06-30 | 无 | Java | MWS 时代 ERP 的历史结构 | 已停更且 MWS 已下线，不可参考 |
| [holodilina/spring-transactional-outbox-kafka](https://github.com/hodilina/spring-transactional-outbox-kafka) | 0 | 2026-07-16 | Apache-2.0 | Java | Spring 事务性 Outbox 的最小样板 | 与本文 4.4 的 Outbox 目标态直接对应 |
| [KubeRiva/OMS](https://github.com/KubeRiva/OMS) | 23 | 2026-05-11 | NOASSERTION | Python + TypeScript | 每组织独立 data-plane；SP-API 轮询 + fulfillment push；Webhook HMAC、退避、投递历史 | 连接器有投递历史与重试，失败可回放 |
| [openoms-org/openoms](https://github.com/openoms-org/openoms) | 25 | 2026-09-02 | NOASSERTION（实为 Elastic License 2.0，source-available） | Go + TypeScript + PLpgSQL | PostgreSQL RLS 多租户；Helm/Trivy/Playwright 齐备 | 多租户隔离下沉到数据库层；SP-API 仍标为开发中 |
| [wimoor-erp/wimoor](https://github.com/wimoor-erp/wimoor) | **1376** | 2026-09-24 | MIT（根目录；`wimoor-amazon/amazon-sp-api/` 子目录**无 LICENSE 文件**，其中 `SellingPartnerAPIAA/`、`documents/` 是 Amazon 官方 helper 拷贝，须按 Apache-2.0 单独标注） | Java + Vue + JS | 与本项目最贴近的 Java/Vue 亚马逊 ERP，业务域覆盖广；**每店铺持久化限流门控**与**文档解密/解压链**是本项目可逐行参照的实现（见 1.5.1 第 8、9 条） | 实测根 `pom.xml` 为 spring-boot-starter-parent **2.6.13** + `<java.version>9</java.version>`（前稿“Spring Boot 2.0/JDK 8”不准确）；框架偏旧不等于集成层不可用——可参照的是具体类，不是整体架构 |

> 纠错（推翻前稿表述）：此前草稿把 `KubeRiva/OMS` 描述为“纯 Python”、把 `openoms` 描述为“PostgreSQL RLS 多租户”的单栈项目，两处均不准确——前者是 Python + TypeScript，后者是 Go + TypeScript + PLpgSQL，且采用 source-available 许可证而非 OSI 宽松许可证。以本表为准。

#### 1.5.1 同类项目可借鉴实现（源码级，第 16 轮新增）

1.5 的表格是“项目级”对标；本小节下沉到**行号级**，只列本轮逐行复验过、且能直接映射到 1.9 / 第 4 章 / 实施任务的实现。第 10～11 条是**禁止照抄**的反例，同等重要。

| # | 借鉴点 | 出处（文件:行号） | 映射到 | 许可证与取用边界 |
|---|---|---|---|---|
| 1 | **凭证三来源可插拔**：构造器同时接受 `refresh_token`、`credentials`、`credential_providers`，调用方无需改代码即可在“静态刷新令牌 / 直接凭证 / 凭证提供链”之间切换 | `spapi-py/sp_api/base/client.py:24-47`（三个入参 L30/L32/L38，装配 L43-47） | 1.9 A4、P0-11（凭证单一事实源 + 轮换） | MIT（`spapi-py/LICENSE`，Copyright (c) 2020 Michael Primke）；可借鉴，须保留版权声明 |
| 2 | **传输层与业务层解耦**：HTTP 客户端抽成独立 `HttpxTransport`，业务客户端只依赖该抽象，测试时可整体替换为记录器 | `spapi-py/sp_api/base/client.py:63-67` | 计划 Task 6 `ConnectorRegistry` 与连接器自检端点 | 同上（MIT） |
| 3 | **防御性字段名兼容**：同一语义的多种官方拼写（`marketplaceIds`/`MarketplaceIds`/`marketplace_ids`/`MarketplaceId`）在入参层归一，避免大小写差异直接导致 400 | `spapi-py/sp_api/base/_core.py:26-47` | P0-27 同类问题（响应字段名靠手写必然漂移）；正解是**契约测试兜底**，不是无限兼容 | 同上（MIT） |
| 4 | **按 HTTP 状态码映射异常**：400/403/404/409/413/415/429/500/503/504 各有子类，未覆盖的状态码有兜底；异常携带原始 headers | `spapi-py/sp_api/base/exceptions.py:139-151`、`sp_api/base/_core.py:105-108` | 1.9 A2（缺凭证显式失败）、4.8“错误码透传” | 同上（MIT） |
| 5 | **每响应留存限流头**：`ApiResponse` 无条件把 `x-amzn-RateLimit-Limit` 存入 `rate_limit` 字段，供上层决策，而不是丢弃响应头 | `spapi-py/sp_api/base/ApiResponse.py:47` | 计划 Task 5 Step 3/4 | 同上（MIT） |
| 6 | **全端点契约测试（零凭证）**：用 AST 扫 `@sp_endpoint` 装饰器收集全部端点，参数化断言“每个端点至少被调用一次、HTTP 方法与路径正确”，完全不依赖真实平台 | `spapi-py/tests/api/test_mocked_clients.py`（`EndpointSpec:27`、`RequestRecorder:41`、fixture `:108-115`、`collect_endpoint_specs:156-185`、`iter_client_classes:188-198`、参数化 `:268-315`） | 计划 Task 3（契约测试范式）、Task 6（能力清单与实现一致性） | 同上（MIT） |
| 7 | **与官方模型对拍**：抓取 `selling-partner-api-models`，逐 operation 断言“模型里声明的端点，客户端必须存在” | `spapi-py/tests/api/test_model_schema_consistency.py`（`MODELS_REPO_REF:17`、`_ensure_models_repo:36`、`test_models_match_clients:249-309`） | 计划 Task 3；**本项目改进**：上游只锁 commit SHA，本项目同时锁 sha256（见下） | 同上（MIT）；被对拍的模型文件属 Amazon，Apache-2.0 |
| 8 | **限流头学习 + 每店铺持久化门控**：把 `x-amzn-RateLimit-Limit` 解析成 `restore`，连同上一次放行时间持久化到 `t_amz_auth_api_timelimit`；下次放行条件为 `restore == null \|\| 距上次放行秒数 × restore > 1`。异常且无响应头时降为 0.01，正常但无响应头时取 1.00 | `wimoor-amazon/amazon-boot/src/main/java/com/wimoor/amazon/auth/pojo/entity/AmazonAuthority.java:206-240`（读头 L210-216、缺头兜底 L218-230、回写 L232-239）、`.../auth/pojo/entity/AmzAuthApiTimelimit.java:70-77`（门控）；调用点显式绑定 operation | 计划 Task 5 Step 3/4（按店铺隔离 + 可恢复 + 去锁内 sleep） | wimoor 根目录 MIT（Copyright (c) 2022 深圳市深码创科技有限公司）；**但 `wimoor-amazon/amazon-sp-api/` 子目录无 LICENSE 文件**，其 `SellingPartnerAPIAA/`、`documents/` 为 Amazon 官方 helper 拷贝，须按 Apache-2.0 分开标注 |
| 9 | **下载即解密 + 解压的流式管线**：`FileInputStream → 解密流 → GZIPInputStream` 逐层包装，任一层失败即关闭已打开的流，不落中间明文文件 | `wimoor-amazon/amazon-sp-api/src/main/java/com/amazon/spapi/documents/DownloadBundle.java:60-82`（管线 L63-75、失败清理 L79-81）、`.../documents/impl/AESCryptoStreamFactory.java:23`（`AES/CBC/PKCS5Padding`）、`:90-91`（key/IV 均 base64 解码） | 计划 Task 4（Feeds 结果报告下载 + GZIP 解压） | 见上条：该子目录按 Apache-2.0 标注 |
| 10 | **反例——重试计数器是装饰器闭包级共享字典**：并发调用互相污染，且 `finally` 在成功路径也重置计数，语义混乱 | `spapi-py/sp_api/util/retry.py:4`（签名 `tries=10, delay=5, rate=1.3`）、`:23`（`tries_counter` 闭包级）、`:43-44`（`finally` 重置） | Task 5 的重试与退避**必须**用 `shopId:operationId` 维度状态 + 调用栈局部变量 | **禁止照抄**；其余 spapi-py 条目仍可借鉴 |
| 11 | **反例——放弃超时控制与空实现限流**：`getTimeOut()` 返回 `Long.MAX_VALUE`；`getRateLimitPermit()` 直接 `return null` | `wimoor-amazon/.../auth/pojo/entity/AmazonAuthority.java:344-348`、`wimoor-amazon/amazon-sp-api/src/main/java/com/amazon/spapi/SellingPartnerAPIAA/RateLimitConfigurationOnRequests.java:36-37` | Task 5 每个外部调用必须有独立超时；`UsagePlan` 不允许空实现，契约测试断言每个在用 operationId 都有非零 rate/burst | **禁止照抄** |

**明确不可取用的仓库（许可证硬约束）**

| 仓库 | 许可证 | 约束 |
|---|---|---|
| `penghaiping/amazon-sp-api` | 无 LICENSE | 无许可证即默认保留全部权利，**不可复制任何代码**，只可作历史结构参考 |
| `nplszfl/OmniTradeERP` | 无 LICENSE | 同上，仅作模块划分思路参考 |
| `openoms-org/openoms` | Elastic License 2.0（source-available，非 OSI） | 禁止将源码用于向第三方提供该软件的服务；本项目只读其架构文档，不取用代码 |

**契约快照锁定（2026-09-24 实测，供 Task 3 的 `contracts/README.md` 直接抄录）**

锁定 sha256 比锁定 commit SHA 更稳：上游 `main` 会漂移，被替换的 commit 可能被 GC，而哈希可自证。

| 模型文件（仓库内相对路径） | 字节数 | sha256 |
|---|---|---|
| `models/reports-api-model/reports_2021-06-30.json` | 83,685 | `d72db9e5280262a92933a0e45e2207c150272f1d66177b2517c20671d69c732c` |
| `models/feeds-api-model/feeds_2021-06-30.json` | 55,901 | `ab235b4a0e5ce21083b885dd4f2b8cae7a6f597d7adf2647b47b90d6f5098a16` |
| `models/orders-api-model/ordersV0.json` | 226,555 | `027ac6f5c97126647c6925db9be09f78c7c741cd1d8727a5367374a1846bedc5` |
| `models/product-fees-api-model/productFeesV0.json` | 49,426 | `d06ad35f909d8c0985845f21420c1f75599531465b27f46d4946f7d7f522fc35` |
| `models/finances-api-model/financesV0.json` | 134,109 | `d80e881091367b0eccd4bde3ce834ed08877d3cf51095239eb8b1e328c0d19d6` |
| `models/fba-inventory-api-model/fbaInventory.json` | 36,985 | `7c14bcdb22de8ca2df45e5a40f2a422cff344d45985a68b9515b2e800edcc5ab` |

> 抓取陷阱（实测）：`fba-inventory-api-model/` 下并不存在 `fbaInventory_2020-10-01.json` / `inventory_2020-10-01.json`，两者都只返回 **14 字节**的 `404: Not Found` 响应体；必须校验字节数与 sha256，不能只看 HTTP 状态码。正确文件名为 `fbaInventory.json`。

### 1.6 Amazon 官方约束摘要

以下为工程设计依据，具体条款以官方最新文档和法务意见为准：

- SP-API 使用 token bucket 限流；遇到 429 应按退避策略重试，不能硬编码固定 timer；可读取限流响应头但不能假定其总是存在。
- 优先使用 Notifications，而不是高频轮询；SQS 标准队列不保证顺序且可能重复，需按 `notificationId` 去重并支持乱序。
- 官方要求有备用机制：定期用 Report API 对账，不能只依赖实时通知。
- 库存应维护内部记录（基于订单/取消），并按官方允许的频率用 `getInventorySummaries` 核对；安全库存用于防止超卖。
- 订单建议混合策略：报表做历史批量，Orders API 做实时补充，Order Change 通知做即时更新。
- Data Protection Policy 的关键工程含义：订单 PII 在交付后 30 天内删除；非 PII 最长保留 18 个月；安全日志至少保留 12 个月；静态与传输中的 PII 都要加密，备份也要加密。
- 安全事件需在 24 小时内通知 Amazon；critical 漏洞 7 天内修复，high 30 天内修复；终止员工访问需在 24 小时内撤销。
- 参考资料：
  - https://developer-docs.amazon.com/sp-api/docs/notifications-api
  - https://developer-docs.amazon.com/sp-api/docs/usage-plans-and-rate-limits
  - https://developer-docs.amazon.com/sp-api/docs/optimize-calls-to-the-selling-partner-api
  - https://developer-docs.amazon.com/sp-api/docs/set-up-notifications-with-amazon-sqs
  - https://developer-docs.amazon.com/sp-api/lang-US/docs/guidance-to-address-key-security-controls-in-sp-api-integration
  - https://developer-docs.amazon.com/sp-api/docs/mcf-best-practices

### 1.7 十条生产准入门槛

1. 生产环境禁 mock；任何 mock/模拟依赖在 `prod` profile 启动时必须失败。
2. 身份、租户、店铺、字段权限全部 fail-closed；内部接口必须服务身份 + mTLS。
3. 订单、库存、财务、集成四大事实源完成闭环，并有可重放历史。
4. 所有跨服务写操作使用 Transactional Outbox；所有外部事件使用 Inbox 幂等和 DLQ/Replay。
5. 实时通知和报表对账双链路成立；对账差异可自动建案、分派、修复和关闭。
6. 凭证进入 KMS/Vault，按租户/店铺隔离并可轮换；PII 加密、最小化、限权、可删除。
7. HA、备份、PITR、RPO/RTO 有书面目标并完成恢复演练；基础设施有默认拒绝的网络和认证。
8. CI 强制门禁：编译、单测、集成测试、契约测试、迁移测试、安全扫描、镜像扫描、SBOM、E2E、部署验证。
9. SLO、告警、审计、不可否认、值班、演练和死信重放全部可执行；不是只写设计。
10. 模拟数据必须固定 seed、可重复生成并显式标记 `SYNTHETIC`；不得伪装成真实经营数据。

---

### 1.8 已核实为正向、整改时必须保持的设计

审计如果只列缺陷，会把“本来做对的部分”一起改坏。以下行为本轮已核实为正向，改造时必须保留或推广：

| 位置 | 已核实行为 | 为什么必须在整改中保持 |
|---|---|---|
| `amz-common/.../util/JwtUtil.java` `init()`（50–58 行） | 密钥为空/空白时抛 `IllegalStateException`，服务**拒绝启动**而不是降级 | 避免了“空密钥静默可用”这类最危险的配置事故；其他密钥/外部凭据应复制这一模式 |
| `SpapiController` 的 `/credential`（66–82 行）、`FeedsController` 的 `/submit`（38–56）与 `/status`（59–81） | 访问下游前显式 `isShopAllowed(shopId)` | 本仓库现成的正确范式；应收敛为统一拦截器，而不是让每个域各自实现 |
| `FeignAuthRelayConfig`（59–99 行） | 凭据透传有明确优先级：显式头 > 当前请求上下文 > `UserContext` 现签 >>> 都没有时不加凭据；并文档化了 `RequestContextHolder` 在 `@Async`/线程池中丢失的限制 | 跨服务身份链是闭环前提；后续改造在其上加服务身份与 mTLS，而不是绕开它 |
| `ProductServiceImpl`（41 行注释） | 已修复 `selectList(null)` 引起的跨店暴露 | 说明该缺陷类别已被团队识别；应补回归测试固化，而不是只留注释 |
| `SearchServiceImpl` 热搜实现 | ZSET 有 1000 条上限 + 7 天 TTL | 防无界增长；“上限 + TTL”应成为所有缓存的默认约束 |
| `OpsServiceImpl`（60–75、107–121、137–154 行） | 扫描类操作有 mock 门禁 | 与 P0-01 同向；应扩展为“生产启动检测到 mock 即失败” |
| `DistributedJobLock` | 注释与实现明确记录了 Redis 不可用时的 fail-open 行为 | 记录清楚才可能被修；但生产要求是 fail-closed，**不能把注释当作已缓解** |
| `DistributedJobLock.runWithLock` | 实测被 **10 个调度器**使用：ad 2（`AdReportSyncScheduler:62/69`、`BidScheduleExecutor:48/56`）、ai 1（`DailyReportScheduler:51/63/124`）、finance 1（`SettlementSyncScheduler:32/56/91`）、logistics 1（`LogisticsTrackingSyncScheduler:73/107/119`）、ops 1（`OpsMonitorScheduler:43/52/84`）、product 1（`KeepaCompetitorScheduler:60/68`）、spapi 3（`InventorySyncScheduler:69/78`、`OrderSyncScheduler:67/82`、`ReplenishmentScheduler:56/65`），多副本互斥成立 | 前几轮“k8s HPA 多副本会导致调度任务双跑”的猜测已被实测推翻（附录 E）；整改时不得把这些调度器换成无锁 `@Scheduled` |
| `OrderSyncScheduler`（80、82–85、116–131、276–320 行） | `@Scheduled(fixedDelay=15min)` + 14 分钟锁租期；Redis `setIfAbsent(dedupeKey,"1",14 天)` 做发布去重（注释核算 7 天窗 × 15 分钟 ≈ 600 次重复）；Redis 异常时降级为“不去重”而非丢单；金额全程 `String`/`BigDecimal` | 这是“宁可重复不丢单”的正确取舍；应在保留该语义的前提下叠加持久化 Inbox（4.4），而不是用 Redis 取代事实源 |
| `SpapiController` 的 `/credential`（69–82 行，校验在 74–80 行） | 除 `@ShopScoped` 外显式校验 `@RequestBody` 内嵌 `shopId` 的归属，因为切面只覆盖 `@RequestParam`/`@PathVariable` | “注解覆盖不到的地方手工补校验”的正向范式；应收敛为统一的 body/query/path 全量归属校验，而不是删除该手工校验 |
| `ShopCredentialStore` | 敏感字段 AES-256-GCM 加密后同时写缓存与 DB，`get()` 返回解密副本而非内部引用 | 加密与防泄漏语义正确；**不能**因此判定“凭证管理已完成”——全表加载（56 行）与启动静默降级（62–64 行）仍属 P0-11、P0-23 |
| `SpapiFeedsClientFallbackFactory` | Feign 降级返回 `Result.failure`，不伪造成功 | 与 4.1 “禁止静默降级”同向；应推广到 Messaging / Ads / Keepa / 17TRACK |

### 1.9 连接器「凭证即插即用」验收标准（API-Ready）

用户口径（2026-09-24 确认）：**暂时没有平台 API 凭证，但系统必须具备对接能力——一旦提供凭证即可直接使用**。这句话在工程上等价于下面 8 条可验收标准；任何一条不满足，都不得对外宣称“支持对接”。

| # | 标准 | 本仓库现状（反例） |
|---|---|---|
| A1 | **凭证即插即用**：按官方协议完整实现认证（LWA / SigV4 / OAuth / HMAC）、分页、限流、幂等与错误分类；提供合法凭证后无需改代码即可跑通 | 1688 / SHEIN / TEMU / TikTok 自述“未校准”；Messaging / Ads 无 SigV4 且为单套全局 token |
| A2 | **缺凭证显式失败**：返回明确错误码与可操作提示，**绝不**返回空列表 / null / false / 占位号 | `KeepaRealClient`、`LogisticsTrackingRealClient` 静默返回 null 或空轨迹；`KingdeeRealClient` 无条件返回 `KINGDEE_MOCK_` 占位号（P0-22） |
| A3 | **启动自检**：生产 profile 启动时校验每个启用连接器的凭证存在性与格式（如 `AMZ_CRYPTO_KEY` 必须为 32 字节 base64）、端点可达性、profile 非 mock；不通过即拒绝启动 | spapi 默认 `mock`（P0-24）；k8s Secret 的 `AMZ_CRYPTO_KEY` 为 34 字节，启动即崩（P0-29） |
| A4 | **凭证归属与轮换**：按 `tenant_id + shop_id + connector` 存储、加密落库、可轮换可吊销并有审计；禁止一套全局凭证服务多店 | finance / procurement / message / ad 仍是全局 `@Value` 单套凭证；SP-API 凭证表的多副本事实源与自动建表都不成立（P0-11、P0-23） |
| A5 | **以联调记录为准**：每个连接器必须提供沙箱或生产联调记录（请求样例、响应样例、错误码覆盖、限流验证），否则在能力清单与前端显示为“未接通” | 全仓无任何联调记录；`@Profile("!mock")` 与类名 `*RealClient` 被当作“已对接”的依据 |
| A6 | **能力清单与实现一致**：支持哪些 operation 就写哪些，未实现的（如 Notifications / RDT / Listings Items）明确标注“未实现” | README 宣称 Shopify / eBay / Walmart / Shopee / Lazada，实际只有 TEMU / TIKTOK / SHEIN（附录 G.2）；SP-API 缺 7 类能力（1.4.1） |
| A7 | **失败可重放**：调用失败进入持久化重试 / DLQ，原始响应加密留存，支持按店铺与时间窗重放 | 无 Outbox / Inbox（4.4）；Redis 去重不能作为唯一事实源 |
| A8 | **限流与配额真实**：按官方 usage plan 逐 operation 配置 rate 与 burst，按店铺隔离，可依据 `x-amzn-RateLimit-Limit` 动态调整 | `SpiRateLimiter` 默认值最高宽松约 120 倍（`feeds.createFeed`：官方 0.0083 req/s，本仓库兜底 1 req/s；`orders.getOrders` 约 60 倍），`feeds`/`fees`/`finances` 三个在用 endpointTag 无策略（P0-28） |

补充口径：

- **“有 API 就能用”不等于“有 API 就能跑对”**：A1–A4 只保证链路可通；业务正确性还需要 4.5 的验签、4.7 的对账与 7.7 的数据质量校验。
- 每个连接器必须同时通过 **A1–A8**；只通过 A1 只能标为“可联调”，不能标为“可生产”。
- 模拟数据（第 7 章）**不能**替代 A5 的联调记录：模拟数据验证的是系统内部行为，不是与平台的协议正确性。
- 前端与服务目录中的“已对接 / 已完成”标签必须以本节 8 条为唯一判定口径（附录 G.4、G.5 的使用规则）。
- 每个连接器的凭证字段、配置键、缺失行为、配置来源优先级与自检端点，见 4.8（逐连接器契约表）。

## 2. 目标架构、租户模型与运行单元

### 2.1 架构原则

1. **领域边界保留，部署单元收敛**：不把现有模块重新写成一个大单体，也不为了“微服务”而保留 15 个必须同时升级的 JVM。
2. **写路径强一致，读路径可扩展**：订单、库存、凭证、财务账本以数据库事务和 Outbox 为核心；报表、搜索、看板通过派生读模型异步构建。
3. **外部事件不可信**：Webhook、MQ、报表文件、第三方 API 响应都必须经过验签、格式校验、幂等和权限检查。
4. **默认拒绝**：身份、租户、店铺、字段、网络、密钥和调度锁的缺省状态都必须是拒绝。
5. **可重放**：任何异步链路失败都必须能从持久化事件或外部原始对象重新处理，而不是依赖日志或人工猜状态。
6. **可观测**：每个请求和事件必须具备 `trace_id`、`request_id`、`tenant_id`、`shop_id`、`actor/service_id`，并对敏感字段脱敏。
7. **先正确后自动**：自动化广告、库存补货、Listing 变更必须先在建议/干跑模式运行，具备护栏、审批和审计，再允许生产写入。

### 2.2 五个目标运行单元

| 运行单元 | 负责的现有模块/能力 | 扩缩策略 | 关键要求 |
|---|---|---|---|
| `erp-edge` | Gateway、鉴权边缘、BFF、WebSocket/SSE 入口 | 无状态水平扩展 | TLS、WAF、限流、身份剥离与重签、长连接会话治理 |
| `erp-core` | 用户/租户、订单、库存、商品、采购、物流、客服、运营、财务、报表聚合 | 核心事务服务，按 CPU/连接池扩展 | 事务边界、幂等命令、Outbox、审计、MySQL HA |
| `erp-integration` | SP-API、广告、多平台连接器、凭证、Webhook 接收、报表拉取 | 按外部端点限流扩展 | 每店铺 token bucket、分页游标、429 退避、原始响应留存 |
| `erp-intelligence` | 搜索、Embedding、AI Agent、推荐、分析计算 | 可独立扩容，允许降级 | 强制租户过滤、工具权限、提示注入防护、PII 脱敏 |
| `erp-worker` | 调度器、MQ 消费者、对账、DLQ/Replay、批处理、报表生成 | 按队列积压和任务时限扩展 | 幂等、租约/锁、重试分类、断点续跑、无状态恢复 |

说明：

- 这是**逻辑运行单元**，不是要求 P0 一次性完成五合一重构。P0 可以先修现有服务，P1 再通过 Spring Boot assembly/module packaging 或独立部署组渐进收敛。
- `erp-core` 不允许直接吞掉集成重试逻辑；外部调用必须通过 `erp-integration` 或明确的防腐层，避免长事务被第三方网络拖死。
- `erp-worker` 不允许持有不可恢复的本地内存状态；游标、租约、重试次数和输出必须持久化。
- 报表读模型可以放在 `erp-core` 的独立 schema/只读副本，不应阻塞订单写入。

### 2.3 统一请求与事件上下文

所有入口、服务调用、MQ 消息和异步任务都必须携带同一个不可变上下文：

```text
tenant_id       必需，首期固定为 1，但不得从请求 body 自由覆盖
shop_id         可选于平台级接口，业务接口必需并在授权范围内
user_id         人类用户身份；服务任务为空
service_id      服务身份；没有 user_id 时必须存在
actor_type      HUMAN | SERVICE | SCHEDULER | EXTERNAL_WEBHOOK
request_id      通过 API 幂等键关联
trace_id        端到端追踪
correlation_id  业务链路关联，例如订单号、作业号
```

执行规则：

- Gateway 移除所有外部传入的 `userId/tenantId/role/shops` 头，校验 JWT 后重写权威头；服务只信任来自受认证网关或服务网格的上下文。
- 服务间的 Feign/HTTP/MQ 调用必须通过统一客户端传播上下文；禁止依赖 `ThreadLocal` 默认值。
- 异步线程池必须使用 `TaskDecorator`/显式上下文参数传递；禁止异步任务回退到用户 1。
- 事件信封至少包含：`event_id`、`event_type`、`tenant_id`、`shop_id`、`aggregate_type`、`aggregate_id`、`aggregate_version`、`occurred_at`、`producer`、`trace_id`、`schema_version`、`payload`、`data_origin`。
- `data_origin` 在生产只能为 `AMAZON`、`PLATFORM`、`MANUAL`、`SYSTEM`；模拟环境必须为 `SYNTHETIC`。

### 2.4 租户与店铺隔离

#### 2.4.1 数据模型

- 所有业务表增加 `tenant_id BIGINT NOT NULL`；所有租户数据查询默认带 `tenant_id`。
- 所有店铺级表增加 `shop_id BIGINT NOT NULL`；关键唯一键必须包含 `tenant_id` 和 `shop_id`。
- 外部平台标识除非官方保证全局唯一，否则唯一键必须包含店铺和平台。
- 所有表增加 `version INT NOT NULL DEFAULT 0` 或等价乐观锁字段，用于并发写和冲突检测。

必须调整的代表性唯一键：

```text
amz_order            (tenant_id, shop_id, marketplace_id, amazon_order_id)
amz_order_item       (tenant_id, shop_id, marketplace_id, amazon_order_id, order_item_id)
amz_webhook_event    (tenant_id, platform, shop_id, event_id)
amz_unified_order    (tenant_id, platform, shop_id, platform_order_id)
amz_platform_account (tenant_id, platform, shop_id, account_id)
```

#### 2.4.2 查询与写入控制

- 使用 MyBatis 拦截器在 SQL 层强制追加 `tenant_id`，并拒绝没有租户上下文的数据库访问。
- 关键写命令以 `(tenant_id, shop_id, aggregate_id, expected_version)` 为条件；更新失败返回冲突，而不是静默覆盖。
- 禁止在 Controller 接受 `tenantId` 作为可信参数；从认证上下文获取。
- 缓存键、分布式锁键、ES 文档和对象存储路径都必须包含 `tenant_id`；搜索查询必须在 BM25 和 kNN 两条路径都加租户过滤。
- 对于 SaaS 高风险租户，提供“独立 database/schema”隔离档位；普通租户可采用共享表 + 强制租户谓词。
- 数据库层尽可能用只读账号、最小权限账号和迁移账号分离；禁止业务服务使用 root。

#### 2.4.3 测试要求

必须存在可自动运行的跨租户/跨店铺负向测试：

- 伪造 `shopId`、`userId`、`tenantId` 请求头/参数/body 字段，全部拒绝。
- 空授权店铺列表、空角色、缺失 JWT claim、缓存不可用、权限服务超时，全部拒绝敏感操作。
- 同一 `amazon_order_id` 在不同店铺/市场下不会冲突。
- 一条 Webhook 重复投递、乱序投递、跨店铺重放均不会污染其他租户。
- 内部接口在没有 mTLS 或服务 JWT 时不可达。

### 2.5 身份与信任边界

- 人类用户：OIDC Authorization Code + PKCE；生产建议使用 Keycloak 或等价 IdP。
- 服务：mTLS + 短期 service JWT；JWT 只用于授权声明，不能替代传输身份。
- 网关：边缘唯一公网入口；内部服务不暴露公网端口。
- 管理面：Nacos、MySQL、Redis、RabbitMQ、ES、Grafana、Actuator、Swagger 只通过 VPN/零信任或集群内网访问。
- 高风险动作：凭证读取、退款、价格/库存批量写入、广告预算变更、权限变更需要二次授权或审批。
- 审计身份：所有人类操作记录用户、角色、店铺、来源 IP、设备和请求 ID；服务操作记录服务身份和部署版本。

### 2.6 数据所有权

| 数据 | 权威写入方 | 派生/读取方 | 说明 |
|---|---|---|---|
| 订单头/行/事件 | `erp-core` 订单域 | 财务、库存、客服、报表 | 外部同步只产生命令，不直接改多域状态 |
| 库存台账/预占 | `erp-core` 库存域 | 订单、采购、物流、广告 | 亚马逊快照是输入，不是唯一事实 |
| 结算/费用/凭证/成本层 | `erp-core` 财务域 | 报表、税务、外部总账 | 凭证必须借贷平衡，关账后只允许调整分录 |
| 店铺与凭证 | `erp-core` 用户/凭证域 | `erp-integration` | 凭证加密、版本化和轮换 |
| 外部原始响应/报表文件 | `erp-integration` | 对账、审计、重放 | 加密、最小化、按保留期清理 |
| 搜索/推荐/AI 读模型 | `erp-intelligence` | 前端、运营 | 可重建，不参与交易事实 |
| Listing/Feed 结果 | 商品域 + `erp-integration` | 运营、审计 | 以 Amazon Feed 结果为准做收敛 |
| 审计日志 | 安全/审计域 | 合规、事故响应 | 只追加、脱敏、独立保留 |

### 2.7 数据一致性模式

- **Transactional Outbox**：业务事务内写业务表和 outbox；独立 relay 投递到 MQ/Kafka/外部系统。
- **Inbox**：消费者在去重表内记录 `consumer_group + event_id + tenant_id`，处理成功才提交，重复消息直接忽略或返回已处理结果。
- **Saga/Process Manager**：跨订单、库存、财务、物流的长事务使用显式状态机和补偿动作，不依赖分布式数据库事务。
- **对账**：外部系统与内部事实源按日/按小时比对，差异生成 `reconciliation_case`，有负责人、期限和关闭证明。
- **重放**：死信和失败事件先进入可检索的失败存储，再通过受控 Replay 工具按租户/店铺/时间范围重放。
- **版本与冲突**：聚合根使用 `version`；外部系统覆盖内部人工修改时，必须记录冲突并提供人工解决入口。

---

## 3. 业务域升级设计

### 3.1 订单域

#### 3.1.1 目标模型

订单不能只是一行订单头，必须拆为可对账的事实集合：

- `order`：订单头、店铺、市场、平台状态、内部状态、币种、金额汇总、版本。
- `order_item`：订单行、SKU/ASIN、数量、单价、折扣、税费、商品状态。
- `order_address`：收件人/地址的加密存储与最小化视图。
- `order_fee` / `order_adjustment`：佣金、履约费、运费、促销、退款、赔付、仓储费、广告归因费等。
- `order_event`：平台事件、状态变化、人工操作、同步批次和原始摘要。
- `order_refund` / `order_return`：退款、退货、换货、补发及原因。
- `order_reconciliation`：内部金额/状态与 Amazon Report 的比对结果。

#### 3.1.2 同步策略

采用 Amazon 官方建议的混合链路：

1. **历史批量**：使用 Reports API 拉取订单和财务报告，按店铺/市场/日期分片，保存原始对象和游标。
2. **增量实时**：Orders API 作为通知缺失时的补充；使用 `LastUpdatedAfter` 等增量条件，不重复扫全量。
3. **即时变化**：Order Change Notifications 推送到 SQS，按 `notificationId` 去重、允许乱序，用聚合版本合并。
4. **定期对账**：每日或按业务风险频率，用报表与内部订单/金额比对；差异进入 `reconciliation_case`。

关键规则：

- 已存在订单不能无条件跳过；必须比较 `amazon_order_id + shop_id + marketplace_id + version`，允许状态、费用、地址和行项目更新。
- 状态去重不能只看订单号和时间窗；需要看事件 ID、聚合版本和字段变化。
- 订单状态机由内部状态驱动，平台状态只作为输入；取消、退款、部分发货、退货不能依赖单一字符串。
- 订单进入财务和库存系统只通过 Outbox 事件；禁止同步器直接跨库写财务/库存。
- 金额字段必须明确币种、精度和舍入规则；金额汇总必须能从行、费用和调整项重算并校验。
- 收件地址仅在履约必需时保留；默认脱敏视图，原件进入 PII vault 并按保留期删除。

#### 3.1.3 状态机（示意）

```text
RECEIVED -> VALIDATED -> ALLOCATED -> FULFILLING -> SHIPPED -> DELIVERED -> CLOSED
     |           |            |            |          |
     v           v            v            v          v
 CANCELLED   ON_HOLD     BACKORDER    EXCEPTION   RETURNED
```

- 每次状态迁移必须校验前置状态、操作者、店铺权限和幂等键。
- 非法迁移返回冲突并记录审计，不允许“直接 UPDATE 状态”。
- 退款、退货和补发是独立子流程，不直接回滚已发生的财务凭证；通过调整分录处理。

### 3.2 库存域

#### 3.2.1 两层模型

**外部快照层**：保存 Amazon/FBA/第三方平台返回的库存摘要、时间戳、来源和原始响应，用于对账和展示。

**内部事实层**：保存库存移动账本和余额，是所有可售、预占、采购和履约决策的依据。

建议的库存桶模型：

| 桶 | 含义 |
|---|---|
| `on_hand` | 实际在库 |
| `reserved` | 已预占但未扣减 |
| `allocated` | 已分配给订单/作业 |
| `inbound_in_transit` | 在途 |
| `inbound_receiving` | 已到仓待收货 |
| `available_to_promise` | 可承诺销售的数量 |
| `unfulfillable` | 不可售 |
| `damaged/lost` | 损失/损坏 |
| `platform_available` | 平台侧快照数量，仅作参考 |

口径必须固定：

```text
available_to_promise = on_hand - reserved - allocated - safety_stock + confirmed_inbound
projected_available  = on_hand - reserved - allocated - safety_stock
```

- 平台刷新频率必须遵守 Amazon 文档和端点限流，不能用高频轮询掩盖模型缺失。
- 所有扣减/预占使用条件更新或行锁，例如 `UPDATE ... WHERE available >= ? AND version = ?`；更新行数为 0 必须视为冲突。
- 同一仓库、同一 SKU 的多批次不能使用 `list.get(0)`；按批次策略（FIFO/FEFO）和成本层显式选择。
- 负库存必须被数据库约束或业务校验拒绝；若外部快照出现负数，进入异常队列，不直接覆盖内部账本。
- 库存健康度必须区分 `UNKNOWN/NEW/HEALTHY/LOW/OVERSTOCK`；缺失数据不能当 0 参与 DOS 计算。
- 每日/定期对账 `platform_available` 与内部台账；差异自动建案，不自动覆盖人工调整，除非有明确规则和审批。

### 3.3 采购、供应商与三单匹配

采购域的目标不是“能下单”，而是可追溯的采购到付款闭环：

1. 供应商准入、资质、付款条款、币种、税率和有效期。
2. 采购订单（PO）行、价格、交期、批次和收货容差。
3. 收货单/入库单与批次、库位、质检结果。
4. 供应商发票、付款申请、付款凭证。
5. 三单匹配：PO、收货、发票；支持数量/价格/税率容差。
6. 差异处理：短装、超收、价格差异、质量扣款、退货。
7. 付款状态、对账状态和供应商余额。
8. 与财务账本通过 Outbox 事件连接，不在采购事务内直接生成不可审计凭证。

**现状（本轮实测，2026-09-24，作为本节目标的整改输入）**：

- `Alibaba1688RealClient`（`@Profile("!mock")`）在 37–38 行自述“签名 / token 端点尚未经 1688 官方沙箱校准”；`Alibaba1688Signer:24` 标注“未校准”，列出两项待沙箱确认（`sign_method=sha1` 是整体 SHA1 还是 HMAC-SHA1；参数值是否需先 URL-encode）；`Alibaba1688TokenManager:26` 同样标注“未校准”（token 端点与 grant 参数名待确认）。
- **凭据是单套全局值**：`alibaba.refresh-token`（`Alibaba1688RealClient:59`）为单个 `@Value` 注入；`Alibaba1688TokenManager` 的 Redis key 是全局常量 `alibaba:open:access_token` / `alibaba:open:refresh_token`（34–35 行），无 tenant/shop 维度 → 多店铺同时使用会互相覆盖 token。
- 接口 javadoc 与实现不一致：`Alibaba1688Client:15` 写“签名方式：SDK 内置 HMAC-SHA1”，实现默认 `sign_method=md5`（`Alibaba1688RealClient:188/195`）。
- **正向保留**：凭证缺失时抛 `IllegalStateException`（诚实失败，不再返回 `1688_MOCK_` 假订单号，29–30 行）；`submitTo1688` 采用“Outbox-lite”预写 `SUBMITTING` 状态（`ProcurementServiceImpl:69-74`），能阻止崩溃后重复下单。但它依赖人工核对 1688 后台推进状态，**不是**真正的 Outbox 表，也没有投递重试与重放（见 4.4 现状）。

### 3.4 物流/WMS

- 仓库、库区、库位、容器、批次和库存移动账。
- 入库：预约、到仓、收货、质检、上架。
- 出库：波次、拣货、复核、打包、称重、面单、交接、发运。
- 调拨：创建调拨单 -> 源仓预占 -> 发出 -> 在途 -> 目的仓收货；两阶段状态，不允许单边扣减。
- 退货入库：收货、检验、处置（良品/次品/报废/退供）。
- 承运商、渠道、面单号、轨迹事件、异常件和理赔。
- 打印面单和出库动作必须有幂等键，重复点击不能重复扣库存。

### 3.5 客服、RMA 与 PII

- 工单、消息、邮件、差评、RMA、退款、补发和换货都绑定 `tenant_id/shop_id/order_id`。
- SLA 必须有开始时间、截止时间、暂停原因、升级路径和责任人。
- 工单回复、接收、关闭必须校验店铺归属和操作权限。
- RMA 状态机至少覆盖：申请 -> 审核 -> 退货在途 -> 收货 -> 检验 -> 退款/补发/拒绝 -> 关闭。
- 客服原始邮件/聊天含 PII 时进入受限存储，前端默认掩码；下载、导出、查看原文需要理由和审计。
- 评价与负面舆情可以分析，但不能把买家 PII 复制到宽表、搜索索引或日志。
- 任何“按订单号查买家”的操作都必须走权限和审计，不能靠前端隐藏按钮。

### 3.6 财务、结算与利润

#### 3.6.1 账本模型

采用双分录账本，最小实体包括：

- `ledger_account`：科目及类型。
- `journal_entry`：凭证头、日期、币种、状态、来源。
- `journal_line`：借贷方向、金额、币种、店铺、订单/费用关联。
- `settlement` / `settlement_detail`：平台结算批次与明细。
- `fee` / `tax` / `exchange_rate`：费用、税务、汇率。
- `cost_layer` / `cost_consumption`：FIFO/加权成本层和消耗记录。
- `profit_snapshot`：可重算、带口径版本的利润快照。

硬约束：

- 每张凭证借贷必须平衡；不平衡拒绝提交。
- 期末关账后，历史凭证不可直接改写，只允许红冲或调整分录。
- 退款、索赔、广告、仓储、订阅、FBA 费和促销费都必须能追溯到凭证行。
- 平台结算文件解析必须校验必需列、币种、日期、金额精度和重复行；不满足即隔离，不能“按三列凑合入库”。
- 结算同步调度默认开启，按店铺和批次游标运行；列表查询必须分页或限制数量。
- 金蝶/其他总账连接器必须区分 mock 和真实实现；生产禁用 mock 标识。

**现状（本轮实测，2026-09-24，作为本节目标的整改输入）**：

- **金蝶是假真实**：`KingdeeRealClient`（`@Profile("!mock")`）的 `syncVoucher` 在 48–51 行无条件返回 `"KINGDEE_MOCK_" + System.currentTimeMillis()` 占位号并只打 warn；finance 的 `application.yml:6-10` 默认 profile 为空（`${SPRING_PROFILES_ACTIVE:}`），即**默认加载 Real 客户端**并走占位分支。
- **凭证状态机死锁**：`FinanceServiceImpl.syncToKingdee` 的原子认领条件是 `.in("kingdee_sync_status", "PENDING", "FAILED")`（190–194 行），而 mock 分支把状态写成 `SYNCING`（205–206 行）。该凭证此后既不满足认领条件、也没有任何后台任务会把它推进到 `SYNCED`——代码注释声称“待真实 API 接入后自动转为 SYNCED”，实际**不存在这条路径**。同时 `V1__init.sql:19` 的列注释仍写 `PENDING/SYNCED/FAILED`，DDL 与运行态已漂移。
- **结算行会跨店静默丢失**：`SettlementParser.rowKey`（136–153 行）的指纹是 `settlementId|orderId|sku|amountType|amount|depositDate` 的 MD5，**不含 `shop_id`、`currency`、`transaction_type`**；而 `V2__settlement_detail.sql:22` 是全局 `UNIQUE KEY uk_row_key (row_key)`。不同店铺（或同店铺不同币种）的同一业务行会被当作重复行丢弃，`SettlementServiceImpl.ingestParsedRows`（111–170 行）的批内/跨批去重不会察觉，结算合计随之少算且没有差异告警。
- **金额精度**：解析端用 `BigDecimal` 保留原始精度，落库列是 `DECIMAL(14,2)`（`V2:16`），写入前 `setScale(2, HALF_UP)`（`SettlementServiceImpl:164`）。对 JPY/KRW 等 0 位小数币种可接受，但多币种场景会累积舍入误差，且列宽没有按币种区分。
- **正向保留**：按表头名（而非列序）定位、行级容错（空值/非数字只记行错误而不中断整批）、批内 + 跨批去重、多币种告警——这些设计在改造时必须保留（`SettlementParser:69-115`、`SettlementServiceImpl:111-170`）。

#### 3.6.2 成本与利润

- 成本层按采购批次/入库批次建立，FIFO 消耗必须有唯一键和审计。
- 利润口径固定为：收入 - 商品成本 - 履约成本 - 佣金 - 广告 - 仓储 - 税费 - 退款/赔付 + 其他调整。
- 多币种需要保存原币金额、结算币金额、汇率来源、汇率日期和折算规则。
- VAT/销售税按国家/站点、税码、生效日期和申报周期计算；税率和阈值不得硬编码在业务代码。
- 利润报表有“估算”“已结算”“已关账”三种口径，前端必须显示口径，不能把估算值当最终利润。

### 3.7 广告域

- OAuth 授权、profile、店铺和 marketplace 隔离；token 加密并支持刷新和撤销。
- 统一客户端处理分页、429、Retry-After、限流预算、超时和错误分类。
- 每天抓取 campaign/ad group/keyword/target/search term 指标，落在 `ad_metric_daily`，唯一键包含店铺、profile、日期、实体类型、实体 ID、归因窗口。
- 归因窗口和时区必须显式保存；跨日数据不能被覆盖成不可追溯的汇总。
- 自动化动作必须经过：建议 -> 干跑 -> 护栏 -> 审批/限额 -> 执行 -> 结果校验 -> 审计。
- 护栏至少包含预算上限、竞价上下限、单日变更次数、ROAS/ACOS 阈值和异常回滚。
- 每次写入 Amazon 广告接口都要生成 `ad_change_audit`，重复请求使用幂等键。

### 3.8 商品、Listing 与搜索

- Listing 采用版本化模型：草稿、校验、提交、Feed 处理中、成功、失败、回滚。
- Amazon Feed 必须保存 feed_id、提交时间、处理状态、结果报告对象和失败明细；超时不能只打 warn。
- 商品 MySQL 事实源变更通过 Outbox 派生到 ES/MongoDB；反向同步必须经过显式命令，禁止双写无一致机制。
- A+ 状态必须来自真实接口结果，不能默认 true。
- 搜索索引文档必须包含 `tenant_id/shop_id/marketplace_id/visibility`；BM25 和 kNN 都要带过滤。
- 对消费者搜索、运营搜索和 AI 检索分别定义可见范围；AI 不得越过用户店铺权限。
- 索引重建采用别名切换，禁止在线上直接删除索引；嵌入生成失败时可降级 BM25。

**现状（本轮实测，2026-09-24，作为本节目标的整改输入）**：

- **索引名单点硬编码**：`ProductDoc.java:17` 声明 `@Document(indexName = "amz_product")`，`SearchServiceImpl.java:215/227/268` 三处 `IndexCoordinates.of("amz_product")` 直接写死；没有别名（alias）与重建流程，无法做不停机的 mapping 变更。
- **无租户/店铺过滤（对应 P0-17）**：`ProductDoc` 已存 `shopId`（第 52 行）与 `userId`（第 56 行），但 BM25（`bm25Search`）、kNN（`hybridSearch`）与 RRF 融合三条路径**都没有把这两个字段加入查询过滤**，融合后也没有二次过滤。结果是任何登录用户的搜索请求都会跨店铺召回。
- **mapping 与集群能力未固化**：`ik_max_word`/`ik_smart`（`ProductDoc.java:27/31/35`）要求 ES 预装 IK 分析插件，但 `docker-compose.yml:137-150` 的 `elasticsearch:8.15.3` 是官方镜像、未挂载任何插件；仓库内也没有 index template / ILM / 自定义分词器定义（全仓库仅 `ProductDoc` 注解里出现 analyzer）。官方镜像不含 IK：首次写入创建索引会因未知分析器失败，除非手工装插件。`docs/es-native-rrf-check.md` 描述的“索引已存在且有 embedding 数据”是**验证前置条件，不是已完成的事实**。
- **连接与超时未配置**：`amz-service-search/src/main/resources/application.yml:27-28` 只有 `spring.elasticsearch.uris: ${ES_URIS:http://localhost:9200}`，无 connect/socket 超时、无连接池上限、无认证与 TLS 配置（`application-local.yml` 同样只有裸 `uris`）；compose 里 ES 显式 `xpack.security.enabled=false`，属于**仅限本地**的配置。
- **向量维度与模型耦合**：`ProductDoc.java:59` 硬编码 `dims = 1024`，更换 embedding 模型必须重建索引；`embedding.enabled` 默认 `false`（`application.yml:32`），默认检索路径是纯 BM25。
- **分页与结果窗口**：查询只使用 `withMaxResults(bm25Top|knnTop|rrfFinal)`（默认 50/50/20，已外置为 `search.retrieval.*`），没有 `from/size` 深分页，也没有显式设置 `index.max_result_window`；`es-native-rrf-check.md` 里的 `rank_window_size` A/B 只有 playbook、没有执行结果。
- **Feed 结果闭环未闭合**：`ListingCopyService.pollFeedStatus`（208–259 行）在 `DONE` 时直接置 `SUCCESS`（236–242 行），**从不下载 `resultDocumentId` 的结果报告**——全仓库该字段只出现在 `ListingsMockClient:43`（Mock 随机生成）与 `ReportsRealClient:80`（报表链路），Listing 链路没有任何处理器；`FATAL/CANCELLED` 时置 `FAILED` 但不保存失败明细（243–249 行）；5 分钟超时只 `log.warn` 后 `return`（253–256 行），任务**永久停留在 `SUBMITTED`**，没有重试、告警或人工确认入口。
- **productType 是占位值**：`DEFAULT_PRODUCT_TYPE = "PRODUCT"`（55 行），注释自述真实场景需按源 Listing 类目填充（如 LUGGAGE/SHOES/HOME），错误值会被 Amazon 直接判 FATAL。

### 3.9 跨域事件清单

至少定义并版本化以下事件：

```text
order.received.v1
order.updated.v1
order.cancelled.v1
order.item.allocated.v1
inventory.changed.v1
inventory.adjusted.v1
inventory.reconciled.v1
listing.feed.submitted.v1
listing.feed.completed.v1
settlement.received.v1
fee.discovered.v1
refund.approved.v1
procurement.po.confirmed.v1
procurement.received.v1
logistics.shipment.created.v1
logistics.tracking.updated.v1
rma.status.changed.v1
ad.metric.ingested.v1
ad.change.executed.v1
reconciliation.case.opened.v1
reconciliation.case.closed.v1
```

每个事件必须由 schema registry 或等价机制校验；生产消费者只接受向后兼容的版本升级，破坏性变更发布新版本。

---

## 4. 外部集成、事件一致性与对账

### 4.1 连接器统一能力

所有外部连接器必须实现相同的抽象能力：

- 认证与 token 生命周期管理；按租户/店铺/profile 隔离。
- 统一请求上下文：tenant、shop、marketplace、endpoint、operation、idempotency key。
- 统一限流：按 `tenant_id + shop_id + marketplace_id + endpoint` 维护 token bucket。
- 统一重试：仅对可重试错误重试；区分 4xx 参数错误、401/403 授权错误、429 限流、5xx 平台错误和网络错误。
- 统一分页：保存 `nextToken`/游标和断点，不允许半途丢失。
- 统一超时、熔断、并发隔离和背压。
- 统一原始响应留存：加密、脱敏、带过期时间，供对账和重放。
- 统一指标：请求量、成功率、延迟、限流命中、重试次数、最后成功时间、游标滞后。
- 生产启动时检查连接器配置；缺失密钥、mock 标记或未验证的端点直接拒绝启动。

**现状（本轮实测，2026-09-24）：连接器真伪全盘点**

| 连接器 | 实现与 profile | 本轮核实事实 | 生产可用性 |
|---|---|---|---|
| SP-API 核心链路（Orders / Feeds / FBA Inventory / Reports / Finances / Fees） | `OrdersClient`、`FeedsClient`、`FbaInventoryClient`、`ReportsClient`+`ReportsRealClient`/`ReportsMockClient`、`FinancesClient`+`FinancesRealClient`/`FinancesMockClient`、`FeesClient`+`FeesRealClient`/`FeesMockClient`（spapi 模块，默认 profile `mock`） | **框架真实**：LWA refresh_token 换 access_token（`https://api.amazon.com/auth/o2/token`，3600s）+ AWS SigV4 签名 + `x-amzn-RateLimit-Limit` 解析 + 429 退避已实现；跨模块 Feign 双向核对通过（product `SpapiFeedsClient` ↔ `FeedsController`；finance `SpApiFinanceClient` ↔ `FinancialDataController`）；`SpapiFeedsClientFallbackFactory` 返回 `Result.failure` 不伪造成功；`/sync/orders` 无凭证时显式失败（`SpapiController:94-96`）。**但**：凭证表无自动建表路径（P0-23）、部署形态默认加载 mock（P0-24）、`ReportsRealClient:80` 字段名错误使文档 ID 恒 null（P0-27）、限流配额最高比官方宽松约 120 倍且不按店铺隔离（P0-28）、Feeds 不下载结果报告（P0-30）、缺 Notifications/RDT 等 7 类能力（1.4.1） | LWA/SigV4/限流**框架**真实实现 ✅；凭证表缺失 ⛔、默认 mock ⛔、Reports 字段名错 ⛔、限流形同虚设 ⛔ |
| 金蝶云星空 | `KingdeeRealClient`（`!mock`）/ `KingdeeMockClient`（`mock`） | `syncVoucher` 48–51 行无条件返回 `KINGDEE_MOCK_` 占位号；finance `application.yml:6-10` 默认 profile 为空 → 默认加载 Real 实现；凭证被置 `SYNCING` 后无法再次认领（认领条件只接受 `PENDING/FAILED`） | 未对接（占位） |
| 1688 | `Alibaba1688RealClient`（`!mock`） | 37–38 行自述签名/token 端点未校准；`Alibaba1688Signer:24`、`Alibaba1688TokenManager:26` 同类标注；凭据为单套全局值（`alibaba.refresh-token` + Redis 全局 key） | 未校准 · 单租户 |
| SHEIN | `SheinRealClient`（`!mock`） | 56–58 行硬编码 `page_no=1`、`page_size=50`、`order_status=PAID`；103–106 行自述未校准；`markShipped` 以 `cred(null)` 解析凭证 | 未校准 · 可能用错店铺 |
| TEMU | `TemuRealClient`（`!mock`） | 50–51 行固定 `page=1`、`page_size=50`；94–96 行自述未校准；65–66 行 `cred(null)` | 同上 |
| TikTok Shop | `TikTokRealClient`（`!mock`） | 67–68 行 `cred(null)`；177–180 行三项待沙箱确认（待签字符串拼接顺序、body 是否参与签名、timestamp 单位） | 同上 |
| SP-API Messaging | `MessagingApiRealClient`（`!mock`） | 单套 `spapi.lwa.access-token`（41 行）；127–128 行只带 `Authorization: Bearer` 与 `x-amz-access-token`，**没有 AWS SigV4 签名**；`/messaging/v1/orders`、`/messaging/v1/messages/{id}/read` 与真实 SP-API Messaging 资源模型不符；失败返回空列表/false（65、81、102、121 行） | 不可用（需重写） |
| Amazon Ads | `AdvertisingApiRealClient`（`!mock`） | 全局 `advertising.profile-id` + `spapi.lwa.access-token`（52–56 行）；182 行 `Amazon-Advertising-API-ClientId` 在 profileId 不含 `:` 时返回空串；失败静默 `emptyList()`/`false`（92–96、124、148、174 行） | 骨架 |
| Keepa | `KeepaRealClient` | apiKey 为空只打 `log.debug` 并返回 null；非 200 与异常同样返回 null | 静默空值 |
| 17TRACK | `LogisticsTrackingRealClient` | javadoc 27 行自述“三层降级，保证没有凭证也不影响系统运行”；不可用/异常一律返回空轨迹（76–102 行，326–334 行列原因） | 静默空值 |

由此得到两条对本节目标态的硬性要求：

1. **验收口径必须以联调记录为准**：`@Profile("!mock")` 与类名 `*RealClient` 都不是“真实对接”的证据；每个连接器必须提供沙箱/生产联调记录 + 校准测试，否则在能力清单与前端上显示为“未接通”。
2. **静默降级必须显式化**：返回空列表/null/false 会让上游把“拿不到数据”当成“没有数据”，必须改为带状态的错误（可重试 / 需人工 / 已降级 `DEGRADED`）并产生指标与告警。

### 4.2 SP-API 三链路

| 链路 | 用途 | 触发方式 | 必须保存的状态 |
|---|---|---|---|
| 实时通知 | 订单变化、授权撤销等即时事件 | SQS/通知 | notification_id、receipt_handle、处理状态、原始摘要 |
| 增量拉取 | 通知缺失、窗口补齐、人工重试 | 调度/命令 | 店铺、市场、时间窗、nextToken、最后成功时间 |
| 批量对账 | 历史、财务、库存、订单对账 | 定时报表 | report_id、document_id、文件哈希、解析结果、差异 |

- 收到 429 时读取 `Retry-After`，否则使用带抖动的指数退避；不能只固定 sleep。
- 不能假设响应头 `x-amzn-RateLimit-Limit` 一定存在；存在时用于调整预算，不存在时仍按本地保守预算。
- SQS 标准队列允许重复和乱序；按 `notificationId` 持久化去重，并允许状态版本倒序到达。
- 任何 Notification 处理失败都必须进入重试/DLQ，不得直接 ack 丢弃。
- 对账不是可选优化，而是生产必须具备的备用机制。

**现状（第 10 轮实测，作为设计输入）**

- 实时通知链路**不存在**：全仓 `/notifications/v1` 命中 0，没有 SQS 消费者；订单变化只能靠 `OrderSyncScheduler.java:80` 的 `@Scheduled(fixedDelay = 15 分钟)` 轮询。
- 增量拉取链路**部分存在**：实际调用的 6 个 endpointTag 为 `orders`/`fba-inventory`/`feeds`/`fees`/`finances`/`reports`（见各客户端 `*_ENDPOINT` 常量），其中 `feeds`/`fees`/`finances` 在 `SpiRateLimiter` 无策略（P0-28）。
- 批量对账链路**断裂**：finance `SettlementServiceImpl.java:88/193` 依赖的报表 `documentId` 恒为 null（P0-27）；`FeedsClient` 不下载 `resultFeedDocumentId`（P0-30），而 product 域 `ListingCopyService.java:236-242` 仍在 Feed `DONE` 时直接置 `SUCCESS`。
- 凭证入口**部分正常**：`SpapiController` 的 `/credential`（69–82 行）校验 body 内 `shopId` 归属后写入 `ShopCredentialStore`；`/sync/orders` 在无凭证时返回 `no credential for shopId=…`（94–96 行）而不是静默成功——这两个行为应保留并推广，但凭证表本身无自动建表路径（P0-23）。

### 4.3 统一错误分类

| 类别 | 示例 | 处理 |
|---|---|---|
| 输入/业务错误 | 参数非法、状态不允许 | 不重试，返回明确错误并记录审计 |
| 认证/授权错误 | 401、403、token 撤销 | 停止该店铺链路，告警并要求人工处理 |
| 限流/暂时错误 | 429、部分 5xx、网络超时 | 有上限的指数退避 + 抖动，保留请求证据 |
| 平台不可用 | 连续 5xx、服务维护 | 熔断，等待恢复后从游标续跑 |
| 数据错误 | 文件缺列、金额不平衡 | 隔离原始对象，不污染事实源 |
| 系统错误 | 数据库、MQ、代码异常 | 回滚事务，进入重试/DLQ，按 SLO 告警 |

### 4.4 Outbox/Inbox

**现状（本轮实测，2026-09-24）**：全仓库**不存在** `outbox_event` / `inbox_event` 表、relay 进程或消费去重表；在 Java/SQL/配置中检索 `outbox|inbox` 只有 2 处业务命中，且都是注释/测试名——`ProcurementServiceImpl.java:69` 的“Outbox-lite”（远程调用前预写 `SUBMITTING` 状态，防崩溃后重复下单）与 `ProcurementServiceImplTest.java:115`。也就是说，下面的表结构与规则是**目标态**：当前唯一的“准 Outbox”是一条采购单状态机约定，它不覆盖订单/库存/财务/结算事件，没有投递、重试、重放与积压监控，也不能作为集成事实源。

建议表结构（示意）：

```text
outbox_event(
  id, tenant_id, aggregate_type, aggregate_id, aggregate_version,
  event_type, event_id, payload, schema_version, status,
  retry_count, next_retry_at, created_at, published_at
)

inbox_event(
  consumer_group, tenant_id, event_id, event_type,
  payload_hash, status, processed_at, error_message,
  PRIMARY KEY (consumer_group, tenant_id, event_id)
)

integration_job(
  id, tenant_id, shop_id, connector, operation, cursor, status,
  attempt, next_retry_at, lease_owner, lease_until, last_error,
  created_at, updated_at
)

webhook_event(
  id, tenant_id, platform, shop_id, event_id, signature_status,
  received_at, processed_at, status, payload_hash,
  UNIQUE (tenant_id, platform, shop_id, event_id)
)
```

规则：

- Outbox relay 使用 `FOR UPDATE SKIP LOCKED` 或等价租约机制，避免多副本重复发送。
- Inbox 的唯一键必须是消费者组级；同一个事件被不同业务消费者各自处理是允许的。
- 消费者在业务事务成功后再标记 Inbox；失败保留错误并进入重试/DLQ。
- 重放工具必须支持按事件 ID、时间范围、店铺、消费者组筛选，并记录操作者与影响范围。
- 禁止用 Redis SETNX 作为唯一业务去重事实源；Redis 可以加速，但持久化唯一性必须落到数据库/Inbox。

### 4.5 Webhook 安全

**现状（本轮实测，作为设计输入）**：`MultiplatformController.receiveWebhook`（132–137 行）没有 `@ShopScoped`/`@RequireRole`，也把 `shopId` 直接传 `null`；`MultiplatformServiceImpl.receiveWebhook`（395–443 行）全流程没有任何签名/时间戳校验，缺口时按 `platform` 反查“最新创建的账号”来猜归属，去重只看 `event_id`，处理函数只写日志。也就是说：这个接口目前既能被匿名伪造写入，也可能把事件挂到别的租户店铺上。

- 每个平台独立验签实现；验签失败直接拒绝并告警。
- 校验时间戳，拒绝超过允许时钟窗口的重放；保存事件 ID 和 payload hash。
- 唯一键为 `(tenant_id, platform, shop_id, event_id)`，不是全局 `event_id`。
- 原始 payload 加密保存到隔离存储，解析使用严格 schema 和大小限制。
- Webhook 处理是异步的：先验签并持久化，再返回 2xx；业务处理失败不影响接收状态，通过重试恢复。
- Webhook 回调地址使用专用域名/路径，不暴露内部服务地址。

### 4.6 限流、背压与调度

- 每个外部 endpoint 有独立预算；不能用“总 QPS”掩盖某个店铺的限流。
- 调度器使用持久化租约和 `next_run_at`，多副本只有一个执行者；Redis 不可用时执行器必须停止或退化为单节点，不得自动多跑。
- 定期任务带抖动，避免整点同时触发；支持暂停、取消、重跑和查看游标。
- MQ 队列设置最大长度和死信队列；消费者根据积压和错误率背压，而不是无限拉取。
- 批量任务分批提交，保存 checkpoint；重跑从 checkpoint 继续，避免重复扣库存或重复凭证。

**现状（第 10 轮实测）与官方差距**

- `OrderSyncScheduler.java:80` 用 `@Scheduled(fixedDelay = 15 分钟)` 全量轮询，`InventorySyncScheduler` 同类；官方明确“**不要硬编码定时器**”，应基于本地预算与真实限流反馈动态调度，并优先用 Notifications 替代轮询。
- `SpiRateLimiter`（`amz-service-spapi/src/main/java/com/amz/ratelimit/SpiRateLimiter.java:70-73`）默认策略与官方 usage plan 对照：

| 操作（官方 operationId） | 官方 Rate（req/s） | 官方 Burst | 本仓库当前默认 | 结论 |
|---|---|---|---|---|
| `orders.getOrders` | 0.0167 | 20 | `orders` = 30/30s（= 1 req/s） | 宽松约 60 倍 |
| `reports.createReport` | 0.0167 | 15 | `reports` = 5/60s（≈ 0.0833 req/s） | 宽松约 5 倍 |
| `reports.getReport` | 2 | 15 | 与 `createReport` 共用 `reports` 一条策略（≈ 0.0833 req/s） | 比官方**严约 24 倍**，逐笔查询报表状态会被自己卡住（**第 16 轮纠正**：原表未单列此行） |
| `reports.getReports` / `cancelReport` / `getReportSchedules` / `createReportSchedule` / `cancelReportSchedule` / `getReportSchedule` | 0.0222 | 10 | 同上 | 宽松约 3.75 倍，且未按 operation 拆分 |
| `reports.getReportDocument` | 0.0167 | 15 | 同上 | 宽松约 5 倍（**第 16 轮纠错**：此前把官方值误记为 2/15——那是 `getReport` 的值；原“比官方更严、批量下载会被自己卡住”的结论方向相反，作废） |
| `feeds.createFeedDocument` | 0.5 | 15 | `feeds` 无策略 → 兜底 30/30s（= 1 req/s） | 宽松约 2 倍（**第 16 轮纠错**：此前把官方值误记为 0.0083/15，与下一行写反） |
| `feeds.createFeed` | 0.0083 | 15 | 同上 | 宽松约 120 倍（**第 16 轮纠错**：此前把官方值误记为 0.5/15，与上一行写反）。注意官方 `description` 另注：`JSON_LISTINGS_FEED` 的限流与该 operation **不同**（见本节末尾待验证风险） |
| `feeds.getFeed` / `cancelFeed` | 2 | 15 | 同上 | 速率比官方**严约 2 倍**，但 burst 从 15 放大到 30（**第 16 轮纠错**：此前把官方值误记为 0.0222/10） |
| `feeds.getFeedDocument` / `getFeeds` | 0.0222 | 10 | 同上 | 宽松约 45 倍 |
| `finances.listFinancialEvents` | 0.5 | 30 | `finances` 无策略 → 兜底 30/30s | 宽松约 2 倍 |
| `fees.getMyFeesEstimates`（本仓库**实际调用**路径：`POST /products/fees/v0/feesEstimate`，`FeesRealClient.java:32`） | 0.5 | 1 | `fees` 无策略 → 兜底 30/30s | 宽松约 2 倍，burst 从 1 放大到 30（约 30 倍）（**第 16 轮纠错**：此前按本仓库**未调用**的 `getMyFeesEstimateForASIN/SKU`（1/2）记录，operation 与 burst 倍数都不对） |
| `fbaInventory.getInventorySummaries` | 2 | 2 | `fba-inventory` = 25/30s（≈ 0.83 req/s） | 速率反比官方更严（约 0.4 倍），但 burst 从 2 放大到 25（约 12 倍），突发仍越权 |

- 结构性缺陷（同一文件）：`windows` 按 `shopId:endpoint` 分键，但 `policies` 只有 endpoint 维度 → **单店触发收紧会影响所有店铺**；`updateLimit()`（139–143 行）只收紧不恢复；`acquire()` 在持有 `Deque` 锁的同步块内 `Thread.sleep`（103 行），`@Scheduled` 单线程池下多店串行阻塞；`listings` 策略无任何调用方，是死配置。
- 官方 Rate/Burst 数值取自 `amzn/selling-partner-api-models` 各模型文件内 `description` 的 “Usage Plan” 表（2026-09-24 逐文件核验，6 个模型文件的字节数与 sha256 见 1.5.1 的“契约快照锁定”表）；本仓库兜底策略为 `DEFAULT_MAX_REQUESTS=30` / `DEFAULT_WINDOW=30s`（源码 37/42 行），即 1 req/s；“宽松倍数”= 兜底速率 ÷ 官方速率，比官方更严时按 官方速率 ÷ 兜底速率 标明。**第 16 轮纠错**：上一版本表有 5 处与官方模型原文不符——`getReportDocument` 误记为 2/15、`reports.getReport` 未单列、`createFeedDocument` 与 `createFeed` 的官方值互换、`getFeed`/`cancelFeed` 误记为 0.0222/10、`fees` 取了本仓库未调用的 operation 且 burst 倍数算错；以修订后的本表为准。
- 正向与缺陷的准确边界：`x-amzn-RateLimit-Limit` 确有读取与解析（全仓 15 处命中），且**已存在一条真实闭环**——`FeedsClient.sendWithRetry`（`amz-service/amz-service-spapi/src/main/java/com/amz/client/FeedsClient.java:308-315`）在 429 分支读取响应头并调用 `spiRateLimiter.updateLimit(FEEDS_ENDPOINT, header)`。因此准确表述是“覆盖面不足”而非“完全不做”：当前只覆盖 `feeds` 单一端点、只在 429 分支生效、只收紧不恢复、不持久化、不跨进程/跨副本共享。
- **`JSON_LISTINGS_FEED` 的专属限流未落实（待验证，暂不升 P0）**：`FeedsClient.java:53` 硬编码 `FEED_TYPE = "JSON_LISTINGS_FEED"`，而官方 `createFeed` 的 `description` 原文写明 “The rate limit for the `JSON_LISTINGS_FEED` feed type differs from the rate limit for the `createFeed` operation”，并指向 *Building Listings Management Workflows Guide*。因此 Task 5 的 `UsagePlan` 必须支持按 `feedType` 分档，或至少在策略表中显式注释该差异。**该 guide 的具体配额以官方页面为准——本轮抓取 developer-docs 失败，不写入未核实数值。**
- **Feeds 上传 `Content-Type` 未带 `charset`（待联调定级，暂不升 P0）**：`FeedsClient.java:54` 为裸类型 `application/json`。官方模型中 `CreateFeedDocumentSpecification.contentType` 只是自由 `string`（required，无 enum、无 pattern），模型本身不约束取值；实际是否要求 `; charset=UTF-8` 只能由真实联调确认。处理方式：Task 3 的契约测试先固定当前取值，联调后再决定是否修改，避免现在凭猜测改动。

### 4.7 对账体系

至少四类对账：

1. **订单对账**：Amazon 报表 vs 内部订单头/行/状态/金额。
2. **库存对账**：平台库存摘要 vs 内部台账/可售承诺。
3. **财务对账**：结算批次、费用、退款、广告、仓储 vs 凭证和利润快照。
4. **Listing/Feed 对账**：提交内容、Feed 结果、平台最终 Listing 状态。

`reconciliation_case` 建议状态：

```text
OPEN -> TRIAGED -> IN_PROGRESS -> WAITING_EXTERNAL -> RESOLVED -> CLOSED
                                      \-> ACCEPTED_DIFFERENCE
```

- 每个差异必须有类型、严重度、影响金额/数量、责任人、期限和证据链接。
- 自动修复只允许幂等、可逆、规则明确的操作；其余必须人工审批。
- 差异关闭后保留原始证据和审计记录，不能只删除告警。

### 4.8 连接器凭证与配置契约（API-Ready 落地口径，第 10 轮新增）

本节把 1.9 的 A1–A8 拆成四件事：**需要哪些字段、放在哪里、缺了会怎样、怎么验证**。下表“当前落点”全部是实测配置键或表列（含行号），不是目标设计。

**外部业务连接器**

| 连接器 | 凭证字段（当前落点，实测） | 端点/协议 | 当前缺失或降级行为 | API-Ready 必须补齐 |
|---|---|---|---|---|
| Amazon SP-API（Orders / Feeds / FBA Inventory / Reports / Finances / Fees） | `amz_shop_credential` 列 `client_id`、`client_secret_encrypted`、`refresh_token_encrypted`、`access_key_encrypted`、`secret_key_encrypted`、`region`、`marketplace_id`、`seller_id`（`amz-service-spapi/src/main/resources/db/schema.sql:11-24`）；全局兜底 `spapi.aws-access-key`、`spapi.aws-secret-key`（同模块 `application.yml:66-68`） | LWA `https://api.amazon.com/auth/o2/token`（`application.yml:69`）+ SigV4 | 凭证表不在任何自动建表路径（P0-23）；默认 profile `mock`（P0-24）；缺凭证时 `/sync/orders` 显式失败（正向） | 凭证表进唯一迁移入口并纳入 clean-install 流水线；生产 profile 非 mock 的启动自检；逐 operation 配额（4.6） |
| Amazon Messaging | `spapi.lwa.access-token`（`MessagingApiRealClient:41`）——**只有 access token，没有 clientId / clientSecret / refreshToken** | `spapi.messaging.endpoint`（同文件 :38） | token 过期后整域失效，且失败静默返回空列表 | 改为按店铺经 LWA 换 token（与 SP-API 共用凭证源与刷新逻辑），禁止全局单 token |
| Amazon Ads | `advertising.profile-id`、`advertising.api-endpoint`、`spapi.lwa.access-token`（`AdvertisingApiRealClient:49-55`） | `https://advertising-api.amazon.com` | 单套全局凭证、无 SigV4、失败静默返回空结果 | 按店铺 profile + LWA + 签名；失败必须显式 |
| Keepa | `keepa.api-key`（`KeepaRealClient:27`） | Keepa HTTP API | 无 key 时静默返回 null | 缺 key 显式错误码；按租户配额与缓存 |
| 17TRACK | `amz.logistics.tracking.api-key`（注释声明由 `AMZ_17TRACK_KEY` 注入）、`api-key-header`（默认 `17token`）、`base-url`、`query-path`、`enabled`（默认 `false`）（`LogisticsTrackingProperties:24-45`） | `https://api.17track.net` | `enabled=false` 或未配置凭证时**直接返回空轨迹**，调用方无法区分“未启用”与“查询失败” | 区分“未启用 / 调用失败”两种状态；失败进重试与告警 |
| 金蝶 | `kingdee.api-gateway`、`kingdee.app-id`、`kingdee.app-secret`（`KingdeeRealClient:30-36`） | 金蝶云 API 网关 | `@Profile("!mock")` 下仍无条件返回 `KINGDEE_MOCK_*`；凭证状态写 `SYNCING` 后无法重试（P0-22） | 真实签名、账套与期间校验；同步状态机可重试 |
| 1688 | `alibaba.app-key`、`alibaba.app-secret`、`alibaba.refresh-token`、`alibaba.gateway`（`Alibaba1688RealClient:50-59`） | `https://gw.open.1688.com/openapi` | 单套全局凭据（Redis key 无店铺维度）、固定第 1 页 | 凭证按店铺存储；签名与分页按官方校准 |
| SHEIN / TEMU / TikTok | `amz_platform_account` 列 `shop_id`、`platform`、`api_endpoint`、`api_key`、`api_secret_encrypted`、`access_token_encrypted`、`refresh_token_encrypted`、`token_expires_at`（`PlatformAccount:16-25`）；默认端点见 `PlatformCredentialService:40-44` | 各平台开放平台 | 已按 `(shopId, platform)` 解析（**正向**，是本文唯一做对“多店多凭证”的连接器域）；但客户端自述未校准，发货回传以 `cred(null)` 解析凭证 | 官方沙箱校准 + 分页与签名修正；由 `token_expires_at` 驱动刷新 |

**内部依赖（不是外部平台，但同样适用 A2 / A3）**

| 依赖 | 配置键（实测） | 缺失时当前行为 | API-Ready 要求 |
|---|---|---|---|
| DeepSeek（AI） | `deepseek.api-key`、`deepseek.base-url`、`deepseek.model-name`、`deepseek.timeout`（`LangChain4jAgentConfig:26-38`） | 功能降级 | 缺 key 时在能力清单标记“未启用”，不得静默返回伪造结论 |
| 嵌入模型 | `embedding.enabled`（默认 false）、`embedding.api_url`、`embedding.api_key`、`embedding.model`（`EmbeddingServiceImpl:26-35`） | 关闭 | 与 ES 索引维度（`knowledge.es.vector-dims:1536`）做一致性校验 |
| 汇率 | `amz.exchange-rates-api-url`、`amz.exchange.strict-unknown`（默认 false）（`GlobalExchangeRateService:40/51`） | 未知币种宽松回退 | 生产环境应置 `strict-unknown=true`，否则利润口径会静默失真 |
| 对象存储 | `oss.endpoint`、`oss.accessKeyId`、`oss.accessKeySecret`、`oss.bucketName`、`oss.access-url`（`OssConfig:14-26`） | 上传失败 | 私有 bucket + 预签名 URL，禁止公开读 |
| 搜索/知识库 | `knowledge.es.base-url`（默认 `http://localhost:9200`）、`knowledge.es.index`（`KnowledgeEsClient:36/39`） | 检索降级 | ES 必须启用认证；索引模板与向量维度统一管理 |

**配置来源与命名（目标设计）**

1. 优先级：密钥管理系统（Vault / KMS / Secrets Manager）> 环境变量 > 数据库密文（按 `tenant_id + shop_id + connector`）> 缺失即该连接器“未启用”；**禁止**用空列表 / null / 占位单号冒充成功（A2）。
2. 命名统一为 `AMZ_<CONNECTOR>_<FIELD>`（如 `AMZ_SPAPI_CLIENT_ID`、`AMZ_17TRACK_KEY`、`AMZ_KINGDEE_APP_SECRET`），并与 k8s `secret.yaml` / `configmap.yaml`、`.env.example` 三处同步——当前 `.env.example` 为 **71 行 / 36 个键**，既无 Nacos 变量（`NACOS_ADDR`/`NACOS_SERVER_ADDR`），也无任何平台凭证变量（SP-API/Ads/Keepa/17TRACK/金蝶/1688/OSS/ES 全部缺失，实测覆盖率见本节末尾“配置覆盖率实测”与 P0-32）。
3. 每个连接器要有独立开关与“未启用”语义；该状态必须与“调用失败”用不同返回值与不同前端标签表达。

**自检与能力清单（API-Ready 验收端点，目标设计）**

- `GET /api/connectors`：逐连接器返回 `{启用状态, 凭证来源(env|db|vault|none), 最近一次调用时间与结果, 支持的 operation 白名单, 是否通过 A1–A8}`；前端“已对接 / 未接通”标签只读此接口，不读人工声明。
- `POST /api/connectors/{code}/self-test`（`@RequireRole("ADMIN")`，带审计）：用真实凭证发起一次最小只读调用（SP-API 取 marketplace participations、Keepa 取一个 ASIN、17TRACK 查一个测试单号、金蝶取一次科目表），返回脱敏请求/响应摘要与错误码，**禁止**回显任何凭证明文。
- 每个连接器的验收证据固定三条：**缺凭证 → 明确错误码**、**错误凭证 → 平台错误码透传（401/403/配额）**、**正确凭证 → 成功样例（脱敏）**；三条齐备才允许标记为 API-Ready（对应 A5，也是 7.9 交付物中的连接器自检用例）。

#### 4.8.1 配置覆盖率实测："有凭证"不等于"凭证能到进程"（第 13 轮）

**本节回答的前提**：用户的诉求是“暂时没有对接 API，但需要有对接能力，有 API 就可以直接使用”。第 13 轮实测给出一个必须先纠正的判断——**当前状态下，即使拿到正确凭证，也不一定能传到进程里**。三类断点同时存在：

1. 代码读的键名与清单注入的键名不一致（P0-25：代码 53 处读 `NACOS_ADDR`，16 份 Deployment 全部只注入 `NACOS_SERVER_ADDR`）；
2. 清单压根没有注入该变量（P0-32：logistics 缺 15 项、search 缺 10 项、product 缺 7 项等）；
3. 部署形态选中的是 mock 实现而非真实客户端（P0-01：16/16 份 k8s 与 Compose 均不设 `SPRING_PROFILES_ACTIVE`）。

因此“API-Ready”必须把**配置可达性**列为独立验收项：只核对“凭证字段是否存在”不足以证明“有 API 就能用”。本小节的数字是后续 `DeploymentManifestContractTest`（计划 Task 8）的断言基线。

**方法**：`PyYAML 6.0.3` 解析 `docker-compose.yml` 与 16 份 `k8s/services/*.yaml`；对 `amz-service/*/src/main/resources/*.yml` 用 `\$\{([A-Z][A-Z0-9_]*)(?::([^}]*))?\}` 提取占位符名后做差集。已剔除假阳性：`MQ_USERNAME`/`MQ_PASSWORD` 只出现在 `application-local.yml`（order / product / message / user 四模块），不是生产缺项。

**（1）k8s Deployment 逐模块覆盖率**（16 份文件结构一致：Service + Deployment + HorizontalPodAutoscaler；全仓 **无 `envFrom`、无 `env_file`**）

| 模块（k8s/services/） | spring 占位符 | Deployment env | 实测缺失项 |
|---|---|---|---|
| `amz-gateway.yaml` | 6 | 10 | `NACOS_ADDR`、`SENTINEL_DASHBOARD` |
| `amz-service-user.yaml` | 21 | 17 | `AMZ_CRYPTO_KEY`、`NACOS_ADDR`、`OSS_ACCESS_KEY_ID`、`OSS_ACCESS_KEY_SECRET`、`OSS_BUCKET_NAME` |
| `amz-service-product.yaml` | 23 | 19 | `AGENT_AI_CHAT_URL`、`DEEPSEEK_API_KEY`、`MONGO_HOST`、`MONGO_PASSWORD`、`MONGO_USERNAME`、`NACOS_ADDR`、`SPRING_PROFILES_ACTIVE` |
| `amz-service-order.yaml` | 18 | 18 | `NACOS_ADDR` |
| `amz-service-search.yaml` | 16 | 11 | `DB_PASSWORD`、`DB_USERNAME`、`EMBEDDING_API_KEY`、`EMBEDDING_API_URL`、`EMBEDDING_ENABLED`、`EMBEDDING_MODEL`、`ES_URIS`、`MYSQL_HOST`、`MYSQL_PORT`、`NACOS_ADDR` |
| `amz-service-message.yaml` | 10 | 21 | `NACOS_ADDR` |
| `amz-service-ai.yaml` | 15 | 21 | `DEEPSEEK_API_KEY`、`KNOWLEDGE_ES_BASE_URL`、`KNOWLEDGE_ES_INDEX`、`KNOWLEDGE_ES_VECTOR_DIMS`、`NACOS_ADDR` |
| `amz-service-spapi.yaml` | 21 | 24 | `SPRING_PROFILES_ACTIVE`（该文件是 16 份中唯一同时注入 `NACOS_ADDR` 与 `NACOS_SERVER_ADDR` 的） |
| `amz-service-ad.yaml` | 13 | 17 | `AD_PROFILE_ID`、`NACOS_ADDR`、`SPRING_PROFILES_ACTIVE` |
| `amz-service-procurement.yaml` | 15 | 17 | `ALIBABA_APP_KEY`、`ALIBABA_APP_SECRET`、`ALIBABA_REFRESH_TOKEN`、`NACOS_ADDR`、`SPRING_PROFILES_ACTIVE` |
| `amz-service-customer.yaml` | 11 | 17 | `NACOS_ADDR` |
| `amz-service-logistics.yaml` | 25 | 17 | `AMZ_17TRACK_BASE_URL`、`AMZ_17TRACK_KEY`、`AMZ_LOGISTICS_*`（11 个）、`AMZ_TRACKING_ENABLED`、`NACOS_ADDR`、`SPRING_PROFILES_ACTIVE` |
| `amz-service-ops.yaml` | 11 | 17 | `NACOS_ADDR` |
| `amz-service-finance.yaml` | 18 | 17 | `KINGDEE_APP_ID`、`KINGDEE_APP_SECRET`、`NACOS_ADDR`、`SPRING_PROFILES_ACTIVE` |
| `amz-service-report.yaml` | 5 | 19 | `NACOS_ADDR`、`SPRING_PROFILES_ACTIVE`（反向过度注入：该模块 `application.yml` 仅 52 行、无 datasource，却收到 19 个 DB/Rabbit/Redis 变量） |
| `amz-service-multiplatform.yaml` | 12 | 17 | `NACOS_ADDR`、`SPRING_PROFILES_ACTIVE` |

**（2）`docker-compose.yml` 注入缺口**（实测 **31 个 service**、顶层键 `['services','volumes']`、`env_file` **0** 次）

| 变量 | 实测注入情况 | 后果 |
|---|---|---|
| `MYSQL_HOST` | **仅 spapi**（= `mysql`；另有 `MYSQL_SLAVE_HOST`） | 其余模块的 `MYSQL_HOST` 占位符在容器内无值 |
| `REDIS_HOST` | **全仓 0 次** | 无任何服务能通过环境变量拿到 Redis 地址（叠加 P0-31 后 order/product 直接连公网 IP） |
| `RABBITMQ_HOST` | **仅 order、finance** = `rabbitmq` | 其余模块的 MQ 地址占位符无值 |
| `NACOS_SERVER_ADDR` | 业务服务普遍有（= `nacos:8848`） | 键名与代码读取的 `NACOS_ADDR` 不一致（P0-25） |
| `SPRING_PROFILES_ACTIVE` | **0 次** | 7 个默认 `mock` 的模块在容器形态下全部走 mock（P0-01） |

**（3）`.env.example` 实测：71 行 / 36 个键**

36 键全清单（分类）：`DB_USERNAME`、`DB_PASSWORD`、`MYSQL_HOST`、`MYSQL_PORT`、`REDIS_PASSWORD`、`REDIS_HOST`、`REDIS_PORT`、`RABBITMQ_USERNAME`、`RABBITMQ_PASSWORD`、`RABBITMQ_HOST`、`RABBITMQ_PORT`、`MQ_USERNAME`、`MQ_PASSWORD`、`MONGO_USERNAME`、`MONGO_PASSWORD`、`JWT_SECRET_KEY`、`JWT_ISSUER`、`JWT_AUDIENCE`、`DEEPSEEK_API_KEY`、`AGENT_LLM_EVAL_ENABLED`、`IM_WEBHOOK_URL`、`IM_WEBHOOK_KIND`、`KNOWLEDGE_ES_BASE_URL`、`KNOWLEDGE_ES_INDEX`、`KNOWLEDGE_ES_VECTOR_DIMS`、`AWS_ACCESS_KEY`、`AWS_SECRET_KEY`、`AWS_REGION`、`AMZ_CRYPTO_KEY`、`KEEPA_API_KEY`、`GRAFANA_USER`、`GRAFANA_PASSWORD`、`SEATA_ENABLED`、`SSL_ENABLED`、`GATEWAY_DOCS_ENABLED`、`FINANCE_VOUCHER_ASYNC`。

仍然缺失（与 §4.8 正文的“三处同步”冲突）：`NACOS_ADDR`、`NACOS_SERVER_ADDR`、`MONGO_HOST`、`ES_URIS`、`OSS_ACCESS_KEY_ID`、`OSS_ACCESS_KEY_SECRET`、`OSS_BUCKET_NAME`、`KINGDEE_APP_ID`、`KINGDEE_APP_SECRET`、`ALIBABA_APP_KEY`、`ALIBABA_APP_SECRET`、`ALIBABA_REFRESH_TOKEN`、`AD_PROFILE_ID`、`AMZ_17TRACK_BASE_URL`、`AMZ_17TRACK_KEY`、`AMZ_LOGISTICS_*`、`AMZ_TRACKING_ENABLED`、`EMBEDDING_API_KEY`、`EMBEDDING_API_URL`、`EMBEDDING_ENABLED`、`EMBEDDING_MODEL`、`AGENT_AI_CHAT_URL`、`SENTINEL_DASHBOARD`。

**（4）验收口径（写入 Task 8）**：CI 断言必须做成**双向**——每个 Deployment 既不能缺“代码会读的变量”，也不能出现“代码从不读的注入项”（后者既是配置漂移，也是误配排查成本）。凭证注入路径必须与 `.env.example`、k8s Secret/ConfigMap、代码占位符三处同名同义；任何一处不同名都必须在发布前被测试拦住。

---

## 5. 身份、租户、数据安全与 Amazon DPP 合规设计

### 5.1 本节结论

当前安全模型的核心问题不是“缺一个登录页”，而是**信任边界没有闭合**：

- 内部接口被当作可信入口绕过网关；
- 店铺和字段权限在缺少上下文时默认放行；
- SSE 与 WebSocket 的异步链路会丢失或伪造用户身份；
- PII 基本以明文业务字段存在，没有分类、最小化、生命周期和删除闭环；
- 密钥没有 KMS 托管、轮换、版本和 crypto-shredding；
- K8s Secret 使用已提交占位值，基础设施端口和监控组件默认开放；
- 审计日志不能作为不可否认证据，且没有敏感字段脱敏的强制约束。

生产设计必须把上述问题改为“缺失即拒绝、异常即拒绝、默认最小权限”。

### 5.2 身份与会话

#### 5.2.1 人机身份

- 首选 OIDC Authorization Code + PKCE；管理端使用短期 access token 与可轮换 refresh token。
- access token 有效期建议 10–15 分钟；refresh token 使用一次性轮换、重用检测和令牌族撤销。
- **现状（本轮实测，作为设计输入）**：`/user/refresh` 经网关必然 401——`BaseAuthInterceptor` 的白名单没有该路径，且 `parseToken` 会对 `refresh:{id}` 形式的 subject 执行 `Integer.valueOf`，抛 `NumberFormatException`。`JwtUtil.verifyRefreshToken`（172–188 行）实现本身正确，但因为没有轮换/吊销/设备绑定存储，即使修好可达性，被盗 refresh token 仍可在 TTL（7 天）内重复使用。
- 高权限角色强制 MFA；登录、重置密码、绑定设备、权限变更产生安全审计。
- 支持会话列表、设备撤销、管理员强制下线、离职/停用后 24 小时内撤权。
- 禁止以手机号验证码作为唯一管理员长期认证方式；验证码仅作为辅助因素。
- 短信通道必须真实可用；未配置通道时接口返回明确错误，不能伪称“已发送”。

#### 5.2.2 前端会话

当前前端将 `token`、`refreshToken`、`token_expiry` 放在 `localStorage`，并使用 refresh TTL 标记 access token 过期时间。生产建议改为：

- BFF 模式：浏览器只持有 `HttpOnly + Secure + SameSite=Lax/Strict` 会话 Cookie；令牌不进入 JavaScript、localStorage、URL 或日志。
- 所有变更请求要求 CSRF token 或同源校验；CORS 只允许明确白名单来源。
- 路由守卫通过 `/session` 获取真实会话状态，不依赖本地过期时间戳。
- access token 过期由 BFF 刷新；刷新失败统一清除会话，避免前端“看似登录、实际一直 401”。
- 登出必须调用服务端撤销接口，并清理 WebSocket/SSE 长连接。

#### 5.2.3 服务身份

- 服务间使用 mTLS 作为传输身份，另附短期 service JWT 表达权限。
- 服务 JWT 的 `aud` 限定被调用服务，`exp` 建议不超过 5 分钟，带 `service_id`、`tenant_scope` 和 `scopes`。
- 服务账号按服务/环境隔离；禁止所有服务共享同一个 JWT secret。
- 禁止把用户 token 原样转交给下游作为服务身份；下游需要同时知道“谁发起”和“哪个服务调用”。
- 内部 Feign 调用、MQ 消费、定时任务都必须通过统一客户端注入服务身份和租户上下文。

#### 5.2.4 SSE/WebSocket

- SSE/WebSocket 仅从 BFF/网关认证结果获取身份，不能从查询参数 `userId` 取身份。
- 长连接建立时绑定 `tenant_id/user_id/shops/device_id/session_id`，并设置最大生命周期与空闲超时。
- 异步线程池必须传播完整上下文；禁止只传播 `traceId` 而让工具回退到默认用户。
- AI 工具执行前必须再次校验租户、店铺和字段权限，不能只在连接建立时校验一次。
- 断线重连使用一次性 ticket 或 Cookie，不使用可长期泄露的 URL token。
- 连接关闭、权限变更、用户停用、密码修改时，服务端主动清理相关会话。
- 消息推送必须按 `tenant_id + user_id` 路由，禁止向广播频道泄露其他租户数据。
- **现状（本轮实测，作为设计输入）**：`AgentMemoryController` 全部 6 个端点（37/49/57/66/79/89 行）从 query/path/body 取 `userId` 且不校验归属，`history` 用 `"sess-"+userId` 拼接会话；这是一个独立的 IDOR 面，和 SSE 上下文丢失是两回事，需要分别修。`KnowledgeController` 的上传端点虽然做了 `isShopAllowed` 与 10MB 限制，但 Tika 2.9.2 的 XXE（见 5.7）可从上传文件触发。
- `/internal/message/notify` 必须改成受服务身份保护的内部命令，并移除网关公开路由。

### 5.3 授权与信任边界

- 网关的公开白名单应只包含登录、回调、健康检查、静态资源等最小集合；`/internal`、`/actuator`、Swagger 不得对公网开放。
- Actuator 只暴露 liveness/readiness；metrics、heap、env、configprops 只允许运维网段或经认证的监控系统访问。
- 业务服务不直接暴露公网端口；Ingress 只路由到 `erp-edge`。
- 安全组/NetworkPolicy 默认拒绝：只有明确的服务对可以建立连接，数据库、Redis、MQ、ES、Nacos、监控均不允许从公网或普通应用 pod 任意访问。
- 采用“边缘认证 + 服务内二次授权”：网关通过不代表业务对象访问一定合法；每个聚合根都要校验租户和店铺。
- 角色权限使用 RBAC + 资源范围（tenant/shop/warehouse）、字段权限和数据范围；管理员也应有审计的 break-glass 流程。
- 高风险操作支持审批、双人复核和时限授权；紧急访问自动过期并触发事后审计。
- **现状（本轮实测，作为设计输入）**：当前守卫链的覆盖面容易被高估。`amz-gateway` 的 `MyGlobalFilter` 只校验 `shopId` **请求头**（115–133 行），body/query/path 里的 shopId 不受保护；`ShopIdGuardAspect` 只覆盖 Long 型、且名为 `shopId` 的 `@RequestParam/@PathVariable`（48–52 行 shops 为空时放行）；`@RequireRole`/`@ShopScoped` 均为 `@Target(METHOD)`，**没有任何类级注解**——“方法上没有注解”不等于“公网无认证”，反过来“类上有注解”也不成立。本轮 328 个端点里 82 个没有任何守卫注解，其中一部分是靠白名单或内部调用约定成立的，必须逐条判定而不是按数量收敛。

### 5.4 PII 与数据分类

#### 5.4.1 分类

| 级别 | 定义 | 示例 | 默认控制 |
|---|---|---|---|
| L0 公开 | 可公开的数据 | 公开 Listing 标题 | 可缓存，仍防篡改 |
| L1 内部 | 非敏感经营数据 | SKU 成本汇总、非 PII 报表 | 登录后可读，按角色限权 |
| L2 机密 | 高价值商业数据 | 结算、供应商价格、广告策略、密钥元数据 | 最小权限、审计、加密 |
| L3 受限 PII | 可识别个人的数据 | 买家姓名/邮箱/电话/地址、聊天、税号、银行信息 | 默认掩码、加密、短保留期、查看审计 |

#### 5.4.2 PII 清单与处理原则

项目中已核对的 PII 风险字段包括：

- 订单：`buyer_name`，以及同步/地址对象中的买家信息。
- 客服：`buyerEmail`、`buyerName`、工单消息、RMA 联系信息。
- 多平台：`PlatformMessage.buyerName/buyerEmail`、统一订单买家昵称。
- 用户：手机号、邮箱、地址、生日等。
- 集成：店铺凭证、AWS key、SP-API refresh token、广告 token。
- 日志：异常堆栈、请求体、报表文件和 URL 查询参数可能携带 PII。

处理原则：

1. **最小化**：只有完成履约、客服或法定义务所需字段才进入持久化；能使用平台订单 ID/匿名 ID 就不复制买家 PII。
2. **分离**：PII 进入受限表/服务（PII vault），普通业务表只保留 token/hash 引用。
3. **加密**：传输 TLS 1.2+；静态存储使用 KMS envelope encryption；敏感列使用 AES-256-GCM + key id；备份和对象存储同样加密。
4. **掩码**：API 默认返回掩码值；查看明文需要权限、理由和短期访问凭证。
5. **日志脱敏**：日志、追踪、MQ 消息和错误响应不得出现完整邮箱、电话、地址、token、密钥和支付信息。
6. **禁止派生泄露**：搜索索引、分析宽表、AI 向量库、测试夹具不得包含未脱敏 PII。
7. **可删除**：支持按用户/订单/店铺的数据主体请求，能够定位所有副本并执行删除或 crypto-shredding。
8. **可证明**：每次敏感字段读取、导出、解密、删除都有审计记录。

#### 5.4.3 保留与删除

按 Amazon DPP 与业务/法务要求建立策略表，工程默认值如下，最终以法务确认和官方最新条款为准：

| 数据类别 | 默认目标 | 删除方式 |
|---|---|---|
| 订单履约所需 PII | 交付后不超过 30 天 | 定时任务删除原件；保留必要匿名订单统计 |
| 非 PII 经营数据 | 最长 18 个月 | 月分区归档，过期分区下线 |
| 安全/审计日志 | 至少 12 个月 | 不可变存储，按合规周期归档 |
| 原始 API 响应/报表 | 以业务必需和 DPP 上限为准 | 对象加密 + lifecycle 删除 |
| 备份 | 按备份保留策略到期 | 过期备份不可恢复；必要时加密销毁密钥 |
| 测试/模拟数据 | 不保存真实 PII | 只使用 `SYNTHETIC` 数据 |

删除流程必须包括：识别 -> 影响评估/保留例外 -> 主库删除 -> 缓存/搜索/对象/分析/向量清理 -> 备份到期 -> 删除证明。仅删除主表行不算完成。

### 5.5 密钥、凭证与轮换

当前 `CryptoUtil` 已有 AES-256-GCM 的正确方向，但仍是单配置密钥，且部分外部平台凭证存在解密失败回退原文的路径。生产设计改为：

- 使用云 KMS/Vault/HSM 管理根密钥；应用只获取短期数据密钥，不持有长期根密钥。
- 数据密钥按环境、租户或高风险店铺隔离；密文保存 `key_id/version/algorithm/created_at`。
- 支持密钥轮换、重包裹和旧版本解密窗口；轮换有审计和回滚方案。
- 外部 token 由 Secret Manager 或加密凭证表保存；禁止日志输出、浏览器暴露和明文回退。
- SP-API refresh token、LWA client secret、AWS key、广告 token、Webhook secret 各自按店铺隔离。
- 创建、更新、撤销、读取凭证都记录操作者、用途和来源；普通业务代码只能获取“已解密的最小能力客户端”，不能得到完整凭证结构。
- K8s 使用 External Secrets/Sealed Secrets/CSI Secret Store；仓库只保留模板，不提交真实值或可复用的默认密码。
- CI 开启 secret scanning；发现密钥立即轮换，不能只删除 Git 行。

### 5.6 审计与不可否认

- 审计事件至少记录：时间、租户、店铺、actor、role、service、action、resource、before/after（脱敏）、请求 ID、trace ID、结果、失败原因、来源 IP/设备。
- 审计存储与业务库分离，写入只追加；可采用只写对象存储、WORM 或带哈希链的表增强不可否认性。
- 定期校验哈希链/签名，异常时告警；不能让应用用普通 UPDATE 删除审计行。
- PII 查看、导出、退款、价格/库存批量修改、广告预算变更、凭证读取、权限变更属于强制审计动作。
- 异步审计失败不能静默丢弃；进入本地持久化队列或 outbox，并在恢复后补写。
- 审计日志保留至少 12 个月；访问审计日志本身也必须有权限和审计。

### 5.7 应用与供应链安全

- 按 OWASP ASVS L2/L3 建立基线，覆盖认证、会话、访问控制、输入校验、加密、日志和错误处理。
- 输入校验使用白名单 schema；禁止把任意 URL、文件名、表达式或 SQL 片段直接执行。
- 防止 SSRF：Webhook 目标、图片/报表 URL、模型工具网络访问必须经过域名和 IP 允许列表。
- 文件上传需要类型/大小/病毒扫描；报表解析防 XXE、压缩炸弹、CSV 注入和路径穿越。
- AI Agent 需要工具白名单、参数 schema、写操作审批、提示注入防护、输出脱敏和调用预算；工具不能绕过业务权限。
- API 具有分页上限、请求体上限、超时、限流和幂等键；防止批量查询和导出打垮数据库。
**现状（本轮实测，作为设计输入）**：运行时依赖树 427 个坐标（第三方 410），OSV 配对查询命中 210 组 `(坐标, advisory)`、67 个坐标、190 条唯一 advisory（18 critical / 80 high / 90 moderate / 22 low），其中 4 个坐标在 OSV 中没有修复版本。重点项：

- `com.alibaba:fastjson:1.2.83`——CRITICAL RCE，1.x 线无修复版本；它不在任何 `pom.xml` 中显式声明、也没有任何 Java 代码 import，而是随 `io.seata:seata-all:2.0.0` 进入 **17 个模块**的运行时类路径（另有 `1.2.83_noneautotype` 随 sentinel 传送模块进入）。整改方向是升级/移除上游、显式排除并回归 Seata 序列化，或迁移到 Jackson；不能靠“代码没直接用”当作已缓解。
- `org.apache.tika:tika-core / tika-parsers-standard-package:2.9.2`——CRITICAL XXE（修复版本 3.2.2+），且可由 `amz-service-ai` 的知识库文档上传路径（`KnowledgeController` → `DocumentParser` → `Tika.parseToString`）触达，属真实可达而非纯理论。
- `io.netty:*:4.1.115.Final`——多条 HIGH/CRITICAL（SNI 路由绕过、HTTP/2 MadeYouReset、HTTP 请求走私、解压炸弹），修复版本在 4.1.13x 一线上；**最外层网关正好使用 `netty-all 4.1.115.Final`**，是互联网暴露面。
- `org.apache.tomcat.embed:tomcat-embed-core:10.1.31`——32 条 advisory（含 CAPTURE 重放、DIGEST 认证绕过、部分 PUT RCE），需随 Spring Boot 升级到修复版本。
- 其他需处理项：`org.apache.opennlp:opennlp-tools:1.9.4`（经 `dev.langchain4j:langchain4j:0.36.2` 引入，CRITICAL XXE / 任意类实例化）、`org.bouncycastle:*:1.77/1.78`（CRITICAL，1.80.2+/1.85 修复）、`com.mysql:mysql-connector-j:8.0.33`（接管漏洞，8.2.0+）、`com.rabbitmq:amqp-client:5.21.0`（多条 HIGH DoS，5.33+）、`commons-io:2.11.0`（2.14.0+）、`commons-fileupload:1.5`（1.6.0+）、`micrometer-core:1.13.6`、`protobuf-java:3.21.9`、`jackson-databind 2.17.2`（多态校验绕过，2.18.8+）。
- 前端：`npm audit --prefix amz-frontend` 报 7 high / 3 moderate（`axios`、`rollup`、`nanoid`、`postcss` 等），需清零或书面豁免。
- 方法与限制：本机 Maven 镜像索引可能滞后，上述修复版本来自 OSV advisory 的 `fixed` 事件；实施阶段必须在能访问完整 Central 的环境重跑 `dependency:tree` 与 SCA 复核，不能照抄版本号。

- CI 加入 SAST、依赖漏洞扫描、容器镜像扫描、IaC 扫描、secret scanning 和 SBOM；高危漏洞按 Amazon 安全要求时限处理。
- 生产镜像使用固定 digest、最小基础镜像、非 root、只读根文件系统和签名验证；禁止 `latest`。

### 5.8 Kubernetes 与部署安全基线

- Ingress 强制 TLS/HSTS，关闭 `ssl-redirect=false` 的默认配置；证书自动轮换。
- 管理入口通过 VPN/零信任；ClusterIP 不暴露基础设施端口。
- 默认 NetworkPolicy：deny all，再按服务对开放端口。
- Pod Security 使用 restricted；`runAsNonRoot`、只读 rootfs、drop all capabilities、seccomp/AppArmor。
- 使用 PDB、拓扑分散、资源 requests/limits、liveness/readiness/startup probes，避免单副本和级联故障。
- Secret 由 External Secrets/Vault/KMS 注入；ServiceAccount 使用 IRSA/Workload Identity，避免静态 AWS key。
- 数据库、对象存储、备份和消息队列启用加密和访问审计；禁止使用默认管理员密码。
- 生产环境关闭 Swagger UI 和详细 Actuator；错误响应不含堆栈和内部主机信息。

### 5.9 安全事件响应

- 建立安全事件分级、值班联系人、升级路径和证据保存流程。
- 发现安全事件后 24 小时内按 Amazon 要求通知 `security@amazon.com`，同时启动内部响应。
- critical 漏洞 7 天内修复，high 30 天内修复；无法按期完成必须有风险接受和补偿控制。
- 终止员工/外包人员访问后 24 小时内撤销全部账号、token、VPN 和密钥。
- 每年至少进行一次桌面演练、一次凭据泄露演练和一次备份恢复演练。
- 保留取证所需日志，但不得无限期保留 PII；通过访问控制和最小化满足两者平衡。

### 5.10 Amazon 安全控制映射

| Amazon 控制/要求 | 本项目控制 | 验收证据 |
|---|---|---|
| PII 仅用于授权目的 | PII vault、最小化、字段权限 | 数据地图、访问测试、审计样例 |
| 静态/传输加密 | TLS、KMS、列级 AES-GCM、加密备份 | 配置证据、密钥轮换记录、渗透测试 |
| 30 天 PII 删除 | 生命周期任务、crypto-shredding、删除证明 | 自动化测试、删除报表、法务确认 |
| 18 个月非 PII 上限 | 分区归档和过期 | 分区策略、归档报告 |
| 12 个月安全日志 | 不可变审计存储 | 保留策略、完整性校验 |
| 24 小时事件通知 | IR 流程和联系人 | 演练记录、通知模板 |
| 7 天 critical/30 天 high | 漏洞 SLA 和 CI 门禁 | 漏洞台账、修复证据 |
| 24 小时撤权 | 身份生命周期和会话撤销 | 离职流程测试、访问审查 |
| 防止未授权访问 | mTLS、服务 JWT、RBAC、NetworkPolicy | 配置基线、越权测试报告 |

### 5.11 安全验收标准

1. 无认证的内网/公网请求无法调用任何 `/internal` 接口。
2. 缺失 tenant/shop/role/权限上下文时，敏感查询和写操作全部拒绝。
3. 通过篡改 JWT、请求头、body、URL 参数、WebSocket/SSE 参数进行的跨租户/跨店铺测试全部失败并告警。
4. 浏览器端不持久化可被 JS 读取的长期令牌。
5. PII 在数据库、备份、日志、ES、MongoDB、MQ 和分析表中均符合分类与脱敏策略。
6. 任意密钥泄露漏洞都能定位影响的店铺、轮换并证明旧密钥失效。
7. 审计日志完整性校验通过，敏感数据访问可追溯到人和服务。
8. 生产镜像无高危未修复漏洞，基础设施端口没有公网暴露。
9. 安全事件演练在 24 小时内完成通知和初步遏制。
10. 所有生产部署都能通过自动化的 fail-closed、迁移、备份恢复和权限测试。

---

## 6. 部署、性能、可观测性与 CI/CD

### 6.1 生产部署拓扑

推荐拓扑：

```text
Internet
  -> DNS/WAF/CDN
  -> Ingress (TLS/HSTS, rate limit, body limit)
  -> erp-edge (gateway/BFF/WebSocket edge)
  -> service mesh / mTLS
       -> erp-core
       -> erp-integration
       -> erp-intelligence
       -> erp-worker

Private data plane:
  -> MySQL HA (primary + standby + read replicas + PITR)
  -> Redis Sentinel/Cluster
  -> RabbitMQ 3-node quorum queues
  -> Elasticsearch 3-node with security/TLS
  -> MongoDB Replica Set
  -> S3/MinIO encrypted object storage
  -> Keycloak / Vault / KMS

Observability:
  -> OpenTelemetry Collector
  -> Prometheus + Alertmanager
  -> Loki/ELK logs
  -> Grafana dashboards
```

部署原则：

- 基础设施端口不映射到宿主机；开发 Compose 与生产 K8s 使用不同的网络策略。
- 每个有状态的运行时使用多副本或高可用托管服务；单实例只允许出现在本地开发。
- 数据库迁移、应用部署、配置变更互相解耦；迁移采用 expand/contract，避免新旧版本同时运行时列不存在。
- 所有环境（dev/staging/prod）使用同一套 IaC 和迁移机制，禁止生产手工改表。
- 生产部署采用渐进式发布；高风险版本先 shadow/canary，再全量。

### 6.2 容量模型（模拟假设）

下列为**容量设计假设**，不是对用户的经营数据判断；所有生成数据标记 `SYNTHETIC`。

| 参数 | 首期基线（SYNTHETIC） | 压力档（SYNTHETIC） |
|---|---:|---:|
| 租户 | 1 | 10 |
| 店铺 | 10 | 100 |
| marketplace | 5 | 20 |
| 订单/月 | 100,000 | 2,000,000 |
| 订单行/月 | 300,000 | 8,000,000 |
| 订单/事件峰值 | 50–100 req/s | 500–1,000 req/s |
| 日活运营用户 | 50 | 1,000 |
| 广告实体日指标 | 100,000 行/天 | 5,000,000 行/天 |
| 库存 SKU 数 | 50,000 | 1,000,000 |
| 单店铺商品数 | 50,000 | 1,000,000 |
| 保留 | PII 30 天；经营数据 18 个月 | 同左，按分区归档 |

粗算存储（实际必须以真实字段和压缩率压测校准）：

- 订单头：100k/月 × 2 KB ≈ 200 MB/月。
- 订单行：300k/月 × 0.5 KB ≈ 150 MB/月。
- 事件/审计：1M/月 × 0.5 KB ≈ 500 MB/月。
- 广告日指标：3M/月 × 0.2 KB ≈ 600 MB/月。
- 一年热数据通常仍在几十到几百 GB 量级，真正的瓶颈更可能是索引、JOIN、全量查询和外部 API 限流，而不是单纯磁盘容量。

容量决策必须通过压测得到，不得用上表作为承诺。建议先建立以下负载模型：

- 订单读取/写入比例、列表页分页深度、报表日期跨度。
- 结算文件大小和峰值导入速度。
- Webhook 峰值、重复率、乱序率。
- SP-API 每店铺每端点的真实配额和 429 比例。
- AI/搜索并发、向量维度、召回规模和最大上下文。

### 6.3 SLO、RPO/RTO

建议的首期目标（需业务确认）：

| 能力 | 目标 | 说明 |
|---|---|---|
| ERP API 可用性 | 99.9%/月 | 不含外部平台自身不可用，但内部必须记录外部依赖影响 |
| 核心 API p95 | < 300 ms | 简单查询/命令；复杂报表单列 |
| 订单写入 p95 | < 500 ms | 不含异步外部确认 |
| 订单事件新鲜度 | 95% < 5 分钟 | 通知或增量拉取成功时 |
| 库存对账新鲜度 | 每日完成 | 遵守 Amazon 限流，不承诺秒级全网一致 |
| 报表生成 | 日结在业务时区 08:00 前完成 | 允许失败重跑 |
| 订单/财务 RPO | ≤ 5 分钟 | 通过 binlog/PITR 和 Outbox |
| ERP RTO | ≤ 1 小时 | 核心交易优先 |
| 单店铺集成 RTO | ≤ 4 小时 | 外部依赖恢复后自动续跑 |

SLO 要区分“服务可用”与“业务正确”：返回 200 但订单金额错、库存超卖、凭证不平衡，不算成功。

### 6.4 数据库设计、索引与分页

- 所有列表 API 必须有分页；默认页大小有上限，禁止无界 `selectList`。
- 深分页使用 keyset/cursor（基于时间 + ID），避免大 `OFFSET`。
- 大型表按月或按业务周期分区：订单、事件、广告指标、审计、结算明细；分区键与查询条件一致。
- 索引以查询模式为依据，至少覆盖：
  - `(tenant_id, shop_id, created_at, id)` 用于订单列表；
  - `(tenant_id, shop_id, amazon_order_id, marketplace_id)` 用于幂等查找；
  - `(tenant_id, shop_id, status, updated_at)` 用于调度扫描；
  - `(tenant_id, shop_id, stat_date, sku)` 用于报表；
  - `(consumer_group, tenant_id, event_id)` 用于 Inbox；
  - `(status, next_retry_at, lease_until)` 用于 Outbox/Job。
- 对高频更新和热点行使用短事务；禁止在事务内做外部 HTTP、文件下载或长耗时解析。
- 读写分离只用于可容忍延迟的查询；订单状态、库存扣减、凭证写入必须走主库。
- 连接池按 Little's Law 压测：`并发连接 ≈ 每秒请求 × 平均持有连接时间`；所有服务连接总和不得超过数据库安全上限。
- MySQL 慢查询日志、`performance_schema`、锁等待和复制延迟纳入监控；慢查询需要查询指纹和租户/店铺维度。
- 大表 DDL 使用在线变更工具或分阶段迁移；删除列/索引在确认无旧版本依赖后进行。

### 6.5 缓存与性能

- Redis 键必须带租户前缀和版本号；权限、库存、订单状态的缓存必须有失效策略。
- 不在缓存中保存未加密 PII；不把缓存当成交易事实源。
- 使用 single-flight/互斥避免缓存击穿；热点键加随机 TTL 防止雪崩。
- 本地 Caffeine 只用于低频变化的静态目录，并设置小容量和短 TTL；多副本场景不能依赖本地缓存一致性。
- ES 写入采用 bulk + 重试，刷新策略按查询需求设置；不要在每条业务写后强制 refresh。
- 图片、报表、原始响应使用对象存储和 CDN 能力，不把大文件存数据库。
- 前端列表使用虚拟滚动或服务端分页，避免一次渲染数万行；路由懒加载和按页面拆包。
- 搜索/推荐可降级：Embedding 不可用时纯 BM25；报表服务不可用时显示缓存并明确数据时间。

**现状（本轮静态扫描定位，作为整改清单）**：

- **全表加载进 JVM**：`ShopCredentialStore.java:56` 的 `selectList(null)` 会把全部店铺凭证一次性载入进程内缓存；店铺数增长时既是内存问题也是爆炸半径问题。
- **占用异步池的长阻塞**：`ListingCopyService.java:223` 用 `Thread.sleep(15s)` 轮询 Feed，最长 5 分钟，直接占用 `@Async` 线程；`AgentChatStreamService.java:40`、`OrderAuditServiceImpl.java:182-183`、`WebSocketServer.java:30` 各自 `new Thread`/固定池，缺少统一线程池与队列上限。
- **硬编码 LIMIT 泛滥**：`MemoryServiceImpl:156`、`ProcurementServiceImpl:148`（LIMIT 500）、`CustomerEmailServiceImpl:165`（1000）/`318`（1）、`LogisticsTrackingSyncScheduler:198`、`RealtimeProfitServiceImpl:138/361`、`OrderController:198/205`、`ProfitCalculator:221`、`FinanceServiceImpl:76/112/131/163`、`MultiplatformServiceImpl:414`（`LIMIT 1` 反查归属）、`ProductSelectionServiceImpl:146`。这些“看起来有上限”的写法实际是无游标分页，超过阈值后**静默丢数据**。
- **Mapper 调用密度**：`selectList/selectCount/selectOne/selectById` 共 236 处（排除测试），仓库内 `@Cacheable` / `@CacheEvict` / `CacheManager` **零命中**，缓存实现分散在 Caffeine / `ConcurrentHashMap` / 同步 `LinkedHashMap` / Redis 四套，键维度见下表。
- **缓存键逐条审查（本轮完成）**：结论是**多数已带 shop 维度，只有一处缺少租户维度、一处取的是全局静态值**。

| 缓存位置 | 实现 | 键 | 上限 / TTL | 判定 |
|---|---|---|---|---|
| `ShopCredentialStore.java:35/56` | ConcurrentHashMap + `selectList(null)` | `Long shopId` | 无 TTL，全量预热 | 键正向；**全表加载需改懒加载** |
| `LwaTokenManager.java:51/57/87-92` | ConcurrentHashMap + 每键锁 | `clientId` + `refreshToken` | 过期前 5 分钟刷新 | 正向（含同键 single-flight） |
| `AdvertisingApiRealClient.java:59-60/193` | 两个 ConcurrentHashMap | `"shop:" + shopId` | 1 小时 | 键正向，但**所有店铺拿到的是同一个静态配置 token**（见 P0-13） |
| `FieldPermissionServiceImpl.java:40/126` | ConcurrentHashMap + Redis 回退 | `role` → `entity` | 无 TTL，`loaded` 一次性加载 | **无租户维度**；规则表当前是全局表，一旦允许租户自定义字段权限，此键必须加租户前缀，否则跨租户串规则 |
| `TranslationService.java:49-51` | Caffeine | 文本 + 目标语言 | `maximumSize(10000)`，TTL 10min | 正向（内容寻址，无租户敏感性） |
| `SearchServiceImpl.java:151-186` | ConcurrentHashMap | `userId` | 5min 正缓存 + 30s 负缓存，>10000 机会式清理 | 键正向；注意缓存的是用户对象（PII 需按 5.4 治理） |
| `RealtimeProfitServiceImpl.java:317-351` | 同步 LinkedHashMap（LRU） | `shopId` 前缀 + `sku`（分隔符为竖线字符） | TTL 5min，上限 1024，写入时按店铺前缀失效 | **正向范本**：键、上限、TTL、失效四要素齐全，可直接推广 |
| `ProfitCalculator.java:76/266-275` | ConcurrentHashMap | `shopId` | TTL 计数缓存 | 键正向 |
| `SpiRateLimiter.java:60/66/87` | ConcurrentHashMap | `shopId + ":" + endpoint` | 滑动窗口，按端点策略 | 键正向（按店铺 + 端点隔离） |
| `EmbeddingServiceImpl.java:47-82` | ConcurrentHashMap | 归一化文本 | 上限 512，超限 `clear()` 全清 | 有上限，但**超限全清**会造成周期性抖动，建议改 LRU/分段淘汰 |

- **N+1 与逐行往返（本轮逐条核对，含推翻项）**：
  - 确认存在逐行 DB 往返：`AdReportSyncScheduler.java:112-133`（天数 × 渠道行，每行一次 `selectOne` 再 insert/update）、`SearchTermServiceImpl.java:296`（`saveAsinKeywords` 在一个事务里逐行 `selectOne` 再 update/insert）。两者都应改成批量 `IN` 查询 + 批量 upsert。
  - 确认存在逐项外部调用：`KeepaCompetitorScheduler.java:89`（按去重后的 ASIN 串行调 Keepa，无速率上限；批次内已按 ASIN 去重属于部分缓解）。
  - **推翻上一轮的怀疑**：`RealtimeProfitServiceImpl` 与 `RealReportServiceImpl` 的循环体**没有**逐行 DB/Feign 往返——`RealtimeProfitServiceImpl:80` 是对已取回明细的内存聚合、`:186` 是对 GROUP BY 结果的再组装、`:364` 是解析分摊 JSON；`RealReportServiceImpl:230/267/308` 是对单次 Feign 响应列表的内存遍历。真正的成本是**跨服务扇出次数**：一次仪表盘请求触发 4+ 次 Feign（利润、订单数、广告、趋势），应在阶段 2 用批量聚合接口替代。
  - **正向写法**：`MultiplatformServiceImpl.java:658` 的 `loadExistingOrderKeys` 用 `IN` 批量查 + 内存去重，可作为其它 upsert 循环的改写模板。
- **分布式锁 fail-open**：`DistributedJobLock` 在 Redis 不可达时退化为直接执行，多副本会重复跑批。
- 正向参照：`SearchServiceImpl` 热搜 ZSET 的上限 + TTL、用户信息的 5 分钟 TTL + 30s 负缓存、`RealtimeProfitServiceImpl` 头程缓存的“键 / 上限 / TTL / 按店铺失效”四要素——把这三种模式推广为默认规范。

### 6.6 异步、调度与故障恢复

- MQ 使用 Quorum Queue（或等价持久化队列）+ DLX + 重试队列；设置消息 TTL、最大重试和死信告警。
- 消费者幂等键和状态持久化到数据库；重复消息必须返回已处理结果或安全忽略。
- 调度锁必须 fail-closed：Redis/租约系统不可用时不允许多实例同时执行；宁可延迟任务，也不能重复扣库存/重复出凭证。
- 每个调度器暴露：最后成功时间、下次运行、游标、积压、失败数、重试数、租约持有者。
- 支持暂停/恢复/单店铺重跑；重跑使用 dry-run 和影响范围预览。
- 定期做 DLQ 演练：注入毒消息、验证告警、修复后重放、确认业务状态。

### 6.7 可观测性

- 统一 OpenTelemetry trace/span；至少覆盖 ingress、HTTP、Feign、MQ、DB、外部 API 和 AI 工具。
- 日志结构化，包含 `trace_id/request_id/tenant_id/shop_id/service_id/actor`，敏感字段脱敏。
- 指标至少包含：
  - RED：rate、errors、duration；
  - 队列：depth、age、consumer lag、retry、DLQ；
  - 集成：请求数、限流、429/5xx、最后成功时间、游标滞后；
  - 业务：订单同步延迟、库存差异、结算差异、未平衡凭证、Feed 失败、对账案件；
  - 安全：认证失败、越权拒绝、敏感字段读取、密钥读取、异常导出。
- 指标标签不得使用高基数 `user_id/order_id`；用聚合桶或 trace 查询。
- 告警以用户影响和 SLO burn rate 为中心，避免“每个异常一行告警”。
- 每个告警都必须有 runbook、负责人、降噪条件和关闭标准。

### 6.8 CI/CD 与发布门禁

当前 CI 的问题包括 Checkstyle `continue-on-error`、Docker 只构建默认模块、缺少 Compose/E2E/镜像扫描/部署验证。生产流水线建议：

1. **静态与依赖**：编译、Checkstyle（阻断）、SpotBugs/ErrorProne、依赖漏洞扫描、secret scanning、IaC 扫描。
2. **单元测试**：后端和前端测试；测试报告与覆盖率趋势，但不以单一覆盖率数字替代关键场景测试。
3. **集成测试**：Testcontainers 或 Compose 启动 MySQL/Redis/RabbitMQ/ES/Mongo，验证迁移、Outbox、Inbox、幂等、事务回滚。
4. **契约测试**：OpenAPI schema、消费者驱动契约、事件 schema 兼容性。
5. **安全测试**：越权/跨租户测试、认证绕过、Webhook 验签、PII 脱敏、依赖和镜像 CVE。
6. **构建**：为每个实际部署模块构建镜像，不使用一个默认镜像代表全部服务；生成 SBOM 和 provenance。
7. **端到端**：Playwright 覆盖登录、订单、库存、结算、对账、权限和故障场景；每次发布前一键可重复运行。
8. **部署验证**：K8s/IaC dry-run、迁移演练、回滚演练、健康检查和 smoke test。
9. **发布**：镜像签名和 digest 固定；staging 通过后再 canary/blue-green；支持自动回滚。
10. **审计**：记录构建号、commit、镜像 digest、迁移版本、审批人和部署结果。

### 6.9 备份、灾备与演练

- MySQL：每日全量 + binlog 持续归档 + PITR；定期在隔离环境恢复并校验业务查询。
- Redis：AOF/RDB 与持久化策略匹配缓存重要性；缓存丢失能重建，不作为唯一事实源。
- RabbitMQ：Quorum Queue、定义导出、节点故障演练；消息和 DLQ 有保留期限。
- ES/MongoDB：快照到对象存储，定期恢复；索引可从 MySQL/Outbox 重建。
- 对象存储：版本控制、生命周期、加密和跨区域/跨账户备份（按风险）。
- 每季度至少一次恢复演练，记录 RPO/RTO 实际值；未验证的备份不算备份。
- 故障演练包括：主库切换、Redis 不可用、MQ 节点宕机、外部 API 长时间限流、报表文件损坏、密钥轮换失败、区域级恢复。

### 6.10 成本与运维变量

用户需要提前确认以下成本与组织变量，否则“生产可用”无法落地：

- 云/机房、区域、可用区数量和网络出口费用。
- MySQL HA、只读副本、Redis HA、RabbitMQ 多节点、ES 多节点、对象存储和备份成本。
- 可观测性数据量：日志/trace/指标保留时间和采样率会显著影响费用。
- 短信、短信验证码、AI 模型调用、图片/向量存储、第三方数据服务成本。
- 运维人员数量、值班模式、是否允许云托管、是否允许使用外部 IdP/KMS/Vault。
- 税务、会计、法务和平台合规责任人，而不是全部由工程团队承担。
- 真实 SP-API 开发者资质、店铺授权、沙箱/生产配额和 Amazon 对 DPP/安全问卷的时限。

### 6.11 性能验收标准

1. 订单写入在目标并发下无重复、无丢失、无超卖，事务回滚可验证。
2. 10 万/100 万订单规模下列表 p95 达到目标，且无全表扫描和无界内存加载。
3. 结算导入、库存对账、搜索索引重建在规定窗口内完成并可断点续跑。
4. 外部 429/5xx/超时下，系统保持退避、背压和恢复，不产生重复业务动作。
5. Redis/MQ/ES 单节点故障时，核心交易按设计降级而不是数据损坏。
6. 备份恢复达到 RPO/RTO，恢复后订单、库存、凭证和对账结果一致。
7. 前端在慢网和大列表下可用，会话过期、重连和权限变更行为正确。
8. 发布和回滚在 staging 可重复执行，迁移失败不会留下半完成 schema。

---

## 7. 模拟数据设计（全部标记 SYNTHETIC）

### 7.1 目标与禁止事项

模拟数据的用途是补齐开发和验证缺口，不是伪造经营事实。

允许：

- 生成确定性、可重复、可审计的测试数据和压测数据。
- 覆盖正常、边界、异常、重试、乱序和对账场景。
- 作为 staging、灾备、性能和安全测试输入。

禁止：

- 把模拟数据写入真实生产对账、税务申报或平台结算。
- 把模拟数据伪装成真实 Amazon 数据。
- 在模拟数据中夹带真实姓名、邮箱、地址、电话、银行信息或真实凭证。
- 使用不可重复的随机数导致测试不可复现。

### 7.2 数据标识

每条模拟记录、事件和文件至少带以下标识之一：

```text
data_origin = SYNTHETIC
dataset_id  = synthetic-amazon-erp-{version}
seed        = 固定整数或字符串
```

- 数据库表增加 `data_origin` 字段；事件信封和 API 响应透出只读标记。
- 前端测试模式显示明显的 `SYNTHETIC` 横幅和颜色，不允许和真实环境混淆。
- 报表、导出文件、通知和审计记录保留来源标记。
- 生产环境的模拟数据加载器默认关闭；只有显式 `test/synthetic` profile 可启用。
- **确定性 ≠ 已标注**：`ProductSelectionServiceImpl` 用 `keyword.hashCode() + marketplace.hashCode()` 生成稳定结果，可复现且看似合理，但**没有任何 `SYNTHETIC` 标记**，外观上与真实经营数据无法区分。凡是“算法生成但具备经营语义”的数据（选品评分、AI 建议、趋势预测、广告优化建议）都必须带来源标记，否则会污染报表、决策和对外承诺。
- 模拟数据不得复用真实生产数据库连接或真实对象存储 bucket。

### 7.3 确定性生成器

建议实现独立的 `synthetic-data-generator`（Java 或 Python 均可），规则：

- 固定 seed：同一 `dataset_id + seed` 必须生成完全相同的 UUID、金额、时间和关联关系。
- 使用稳定 UUID v5 或基于业务键的确定性 ID，避免随机主键导致测试不可重复。
- 生成顺序遵循外键依赖：租户 -> 店铺 -> marketplace -> 供应商 -> 商品/SKU -> 成本批次 -> 库存 -> 订单 -> 订单行 -> 履约/退款 -> 结算 -> 凭证 -> 报表。
- 所有金额使用最小货币单位或 `BigDecimal`，明确舍入规则；禁止使用浮点生成财务数据。
- 所有时间用 UTC 保存，显示按店铺/市场时区转换；生成时明确时区和夏令时边界。
- 生成器输出 SQL/JSONL/NDJSON，并提供 schema 校验和行数校验。
- 生成数据可以重复执行 `--reset --seed`，也可追加新的数据集版本。

### 7.4 数据规模档位

| 档位 | 用途 | 建议规模 |
|---|---|---|
| `demo` | 本地演示/功能测试 | 1 租户、3 店铺、3 市场、1 万订单 |
| `ci` | CI 集成/迁移/契约测试 | 1 租户、2 店铺、2 市场、1,000 订单 |
| `staging` | 业务验收、对账、权限 | 1 租户、10 店铺、5 市场、100 万订单 |
| `perf` | 压测和容量评估 | 1–10 租户、100 店铺、20 市场、1,000 万订单或按压测目标扩展 |
| `chaos` | 故障/恢复演练 | 在 staging/perf 基础上注入重复、乱序、延迟、缺失和损坏 |

大文件不提交到 Git；生成脚本、schema 和固定 seed 提交，产物存对象存储或本地临时目录。

### 7.5 业务子图

每个店铺至少生成以下可追溯子图：

- 商品：SPU/ASIN/SKU、变体、市场、分类、Listing 状态、A+ 状态、Feed 提交和结果。
- 成本：供应商、采购单、收货批次、批次成本、FIFO 消耗、头程/关税分摊。
- 库存：FBA/FBM/海外仓/国内仓、库存桶、预占、调整、调拨、退货入库、批次。
- 订单：订单头/行、地址脱敏、币种、促销、费用、税费、发货、取消、退款、部分退款、退货、换货。
- 结算：结算批次、明细、费用类型、退款、广告费、仓储费、促销返点、汇率、VAT。
- 广告：profile、campaign、ad group、keyword、target、search term、日指标、归因窗口、变更记录。
- 客服：工单、消息、邮件任务、负面评价、RMA、SLA。
- 审计与集成：Outbox、Inbox、Webhook、报表原始对象、重试、DLQ、对账案件。

### 7.6 必须覆盖的异常场景

1. 同一 `amazon_order_id` 出现在不同店铺/市场，不能互相覆盖。
2. 同一 Webhook 事件重复投递、乱序投递、延迟投递、签名失败和跨店铺重放。
3. 订单状态从 Unshipped -> Shipped -> Cancelled -> Refunded 的非法或非典型序列。
4. 部分发货、部分退款、多次退款、退货后补发、取消后仍收到结算。
5. 结算文件缺列、金额不平衡、币种不一致、重复行、负数、延迟到账和历史补结算。
6. 库存缺失、负数、快照过期、平台与内部台账不一致、超卖、预占冲突。
7. 同一 SKU 多批次、FIFO 顺序变化、成本缺失、批次退货、采购价格变动。
8. Feed 提交成功但处理失败、结果报告下载失败、A+ 状态回滚、Listing 被平台侧修改。
9. 广告 refresh token 失效、429、5xx、分页中断、归因窗口跨日、重复指标。
10. 新用户注册、验证码发送失败、refresh token 重用、用户停用后长连接仍存活。
11. Redis/MQ/ES 不可用、数据库主从切换、任务租约过期和 DLQ 重放。
12. PII 删除请求、备份恢复、密钥轮换、审计日志完整性校验。

### 7.7 数据质量校验

生成并在入库前验证：

- 外键完整性、唯一键、枚举值、时间顺序和版本单调性。
- 订单头金额 = 行金额 + 费用 + 调整项（按币种和舍入规则）。
- 凭证借贷平衡；结算明细与凭证可追溯。
- 库存移动账的期末余额与余额表一致；不能出现无来源扣减。
- 订单、退款、结算和对账金额可重算。
- 所有 PII 标记字段在导出、日志和测试报告中默认脱敏。
- 每批数据有行数、哈希、生成时间和 seed；变更生成规则必须提升 dataset 版本。

### 7.8 测试验收

- 固定 seed 生成两次，产出哈希一致。
- 从空库执行全量迁移 + 模拟数据 + 业务 E2E 成功。
- 从上一版本数据库执行增量迁移 + 对账成功。
- 每个异常场景有自动化测试、预期结果和证据。
- 压测数据可重复使用，并能生成报表/对账案例而不污染生产。

### 7.9 首批交付物与固定 seed（设计，待批准后才落代码）

用户已确认：**暂无真实数据，也暂无任何平台凭证**。因此模拟数据是开发与验收的主要输入。下列内容为**设计**，未经批准不写实现代码。

| 产物 | 建议路径 | 说明 |
|---|---|---|
| 生成器 | `tools/synthetic-data/`（独立目录，不依赖任何业务模块） | 固定 seed、稳定 UUID v5、输出 SQL/JSONL |
| 数据集元数据 | `tools/synthetic-data/datasets/{tier}/dataset.json` | `dataset_id`、`seed`、规模、生成时间、行数、文件哈希 |
| demo 档产物 | `tools/synthetic-data/out/demo/*.sql` | 1 租户 / 3 店铺 / 3 市场 / 1 万订单 |
| ci 档产物 | `tools/synthetic-data/out/ci/*.sql` | 1 租户 / 2 店铺 / 2 市场 / 1,000 订单 |
| 校验脚本 | `tools/synthetic-data/verify.ps1` 与 `verify.sh` | 行数、哈希、外键、标记字段校验 |

首批固定值：

```text
dataset_id  = synthetic-amazon-erp-v1
seed        = 20260924
data_origin = SYNTHETIC
tenant_id   = 900000000000000001
shop_id     = 900000000000000101 / 900000000000000102 / 900000000000000103
```

要求：

1. **列定义只从既有 DDL 取**：`docker/init-sql/`（32 个文件、105 条 `CREATE TABLE`）与各服务 `db/migration`；生成器的列清单必须从 DDL 自动推导或与 DDL 做哈希比对，禁止手抄，避免再出现 §7 之外的新 schema 副本（附录 B）。
2. 大文件不提交 Git：只提交生成器、DDL 快照与固定 seed；产物写对象存储或本地临时目录并附 SHA-256。
3. `amz_shop_credential` 合成行（用于验证“凭证即插即用”入口，**不含任何真实范围值**）：

```text
shop_id                 = 900000000000000101
client_id               = SYNTHETIC_LWA_CLIENT_ID
client_secret_encrypted = CryptoUtil 加密 "SYNTHETIC_CLIENT_SECRET"
refresh_token_encrypted = CryptoUtil 加密 "SYNTHETIC_REFRESH_TOKEN"
access_key_encrypted    = CryptoUtil 加密 "SYNTHETIC_AWS_ACCESS_KEY"
secret_key_encrypted    = CryptoUtil 加密 "SYNTHETIC_AWS_SECRET_KEY"
region                  = NA
marketplace_id          = SYNTHETIC_MARKETPLACE_ID
seller_id               = SYNTHETIC_SELLER_ID
```

**前置条件（必须先修，否则这一步直接失败）**：`CryptoUtil.init()` 要求 `AMZ_CRYPTO_KEY` 解码后恰为 32 字节，而 `k8s/secret.yaml` 现有值解码为 34 字节（P0-29），且该表当前没有任何自动建表路径（P0-23）。生成合成凭证前必须先替换为合法密钥（例如 `openssl rand -base64 32`）并把凭证表纳入迁移。

校验命令（示例，实现后固化进 CI）：

```powershell
# 1) 确定性：生成两次，产物哈希必须一致
Get-FileHash tools/synthetic-data/out/ci/*.sql -Algorithm SHA256 | Sort-Object Path
# 2) 标记完整性：所有生成脚本与产物都带 SYNTHETIC 标记
git grep -n "SYNTHETIC" -- tools/synthetic-data
# 3) 部署后确认凭证表真实存在（P0-23 的回归检查）
# SELECT COUNT(*) FROM information_schema.tables
#  WHERE table_schema = 'amz_spapi' AND table_name = 'amz_shop_credential';
```

上述产物（生成器、数据集、校验脚本）均列为**待批准项**；在用户批准实施计划之前，本文只保留设计，不落地代码或数据文件。

---

## 8. 路线图、迁移与生产验收

### 8.1 路线图原则

路线图按“先阻止损失，再建立事实源，再自动化”排序。以下工期是**在假设团队有 6–8 名熟悉 Java/云原生/财务领域的工程师、1 名 SRE、1 名 QA、1 名安全负责人时的粗略区间**，不是承诺；人员、真实 SP-API 联调、合规评审和第三方系统会成为关键路径。

### 8.2 阶段 0：生产阻断修复（约 2–6 周）

目标：让系统不再存在“明显可被利用或会产生错误经营数据”的入口。

- 生产配置：禁止 mock、清理默认密码、移除已提交 Secret、关闭公网基础设施端口。
- 身份：修复 `/internal`、用户 IDOR、SSE/WebSocket 身份、租户/店铺/字段权限 fail-closed。
- 注册/登录：修复用户实体与 DDL 漂移、短信通道、refresh token 轮换与撤销。
- 数据：修复报表数据库漂移；建立单一迁移入口；阻止 Compose/mock 进入生产。
- 运维：开启 TLS、Actuator 限制、NetworkPolicy、备份和基础告警。
- CI：Checkstyle 阻断、全模块编译、迁移测试、secret/依赖/镜像扫描。

验收门槛：P0 表中每项有修复、自动化测试和证据；没有未处理的 critical/high 安全问题。

### 8.3 阶段 1：事实模型与一致性（约 6–12 周）

- 引入 `tenant_id/shop_id/version` 和统一迁移规范。
- 订单头/行/事件/费用/退款模型落地；同步改为通知 + 增量 + 报表对账。
- 库存台账、预占、扣减、冲销、调拨、退货入库落地。
- Outbox/Inbox/Job/Webhook 表和统一事件信封落地。
- 凭证/KMS/密钥轮换；PII vault、脱敏和保留任务第一版。
- 搜索结果和缓存强制租户/店铺过滤。

验收门槛：订单、库存、集成三大链路可重放；跨租户/跨店铺负向测试通过；故障注入不产生重复扣减。

### 8.4 阶段 2：业务闭环（约 8–16 周）

- 采购到付款、三单匹配、供应商生命周期。
- WMS 入库/出库/调拨/退货/面单/轨迹。
- RMA/客服 SLA 与 PII 生命周期。
- 双分录账本、FIFO 成本层、多币种、税务规则、结算对账。
- 广告 OAuth/分页/限流/归因/护栏和变更审计。
- Listing 版本、Feed 结果闭环、ES/搜索派生同步。

验收门槛：财务试算平衡；结算、库存、订单对账率达到业务阈值；所有差异可建案、分派和关闭。

### 8.5 阶段 3：规模化、智能化和 SaaS 化（约 12–24 周）

- 运行单元收敛、容量压测、缓存/索引优化和报表读模型。
- 多租户 SaaS 权限、租户级限流、成本核算和租户隔离测试。
- AI Agent 工具安全、提示注入防护、审批与审计。
- 自动补货、广告自动化、异常检测、预测和智能客服。
- 跨区域灾备、成本优化和持续合规审计。

验收门槛：达到目标 SLO/RPO/RTO；多租户隔离通过独立渗透与数据泄漏测试；自动化动作有护栏和可回滚证据。

### 8.6 数据迁移策略

1. 建立旧库快照与只读校验环境；记录每个源表的行数、金额、状态分布和哈希。
2. 设计目标 schema 和映射表；对订单号、店铺、市场、币种、状态建立明确映射。
3. 使用 expand/contract：先加新列/新表，双写或回填，再切换读路径，最后移除旧路径。
4. 回填按租户/店铺/时间分批，可暂停和续跑；每批记录 checkpoint、行数和错误。
5. 对账旧新订单头/行/金额/库存/凭证；差异进入迁移差异清单，人工确认后再切换。
6. 切换前执行冻结窗口或增量追赶；切换后保留只读回退路径一段时间。
7. 回滚不反向覆盖新事实源；优先通过修复和新迁移解决，避免双主。

### 8.7 发布与回滚

- 数据库迁移向前兼容；先部署兼容新旧 schema 的版本，再切流量。
- 生产发布采用 canary 或 blue-green；观察错误率、p95、队列、外部限流、对账差异。
- 回滚脚本必须测试过，并且回滚不丢失已接收的 Outbox/Inbox 事件。
- 高风险模块（库存、支付/退款、凭证、广告预算）默认关闭自动执行，先干跑或只生成建议。
- 每次发布记录镜像 digest、迁移版本、配置版本、审批人和回滚点。

### 8.8 最终生产验收清单

- [ ] 生产环境无 mock、无默认凭据、无公开内部端口。
- [ ] 身份、租户、店铺、字段权限 fail-closed，跨租户测试通过。
- [ ] 订单头/行/事件/费用/退款可重放并与报表对账。
- [ ] 库存台账、预占、扣减、调拨、退货可原子更新且无超卖。
- [ ] 财务凭证借贷平衡、FIFO 成本可追溯、结算可对账。
- [ ] Outbox/Inbox/DLQ/Replay、Webhook 验签和幂等全部通过故障注入。
- [ ] SP-API 真实/沙箱联调完成；429、分页、SQS 重复和乱序处理通过。
- [ ] PII 分类、加密、掩码、保留、删除、审计和备份策略落地。
- [ ] 密钥托管、轮换、吊销和最小权限落地。
- [ ] HA、备份、PITR、RPO/RTO 和恢复演练通过。
- [ ] SLO、告警、值班、runbook、事故响应和安全 SLA 落地。
- [ ] CI/CD 全门禁通过；镜像签名、SBOM、迁移和回滚验证通过。
- [ ] 模拟数据全部标记 `SYNTHETIC`，可通过 seed 重现。
- [ ] 业务、财务、税务、法务和安全负责人签字确认。

### 8.9 需要用户补充的信息

以下信息缺失会显著改变设计与工期，必须在实施前确认：

1. 部署形态：单租户私有化、SaaS 多租户，还是两者都要。
2. 云/机房、区域、是否有 Kubernetes、是否允许使用托管 MySQL/Redis/RabbitMQ/ES/KMS。
3. 店铺数量、市场、SKU、订单量、结算量、广告量、峰值和增长预期。
4. 真实 SP-API 开发者资质、授权方式、沙箱/生产配额、是否有 Amazon 客户经理或安全联系人。
5. 会计制度、税务辖区、总账系统（金蝶等）、币种、关账和审计要求。
6. 短信、邮件、广告、AI 模型、对象存储和日志平台供应商。
7. 团队规模、值班能力、允许的运维自动化程度和预算范围。
8. 是否已有真实生产数据需要迁移；若没有，模拟数据只用于开发和演练。

---

## 附录 A：关键证据路径

### A.1 安全、身份与租户

- `amz-common/src/main/java/com/amz/interceptor/BaseAuthInterceptor.java`
- `amz-common/src/main/java/com/amz/context/UserContext.java`
- `amz-common/src/main/java/com/amz/aspect/ShopIdGuardAspect.java`
- `amz-common/src/main/java/com/amz/service/impl/FieldPermissionServiceImpl.java`
- `amz-common/src/main/java/com/amz/aspect/FieldPermissionAspect.java`
- `amz-common/src/main/java/com/amz/util/CryptoUtil.java`
- `amz-common/src/main/java/com/amz/config/FeignAuthRelayConfig.java`
- `amz-common/src/main/java/com/amz/lock/DistributedJobLock.java`
- `amz-gateway/src/main/java/com/amz/filter/MyGlobalFilter.java`
- `amz-gateway/src/main/resources/application.yml`
- `amz-common/src/main/java/com/amz/util/JwtUtil.java`（50–58 行密钥为空即启动失败；172–188 行 refresh 校验）
- `amz-service/amz-service-ai/src/main/java/com/amz/controller/AgentMemoryController.java`（6 个端点 userId 来自请求）
- `amz-service/amz-service-ai/src/main/java/com/amz/controller/KnowledgeController.java` 与 `.../ai/knowledge/DocumentParser.java`（Tika 解析入口）
- `amz-service/amz-service-ops/src/main/java/com/amz/service/impl/ProductSelectionServiceImpl.java`（硬编码店铺 1、IDOR、未标 SYNTHETIC）

### A.2 用户、AI、消息

- `amz-service/amz-service-user/src/main/java/com/amz/service/impl/LoginServiceImpl.java`
- `amz-service/amz-service-user/src/main/java/com/amz/model/pojo/User.java`
- `amz-service/amz-service-user/src/main/java/com/amz/controller/UserController.java`
- `amz-service/amz-service-ai/src/main/java/com/amz/controller/AgentSseController.java`
- `amz-service/amz-service-ai/src/main/java/com/amz/agent/AgentChatStreamService.java`
- `amz-service/amz-service-ai/src/main/java/com/amz/agent/ErpToolExecutor.java`
- `amz-service/amz-service-message/src/main/java/com/amz/controller/MessageNotifyController.java`
- `amz-service/amz-service-message/src/main/java/com/amz/handler/WebSocketHandler.java`
- `amz-service/amz-service-message/src/main/java/com/amz/mq/consumer/MessageNoticeConsumer.java`
- `amz-service/amz-service-search/src/main/java/com/amz/service/impl/SearchServiceImpl.java`（hybridSearch BM25/kNN 无租户过滤）
- `amz-service/amz-service-ai/src/main/java/com/amz/agent/AgentChatStreamService.java`、`.../agent/ErpToolExecutor.java`（线程池只传 trace；OPERATE_TOOLS 鉴权 fail-open）

### A.3 业务与集成

- `amz-service/amz-service-order/src/main/java/com/amz/service/impl/OrderServiceImpl.java`
- `amz-service/amz-service-order/src/main/java/com/amz/mq/consumer/OrderConsumer.java`
- `amz-service/amz-service-order/src/main/java/com/amz/config/MybatisPlusConfig.java`
- `amz-service/amz-service-spapi/src/main/java/com/amz/scheduler/OrderSyncScheduler.java`
- `amz-service/amz-service-spapi/src/main/java/com/amz/scheduler/InventorySyncScheduler.java`
- `amz-service/amz-service-spapi/src/main/java/com/amz/analytics/InventoryHealthAnalyzer.java`
- `amz-service/amz-service-spapi/src/main/java/com/amz/credential/ShopCredentialStore.java`
- `amz-service/amz-service-spapi/src/main/java/com/amz/client/OrdersClient.java`
- `amz-service/amz-service-spapi/src/main/java/com/amz/client/ReportsRealClient.java`
- `amz-service/amz-service-spapi/` 模块重点路径（第 10 轮补审）：
  - `src/main/java/com/amz/ratelimit/SpiRateLimiter.java`（70–73 行默认配额；96–113 行锁内 `Thread.sleep`；139–143 行只收紧不恢复）
  - `src/main/java/com/amz/client/FeedsClient.java`（378 行；无 `resultFeedDocumentId` 处理）
  - `src/main/java/com/amz/config/SpApiConfig.java`（33 行 `spapi.lwa-endpoint` 默认值）
  - `src/main/resources/application.yml`（7 行默认 `mock`；69 行 LWA 端点；83 行 `crypto.key`）
  - `src/main/resources/db/schema.sql`（11–24 行 `amz_shop_credential`）与 `src/main/resources/db/migration/V1__init.sql`（只建 7 张表，不含凭证表）
  - `src/main/java/com/amz/credential/ShopCredentialStore.java`（56 行全表加载；62–64 行加载失败仅 warn）
  - `src/main/java/com/amz/controller/SpapiController.java`（69–82 行凭证写入与 body 内 shopId 校验；94–96 行缺凭证显式失败）
  - `src/main/java/com/amz/scheduler/OrderSyncScheduler.java`（80、82–85、116–131 行）
- `amz-service/amz-service-product/src/main/java/com/amz/client/SpapiFeedsClient.java`
- `amz-service/amz-service-finance/src/main/java/com/amz/service/impl/SettlementServiceImpl.java`
- `amz-service/amz-service-finance/src/main/java/com/amz/parse/SettlementParser.java`
- `amz-service/amz-service-finance/src/main/java/com/amz/service/impl/SkuProfitServiceImpl.java`
- `amz-service/amz-service-ad/src/main/java/com/amz/client/AdvertisingApiRealClient.java`
- `amz-service/amz-service-logistics/src/main/java/com/amz/service/impl/WarehouseServiceImpl.java`
- `amz-service/amz-service-logistics/src/main/java/com/amz/mapper/WarehouseInventoryMapper.java`
- `amz-service/amz-service-procurement/src/main/java/com/amz/service/impl/FbaShipmentServiceImpl.java`
- `amz-service/amz-service-customer/src/main/java/com/amz/service/impl/CustomerEmailServiceImpl.java`
- `amz-service/amz-service-multiplatform/src/main/java/com/amz/service/impl/MultiplatformServiceImpl.java`（395–443 行 Webhook 无验签 + 归属反查）
- `amz-service/amz-service-multiplatform/src/main/java/com/amz/controller/MultiplatformController.java`（132–137 行无守卫 webhook；167–172 行 `/oauth/token`）
- `amz-service/amz-service-ad/src/main/java/com/amz/service/impl/AdServiceImpl.java`
- `amz-service/amz-service-order/src/main/java/com/amz/controller/OrderController.java`
- `amz-service/amz-service-logistics/src/main/java/com/amz/controller/LogisticsController.java`、`.../service/impl/LogisticsServiceImpl.java`、`.../service/impl/WarehouseServiceImpl.java`
- `amz-service/amz-service-customer/src/main/java/com/amz/controller/CustomerController.java`
- `amz-service/amz-service-product/src/main/java/com/amz/service/ListingCopyService.java`（唯一确认的真实写链路；`DEFAULT_PRODUCT_TYPE` 占位在 55 行、`Thread.sleep` 轮询在 208–259 行、`DONE` 后不下载结果报告在 236–242 行、超时留 `SUBMITTED` 在 253–256 行）
- `amz-service/amz-service-finance/src/main/java/com/amz/client/KingdeeRealClient.java`（48–51 行占位号）与 `.../service/impl/FinanceServiceImpl.java`（190–206 行认领条件与状态写回）
- `amz-service/amz-service-finance/src/main/java/com/amz/parse/SettlementParser.java`（136–153 行 rowKey）与 `.../src/main/resources/db/migration/V2__settlement_detail.sql`（16、22 行）、`.../V1__init.sql`（19 行金蝶状态列注释）
- `amz-service/amz-service-procurement/src/main/java/com/amz/client/Alibaba1688RealClient.java`、`Alibaba1688Signer.java`、`Alibaba1688TokenManager.java`（未校准标注与全局 Redis token key）
- `amz-service/amz-service-multiplatform/src/main/java/com/amz/client/SheinRealClient.java`、`TemuRealClient.java`、`TikTokRealClient.java`、`AbstractPlatformClient.java`（`cred(null)` 回退）与 `.../credential/PlatformCredentialService.java`（23–24 行按 platform 回退到首个账号）
- `amz-service/amz-service-message/src/main/java/com/amz/client/impl/MessagingApiRealClient.java`（41、127–128 行：单套 token + 无 SigV4）
- `amz-service/amz-service-ad/src/main/java/com/amz/client/AdvertisingApiRealClient.java`（52–56、92–96、181–204 行：全局凭据与静默降级）
- `amz-service/amz-service-product/src/main/java/com/amz/client/impl/KeepaRealClient.java`（47–49、62–66 行：静默返回 null）与 `amz-service/amz-service-logistics/src/main/java/com/amz/client/LogisticsTrackingRealClient.java`（27、76–102、326–334 行：三层降级、空轨迹）

### A.4 部署、迁移与 CI

- `docker-compose.yml`
- `Dockerfile`
- `.github/workflows/ci.yml`
- `k8s/ingress.yaml`
- `k8s/secret.yaml`
- `k8s/configmap.yaml`
- `k8s/services/`
- `k8s/infra/`
- `init_all_tables.sql`
- `docker/init-sql/`
- `docs/erp-improvement-plan-2026-08-17.md`
- `docs/item7-shopid-audit-report.md`
- `docs/item6b-key-externalization-plan.md`
- `pom.xml`（`micrometer.version`、`mysql-connector-j` 等版本治理点）
- `amz-service/amz-service-ai/pom.xml`（Tika 依赖声明）
- `amz-frontend/package.json`（前端依赖与脚本）
- 本轮审计产物（不随仓库分发）：`target/dependency-tree-runtime.out.log`、`target/maven-coords.txt`、`target/osv/osv-pairs.csv`、`target/osv/osv-matrix.csv`、`target/osv/details/*.json`

## 附录 B：表与迁移口径冲突

| 来源 | 表数量/口径 | 结论 |
|---|---|---|
| `init_all_tables.sql` | 约 57 张唯一表 | 单文件初始化不代表生产迁移历史 |
| `docker/init-sql/` | 约 105 张表 | 与单文件不完全一致 |
| README | 宣称 54 张 | 与代码/脚本不一致 |
| Flyway | 每个服务有各自迁移和 baseline 策略 | 没有统一 schema 版本和端到端迁移测试 |

整改要求：

1. 选择一个权威迁移机制（Flyway 或 Liquibase），所有服务共用版本规则。
2. 移除“启动时执行另一个 SQL 文件”的隐式行为，避免多入口竞争。
3. 提供 clean-install 和 upgrade-from-N-1 两条流水线。
4. 每次变更生成 schema 差异报告；禁止只改实体或只改 SQL。
5. 建立数据库字典，标注租户/店铺/版本/PII/保留期/分区和索引。

**三处建表镜像已经破损（第 10 轮实测）**：`init_all_tables.sql`（约 57 张唯一表）、`docker/init-sql/`（32 个文件，编号 01–33、缺 03，共 105 条 `CREATE TABLE`）、各服务 `db/migration`（Flyway）三套口径并存，并且已经出现真实缺口：`amz_shop_credential` 只在 `amz-service/amz-service-spapi/src/main/resources/db/schema.sql:11` 与 `init_all_tables.sql:459` 各定义一次，`docker/init-sql/` 对 `credential` **0 命中**，spapi 的 `db/migration/V1__init.sql` 也不含此表 → 用 Compose 或 Flyway 部署时该表**根本不存在**，凭证写入直接失败（P0-23）。这说明“多处镜像”不是风格问题，而是已经在制造不可用功能。

## 附录 C：不建议的做法

- 为了“看起来生产化”而只增加 K8s 副本数，不修复数据事实源。
- 用高频轮询替代 Notifications 和 Reports 对账。
- 用 Redis 去重代替持久化 Inbox。
- 用进程内缓存作为店铺凭证或库存的权威来源。
- 用数据库触发器、定时全表扫描或前端隐藏来替代权限模型。
- 在审计日志、异常信息、URL、前端 localStorage 中保存 token 或 PII。
- 把模拟数据直接导入生产用于报表和税务。
- 未做恢复演练就宣称已有备份和 RPO/RTO。
- 未做真实 SP-API 联调就宣称已支持 Amazon 全部能力。
- 在没有财务、税务、法务签字的情况下自动执行退款、结算或税务动作。

## 附录 D：评审清单

请评审时明确回答：

1. 是否接受“单租户私有化优先、保留 tenant_id”的工作假设？
2. 是否接受 5 个逻辑运行单元的目标架构；P0 是否允许先保留现有微服务部署？
3. 是否接受 MySQL 继续作为首期事实库，而不是迁移到 PostgreSQL？
4. 是否接受 BFF + HttpOnly Cookie 替代 localStorage token？
5. 是否确认 PII 30 天、非 PII 18 个月、安全日志 12 个月的默认保留策略？
6. 是否已有真实 SP-API 沙箱/生产授权和 Amazon 安全联系人？
7. 是否已有财务/税务/总账系统接口和责任人？
8. 期望的首期容量、SLO、RPO/RTO 和预算范围是什么？
9. 是否允许引入 Keycloak、Vault/KMS、外部对象存储、企业短信和外部 IdP？
10. 是否确认各外部连接器的真实状态（金蝶未对接、1688/SHEIN/TEMU/TikTok 未沙箱校准、Messaging/Ads 为骨架且静默降级、Keepa/17TRACK 静默空值）？是否接受“阶段 0 完成前不对外宣称任何真实平台对接能力”？
11. 是否接受连接器「凭证即插即用」（API-Ready）验收口径（见 1.9）：提供凭证即可跑通、缺凭证显式失败、生产启动自检、每个连接器必须有沙箱或生产联调记录？
12. 是否批准进入实施计划阶段；批准前不修改业务源码。

## 附录 E：本文自检

- 结论与事实来源：关键问题均能在附录 A 代码路径中找到对应实现或配置；外部约束使用 Amazon 官方文档；容量数字明确标为 `SYNTHETIC`。
- 逻辑跳跃：将“模拟数据”与“真实生产上线”明确分离；不把测试数量等同于生产能力；不把 K8s 多副本等同于高可用。
- 一致性：本文假设与前面设计章节的工作假设保持一致；若评审推翻部署形态或数据库选择，需要重新评估租户、迁移和成本章节。
- 范围：本文覆盖业务、性能、安全、可靠性、运维、合规、数据迁移和验收；不包含具体源码实现，符合“设计先行”的流程。
- 歧义：PII 保留、税务、RPO/RTO、容量、预算和运行单元收敛均列为待确认决策，未伪装成已确定事实。
- **本轮推翻/收窄的假设（凡与以下条目冲突的旧表述，以本节为准）**：(1) “JWT 空密钥静默可用”被推翻——`JwtUtil.init()` 在密钥为空时直接抛异常拒绝启动，属正向设计；(2) “AI 工具会写生产数据”被收窄——8 个 `OPERATE_TOOLS` 中只有 `cross_marketplace_listing` 确认真实写入（SP-API Feeds）；(3) “`generate_promotion_plan` 会写库”被推翻——它是 `@GetMapping` 且返回硬编码方案，完全只读；(4) “OSV 命中 210 个漏洞”表述错误——正确口径是 210 组 `(坐标, advisory)` 配对、190 条唯一 advisory、67 个受影响坐标，且必须去重后取 `GET /v1/vulns/{id}` 才能谈严重度与修复版本；(5) “金蝶客户端仍可能返回 mock”被收窄为必然——`KingdeeRealClient` 无条件返回 `KINGDEE_MOCK_`，凭证写成 `SYNCING` 后**无法重试**，也不是“已过账”；(6) “多平台真实客户端已按官方校准”被推翻——SHEIN/TEMU/TikTok 三家均自述未校准，且发货回传以 `cred(null)` 解析凭证；(7) “1688 已完成真实对接”被推翻——真实客户端自述未校准，凭据为单套全局值（Redis key 无店铺维度）；(8) “Messaging/Ads 已有真实客户端”被收窄为骨架——无 SigV4、无按店铺 profile、失败静默返回空结果；(9) “仓库已有 Outbox”不成立——只有采购单的 Outbox-lite 状态机约定，没有 `outbox_event`/`inbox_event` 表与 relay（见 4.4 现状）。(10) 前几轮“k8s HPA 多副本会导致调度任务双跑”的推测被**撤回**——实测 `DistributedJobLock.runWithLock` 被 10 个调度器使用，互斥成立；该类别缺陷应改记为 `DistributedJobLock` 自身在 Redis 不可用时 fail-open（§1.8 已如实记录）；(11) “`SpiRateLimiter` 已参考官方配额”被推翻——默认 `orders=30/30s` 与官方 0.0167 req/s 相差约 60 倍，`feeds`/`fees`/`finances` 三个在用 endpointTag 没有策略，`listings` 策略无调用方；(12) “SP-API 客户端已可用”被收窄——`ReportsRealClient:80` 取错官方字段名（应为 `reportDocumentId`），结算报表文档 ID 恒为 null；(13) “k8s 已具备可部署 Secret”被推翻——`AMZ_CRYPTO_KEY` 解码为 34 字节，`CryptoUtil` 硬校验 32 字节会让 spapi 启动失败；(14) “Feeds 提交流程已闭环”被推翻——`FeedsClient` 从不下载 `resultFeedDocumentId`，被拒行没有读取渠道；(15) “SP-API 默认加载真实实现”被推翻——默认 profile 为 `mock`，部署清单也不设置 profile，财务域三类客户端返回样例数据；(16) 本规格第 1～7 轮整体未审查 `amz-service-spapi`，本轮补审（附录 G.4）。 (17) “`JAVA_OPTS` 可能带 `-Dspring.profiles.active`”的假设被**推翻**——`k8s/configmap.yaml:47-48` 两处 `JAVA_OPTS`/`JAVA_OPTS_GATEWAY` 均只含堆内存与 GC 参数，16 份 Deployment 无其它 profile 来源（即“k8s 部署会跑 mock”结论**成立且覆盖全部 16 份**）。(18) Redisson 硬编码公网 IP 的影响面**收窄**为 order / product 两个模块——spapi 虽引 `redisson-spring-boot-starter` 但无自定义 `RedissonConfig`；同时该配置键在 `spring.data.*` 迁移后**必然失效**（不是“可能失效”），实测 45.3 s 连接超时。(19) `.env.example` 键数口径修正为 **71 行 / 36 个键**（此前“37 键”说法作废，以本轮正则 `^[A-Z][A-Z0-9_]*=` 计数为准）；“缺 Nacos 与平台凭证”的结论方向不变。(20) `docker-compose.yml` 服务数口径修正为 **31 个 service**（README “17 服务”与旧审计“约 30”均作废）；`env_file` 命中 0，不存在“compose 会统一加载 .env 补齐变量”的兜底路径。(21) 本规格 4.6 表的官方限流数值**自我纠错**——第 16 轮逐文件比对官方 OpenAPI 模型后确认 5 处与模型原文不符（`getReportDocument` 官方 0.0167/15 而非 2/15，使“比官方更严”的结论反向；`reports.getReport` 2/15 未单列；`createFeedDocument` 官方 0.5/15 与 `createFeed` 官方 0.0083/15 被写反；`getFeed`/`cancelFeed` 官方 2/15 而非 0.0222/10；`fees` 实际调用的是 `getMyFeesEstimates`（0.5/1）而非 `getMyFeesEstimateForASIN`（1/2）），以修订后的 4.6 与 1.5.1 为准；(22) “wimoor 技术栈偏旧（Spring Boot 2.0 / JDK 8）”被**推翻**——实测根 `pom.xml` 为 spring-boot-starter-parent **2.6.13** + `<java.version>9</java.version>`，且其每店铺持久化限流门控与文档解密/解压链是本项目可逐行参照的实现（1.5.1 第 8、9 条）；(23) “`x-amzn-RateLimit-Limit` 全仓只读取不闭环”被**收窄**——`FeedsClient.sendWithRetry:308-315` 已在 429 分支读取响应头并回写限流器，缺陷是覆盖面（单端点、仅 429、不恢复、不持久化、不跨进程）。(24) 附录 F.3 增补**本轮复核实例**与**已排除项**——`FeedsController` 的 2 个端点与 `SpapiController#saveCredential` 属“无方法级注解但方法内 `isShopAllowed`”的 C 类（不是 A 类缺口），`GET /spapi/status` 属 B 类探针；`InventoryController`/`ReplenishmentController`/`FinancialDataController`/`FinanceController`/`ReportController`/`OrderAuditController` 经 328 行矩阵复核**无守卫端点均为 0**，后续审计不必重复排查。
---

## 附录 F：328 端点守卫矩阵与 82 条无守卫清单（本轮实测）

**方法**：对 19 个模块中所有 `@RestController` 做静态扫描，逐个抽取 `@GetMapping/@PostMapping/@PutMapping/@DeleteMapping` 的 HTTP 方法、路径、方法级守卫注解（`@ShopScoped` / `@RequireRole`）以及是否存在 `shopId` 参数，得到 328 个端点矩阵。数据列：`Controller, Module, Http, Path, Guards, HasShopIdArg, SigLine, File`。

### F.1 守卫分布（合计 328）

| 守卫组合 | 端点数 |
|---|---|
| `GetMapping,ShopScoped` | 132 |
| `PostMapping,ShopScoped` | 75 |
| `PostMapping`（无守卫注解） | 47 |
| `GetMapping`（无守卫注解） | 23 |
| `PostMapping,RequireRole,ShopScoped` | 23 |
| `PutMapping`（无守卫注解） | 9 |
| `PostMapping,RequireRole` | 7 |
| `PutMapping,ShopScoped` | 6 |
| `DeleteMapping`（无守卫注解） | 3 |
| `DeleteMapping,ShopScoped` | 3 |
| **无任何守卫注解合计** | **82** |

参数维度：`HasShopIdArg=false` 160 个、`true` 168 个。也就是说约一半端点根本没有 shopId 入参——这些端点要么是平台级/用户级资源（需要另一种归属模型），要么就是缺归属校验（需要逐个判定）。

### F.2 模块分布（合计 328）

logistics 54、ad 39、procurement 34、finance 28、multiplatform 26、product 24、report 23、customer 21、order 18、spapi 18、ai 16、ops 12、user 7、message 4、search 4。

### F.3 如何正确使用这 82 条

**82 个无守卫端点 ≠ 82 个漏洞**。分类判定必须逐个读服务实现：

- **A 类（真缺口）**：无守卫且直接读写业务数据，归属来自请求参数。已在 P0-13…P0-20 逐条列出，例如 `/webhook/{platform}/{eventType}`、`/order/saveOrder`、`/customer/receiveMessage`、Agent 记忆全部端点、`/ai/eval/run`。
- **B 类（设计如此但需加固）**：登录、验证码、注册等本就应在认证前的端点，以及集群内 `/internal/**`。这类不是“漏加注解”，而是需要服务身份、mTLS、限流和网段限制。
- **C 类（看似安全但有隐藏依赖）**：靠方法内手工 `isShopAllowed`、靠 `@RequireRole` 上游、或靠 Feign 只被内部调用。这类要显式记录下来，避免后续重构时“顺手删掉一行校验”。 **本轮复核实例**：`FeedsController` 的 2 个端点（`POST /spapi/feeds/submit`、`GET /spapi/feeds/status/{shopId}/{feedId}`）无方法级守卫注解，但方法体内先做 `UserContext.isShopAllowed`；`SpapiController#saveCredential`（`POST /spapi/credential`）同型——其源码注释已说明「`@ShopScoped` 切面仅覆盖 `@RequestParam`/`@PathVariable`，无法校验 `@RequestBody` 内嵌 `shopId`」。同一控制器的 `GET /spapi/status` 只返回固定串 `SP-API service running`，归 B 类健康探针。
- **本轮已排除（328 行矩阵复核，无需再逐个人工排查）**：`InventoryController`（2 端点）、`ReplenishmentController`（3）、`FinancialDataController`（5）、`FinanceController`（4）、`ReportController`（4）、`OrderAuditController`（9）的无守卫端点数**均为 0**。注意矩阵里 `Controller` 列带 `.java` 后缀，用短名过滤会得到 0 行并误判为“不在矩阵中”。

### F.4 必须保留的三条更正（避免后续引用出错）

1. **废弃文件**：早期审计产出的 `endpoint-matrix.csv` 存在列错位问题，**已废弃**，只在阅读历史记录时作为反例。有效数据是 `endpoint-matrix2.csv`（328 行）与 `endpoint-unguarded-triage.csv`（82 行，列：`Module, Http, Path, Controller, Line, Checks, ShopIdArg, File`）。它们生成于仓库外（`~/.cache/codex-tools/`），不随仓库分发，可按 F 节方法重新生成。
2. **注解计数口径**：`@ShopScoped` 258 处、`@RequireRole` 34 处是**注解出现次数**，既不是端点数也不是类级覆盖；两者都是 `@Target(METHOD)`，本仓库**没有任何类级** `@ShopScoped`/`@RequireRole`。因此“方法上没有注解”不能推断为无认证，反过来也不能假设类级注解提供了覆盖。
3. **OSV 口径**：`querybatch` 只返回 `{id, modified}`，不含 severity/summary/fixed；要给结论必须对去重后的 ID 再调 `GET /v1/vulns/{id}`。同时必须保留 `(坐标, advisory)` 配对——早期“按未过滤坐标列表错位映射”的表格**已作废，不得引用**。正确口径见 1.2 与 5.7 节：210 组配对、190 条唯一 advisory、67 个坐标。

### F.5 复现命令（PowerShell）

```powershell
# 端点矩阵：统计守卫组合与无守卫清单（需自行实现扫描脚本，输出同 F.1 的列）
$m = Import-Csv endpoint-matrix2.csv
$m | Group-Object Guards | Sort-Object Count -Descending
($m | Where-Object { $_.Guards -notmatch 'ShopScoped|RequireRole' }).Count   # 应为 82

# 依赖坐标
mvn -B -ntp dependency:tree -Dscope=runtime > target/dependency-tree-runtime.out.log

# OSV 配对查询（保留索引对应关系），再逐 ID 取详情
# POST https://api.osv.dev/v1/querybatch  { queries: [{package:{name,ecosystem:'Maven'},version}] }
# GET  https://api.osv.dev/v1/vulns/{id}
```
## 附录 G：仓库内已失效的既有文档与对外宣称核对

### G.1 `docs/item7-shopid-audit-report.md` 的核心结论已失效

该报告是仓库内**已提交**的审计文档，但其结论与 2026-09-24 的逐行实测矛盾，**不得作为整改基线**：

| 报告位置 | 原文口径 | 实测结论 |
|---|---|---|
| 第 50 行 | “所有涉及多租户业务数据的 Controller 均已覆盖 `@ShopScoped`，合计约 **16 个 Controller、50+ 方法**” | 328 个端点中 **82 个没有任何守卫注解**，且包含已确认的真缺口：`/webhook/{platform}/{eventType}`、`/order/saveOrder`、Agent 记忆全部端点、`/search/**`、`/customer/receiveMessage`、`/logistics/createShipment`、选品相关端点（详见 P0-13…P0-21、附录 F） |
| 第 23 行 | `OrderController` “✅ 全量” | 存在无守卫写入口 `saveOrder`：订单归属来自请求 body，经 MQ 落库（见 P0-15） |
| 第 43 行 | `MultiplatformController` “✅ 全量” | 26 个端点中只有 `/webhook/list/{shopId}` 等少数带 `@ShopScoped`；`/webhook/{platform}/{eventType}`、`/product/sync/**`、`/order/{orderId}/ship` 等均无注解（`markShipped` 内部手工 `isShopAllowed`，属 C 类） |
| 第 44 行 | `CustomerController` / `CustomerEmailController` “✅ 全量” | 客服域存在无守卫端点，且写操作缺少店铺归属校验 |
| 第 54 行 | `ErpToolExecutor` “在入口（第 100 行）对携带 `shopId` 的工具强制 `UserContext.isShopAllowed(argShopId)`，覆盖全部 28 个工具” | 入口校验确实存在（`ErpToolExecutor.java:121`），但 `hasOperatePermission()`（196-201）在 `role == null` 时 **`return true`**，属于 **fail-open**；“有校验” ≠ “校验 fail-closed”（见 P0-04） |

该报告第 61 行“`analyze_sales_trend` 已从 `TOOLS_IGNORING_SHOP_ID` 白名单移除”（`ErpToolExecutor.java:81-83`）经核对是**真实**的，但它只解决“工具是否需要 shopId”这一层，不解决身份缺失时的放行问题。两类结论必须分开引用，不能互相替代。

### G.2 对外能力宣称与代码事实核对表

| 宣称来源 | 原文 | 代码事实 | 处置 |
|---|---|---|---|
| `README.md:43` | 多平台支持 Shopify/eBay/Walmart/Shopee/Lazada | 只有 Temu/TikTok/Shein 有接口与 Real/Mock 实现；无任何其它平台类或分发分支 | 修正 README 或补齐实现 |
| `docs/es-native-rrf-check.md` | “索引 `amz_product` 存在且有 `embedding` 数据” | 仓库内无 index template、compose 的 ES 官方镜像未装 IK 插件、无索引初始化脚本 | 该文档是**验证前置条件**，不是既成事实；需先建索引再验证 |
| `docker-compose.yml:137-150` | ES 作为检索依赖 | `xpack.security.enabled=false`、单节点、512m 堆、无插件 | 仅限本地；共享/生产环境必须重建拓扑与安全配置 |
| README 模块清单与“完成”措辞 | 各服务能力描述 | 见 1.4 业务模型缺口与 1.3 P0 清单 | 以本规格为准 |

### G.3 `docs/erp-improvement-plan-2026-08-17.md` 的“全部完成”与代码事实矛盾

该文档第 5 行声明“**全部 8 项已完成**，里程碑 A/B/C/D 全部闭环，546 单测全绿”，但同一文档与代码事实均不支持：

| 该文档位置 | 原文口径 | 实测结论 |
|---|---|---|
| 第 5 行 | “全部 8 项已完成 … 546 单测全绿” | 本轮实测后端 `@Test` 527 处、执行 527（0 失败 / 2 跳过），前端 133 用例；数字口径与 546 不符，且“全部完成”与 1.3 的 32 条 P0、1.4 的业务缺口直接矛盾 |
| 第 14 行 | 第 2 项“1688 开放平台真实对接 ✅ 已完成” | **同文档第 55 行**自述该客户端是“`log.warn` + 返回 `1688_MOCK_` 占位”的骨架；代码侧 `Alibaba1688Signer:24`、`Alibaba1688TokenManager:26`、`Alibaba1688RealClient:37-38` 均自述未校准 |
| 第 16 行 | 第 4 项“多平台签名按官方校准 ✅ 已完成” | `SheinRealClient:103-106`、`TemuRealClient:94-96`、`TikTokRealClient:177-180` 均自述“未校准”，待平台沙箱确认 |
| 第 18 行 | 第 6 项“凭证管理（密管+多租户）✅ 已完成” | 只有 multiplatform 域实现按 `(shopId, platform)` 解析（`PlatformCredentialService`）；finance（金蝶）、procurement（1688）、message（Messaging）、ad（Ads）仍是全局 `@Value` 单套凭据 |
| 第 19 行 | 第 7 项“Feign 端点 shopId 越权收口 ✅ 已完成” | 328 个端点中仍有 82 条无任何守卫注解（附录 F），其中包含已确认的真缺口（P0-15、P0-17、P0-19、P0-20） |
| 第 164 行 | “1688 / 多平台签名：真实实现后补签名与沙箱测试（见 2.2/2.4）” | 这句话本身就承认沙箱校准尚未完成，与第 5 行的“全部完成”属同一文档内的自相矛盾 |

对照：`docs/amazon-ai-agent-execution-plan-2026-09-16.html` 的进度看板默认显示“已完成 0 / 28”（第 139 行），且勾选状态只存在浏览器本地（第 132 行）——它不能作为完成度证据，但至少没有宣称已全部完成。

### G.4 漏审教训与本轮方法论（第 10 轮新增）

| # | 教训 | 依据 |
|---|---|---|
| 1 | **前 7 轮审计整体漏掉了 `amz-service-spapi` 模块**，导致凭证表缺失、默认 mock、Reports 字段名错误、限流配额失真四个 P0 长期未被发现 | 本文 P0-23、P0-24、P0-27、P0-28 均为第 10 轮首次记录；此前章节的“SP-API 已接入”判断缺这一模块的逐行核对 |
| 2 | **“用官方 OpenAPI 模型反查代码”应成为所有连接器的强制验收手段** | 一次比对 `reports_2021-06-30.json` 即同时查出字段名错误（P0-27）与配额量级差异（P0-28），成本远低于沙箱联调 |
| 3 | **审计必须覆盖“配置的加载路径”，而不只是配置的存在** | P0-25 的三个断面（变量名不一致、`bootstrap.yml` 在当前依赖下不生效、死配置文件）都只能通过追踪“谁真正读了它”发现 |
| 4 | **“能启动”不等于“表存在”** | `ShopCredentialStore` 在表缺失时只 warn 并空缓存启动（P0-23），使部署成功与功能可用被混淆 |
| 5 | **自测通过会掩盖协议级错误** | 527 个后端用例全绿，但 `resultDocumentId` 与 `reportDocumentId` 的差异、Feeds 结果报告缺失都不会被现有用例覆盖 |

后续审计与验收的执行规则：每个连接器必须同时给出（a）官方模型或文档依据，（b）真实读取凭证/配置的代码行号，（c）正例与反例各一条，三者缺一即记为“未核对”。

### G.5 使用规则

1. 本文档与仓库内既有审计文档冲突时，**以本文档标注的源码行号为准**，并重新执行 F.5 的复现命令核对。
2. 任何“已完成 / 已支持 / 已通过”的表述，必须同时给出来源：源码路径 + 行号、测试命令 + 输出、或联调记录。三者缺一即为待验证。
3. 对外材料（README、官网、销售材料、投标文件）在阶段 0 结束前**不得**引用未经核对的模块能力与安全结论。

---
