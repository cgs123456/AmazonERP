# AmazonERP 全方位 Review 与生产化升级方案

> 审计日期：2026-09-28（Asia/Shanghai）  
> 审计对象：`C:\Users\Administrator\Desktop\AmazonERP`  
> 当前分支：`codex/api-ready-connectors`  
> 当前 HEAD：`3c8f21ed21c2cc77cbf08d1f12ddd4256265ac25`  
> 审计方式：只读代码/配置/迁移/文档审查 + 历史运行日志复核 + 官方文档与 GitHub 同类项目调研  
> 本次未完成：真实 Amazon SP-API/Ads 沙箱联调、真实卖家生产授权、全栈当前运行时复验、生产压测、灾备演练、安全渗透测试  
> 本次唯一新增文件：本报告。未修改代码、配置、测试、数据库或既有文档。

## 0. 结论先行

### 0.1 当前系统应该怎么称呼

当前项目可以准确描述为：

**具备 Amazon SP-API/Ads 对接能力、API-Ready（未联调）的跨境电商 ERP 工程。**

但不能描述为：

- “已经接通 Amazon SP-API”；
- “已完成真实联调”；
- “当前可以直接生产部署”；
- “有 API 凭据就可以零改造使用”。

项目 README 自身也明确承认：当前仓库不能按现状视为可直接生产部署；最高工程证据仍是进程内桩和官方 OpenAPI 快照，不是沙箱或生产联调。该结论与本次审查一致。

### 0.2 对“有 API 就可以直接使用”的直接纠正

这句话只有一半成立：

- **成立的一半**：仓库已经具备不少 API-Ready 基础设施，包括 LWA/SigV4 兼容层、SP-API 统一客户端、限流表、Outbox/Inbox、通知入站、凭证存储、店铺隔离、RBAC、连接器能力台账和自检页面。真实凭据到位后，很多接入动作可以通过配置、授权和联调完成，不必从零写客户端。
- **不成立的一半**：Amazon 侧仍要求应用注册/审核、卖家 OAuth 授权、角色/权限申请、RDT/PII 授权、marketplace/region/endpoint 校验、通知订阅、限流与字段契约验证，以及沙箱和生产验收。拿到一组 client id/secret/refresh token 只证明“有调用资格”，不证明业务语义、权限范围、财务口径、通知可靠性和灾备能力正确。
- **因此本报告的结论是**：当前应继续以“API-Ready 底座”建设，但在 E4 沙箱和 E5 生产试点完成前，不得把系统标为生产就绪。

### 0.3 最重要的生产阻断项

按影响面排序，当前最需要先处理的不是继续增加页面或 AI 工具，而是以下基础能力：

1. **发布基线不可复现**：工作区高度脏乱，HEAD 不等于被审计内容，生产发布缺少可追溯快照。
2. **参数校验基本缺失**：没有有效引入 Bean Validation，非法请求主要靠业务代码和数据库兜底。
3. **HTTP 异常契约错误**：业务/参数/运行时异常通常仍返回 HTTP 200，只靠响应体 `code` 区分错误，破坏网关、LB、监控、重试和第三方集成语义。
4. **审计没有真正落库**：存在 `amz_oper_log` 表和切面，但没有实际 `@OperLog` 使用和持久化闭环。
5. **权限系统存在 fail-open**：字段权限加载失败、未知角色/实体、空参数等情况会扩大可见范围。
6. **租户/店铺隔离不是统一强约束**：`@ShopScoped` 覆盖面有限，`UserContext` 兼容逻辑和若干手工校验点需要逐端点收敛。
7. **敏感凭据生命周期不完整**：OAuth token 明文入库，缺少校验、刷新、撤销、过期清理和资源服务闭环；webhook 缺少平台签名验证证据。
8. **可观测性和告警链不可用**：Prometheus 目标、端口、exporter 和 Alertmanager receiver 存在明显缺口。
9. **部署安全未达生产标准**：无 TLS、`ssl-redirect=false`、Actuator 暴露、生产前端占位域名、K8s Secret 仍是强制替换占位。
10. **真实外部接入未取证**：当前没有 E4/E5 证据，无法证明 SP-API 限流兼容、RDT/PII、通知可靠性、订单/库存/财务字段正确性。

### 0.4 生产就绪度判断

| 维度 | 当前判断 | 说明 |
|---|---|---|
| 功能广度 | 中上 | 15 个业务服务、订单/库存/采购/物流/财务/广告/AI/报表等域均有代码路径 |
| 架构基础 | 中上 | Spring Cloud、Flyway、Outbox/Inbox、RBAC、店铺上下文、合成数据工具链已具备 |
| 安全性 | 不达标 | 校验、审计、字段权限、token 生命周期、TLS、Secret 管理仍有生产阻断 |
| 租户隔离 | 部分达标 | 网关和多数服务有隔离设计，但静态扫描显示覆盖不完整，不能宣称全域强隔离 |
| API 对接 | API-Ready | 具备适配层和配置能力；最高证据约 E3，未完成 E4/E5 |
| 可靠性 | 不达标 | Redis 启动强依赖、MQ 消费链不均一、调度锁/重试/灾备未闭环 |
| 可观测性 | 不达标 | 指标抓取和告警路由存在确定性配置缺口 |
| 发布工程 | 不达标 | 脏工作区、临时日志/脚本混入、CI 门禁偏弱、缺少 SBOM/漏洞/签名 |
| 测试 | 有基础但不足 | 历史日志显示大量单元测试通过；缺真实联调、契约全量、覆盖率和生产验收 |
| 生产结论 | **不具备上线条件** | 只能作为 API-Ready 工程继续做生产化加固 |

## 1. 审计边界、方法与证据等级

### 1.1 本次做了什么

- 阅读 Maven 模块、Spring Boot 配置、Controller、Service、AOP、Interceptor、Mapper、Flyway migration。
- 检查 Docker Compose、Kubernetes、Prometheus、Alertmanager、Grafana、CI workflow。
- 检查前端生产环境配置、API 调用层和既有测试日志。
- 统计主要规模指标、注解覆盖和端点分布。
- 对照 Amazon 官方文档、官方样例仓库和开源同类 ERP/SP-API 项目。
- 复用仓库中已有的历史构建、测试和运行日志，但不把历史日志当作本次运行证据。

### 1.2 证据等级

本报告采用以下等级，避免把“代码写了”误当成“真实环境验证过了”：

| 等级 | 含义 | 当前状态 |
|---|---|---|
| E1 | 静态代码/配置证据 | 已大量具备 |
| E2 | 单元测试、进程内桩、测试替身 | 已大量具备 |
| E3 | 官方 OpenAPI 快照、契约测试 | 部分具备 |
| E4 | 真实 Amazon 沙箱/测试账号联调 | **未取得** |
| E5 | 真实卖家生产试点、对账和运维验收 | **未取得** |

### 1.3 工作区基线

写入本报告前测得：

- `git status --porcelain`：626 项；
- `git diff --stat`：307 files changed，24,065 insertions，12,773 deletions；
- 状态约：301 个 modified、301 个 untracked、1 个 deleted，另有 staged 状态；
- 根目录存在大量日志、临时脚本和运行产物，例如 `_r83_spapi_final_out.log`（约 114 MB）、`full3.log`、`.patch_*.py`、`.mvn-*.log`、`ProbeUpdateWrapperTest.java` 等。

这说明当前工作区不是干净、可审计、可复现的生产发布基线。即使代码逻辑没有问题，也无法把当前目录直接等同于某个可发布版本。

### 1.4 规模与静态统计口径

以下是静态扫描结果，不代表运行时覆盖：

- Java main：786 个文件；
- Java test：234 个文件；
- Controller：59 个；
- HTTP mapping：约 346 个（按注解行口径；简单文本扫描会因注释/导入得到更高值）；
- `@Scheduled`：23 处；
- `@RabbitListener`：4 处；
- 主代码 Flyway migration：49 个；
- 全仓 `V*__*.sql`（含测试/工具）：98 个；
- 业务服务模块：15 个 `amz-service-*`；
- 主代码注解行近似计数：`@ShopScoped` 255、`@RequireRole` 110、`@InternalServiceAccess` 12；
- 宽松文本命中约：`@ShopScoped` 276、`@RequireRole` 114、`@InternalServiceAccess` 18，包含注释或非注解文本，不能直接当运行时权限覆盖率。

### 1.5 必须保留的不确定性

- 本次没有真实 Amazon 凭据，不能证明任何 SP-API 操作能通过真实权限和字段校验。
- 本次没有重新启动全部服务，不能证明当前工作区全栈可启动。
- 本次没有成功复连 MySQL，不能把历史行数/EXPLAIN 当作当前结果。
- RabbitMQ 未在当前环境监听，MQ down/积压/DLQ 行为没有做本轮故障注入。
- 前端与后端 346 个端点的契约没有做全量自动比对。
- “代码里没找到某能力”只能说明静态证据缺失，不能反向证明运行时绝对不存在；反之，“代码里有实现”也不能证明生产正确。

## 2. 外部基准与 Amazon 官方约束

### 2.1 Amazon 官方事实

1. **连接与鉴权**：常规 SP-API 请求使用 LWA access token，并放在 `x-amz-access-token`；每个请求必须有合法 `user-agent`。受限数据操作必须使用 RDT，而不是普通 LWA token。  
   来源：[Connecting to the Selling Partner API](https://developer-docs.amazon.com/sp-api/docs/connecting-to-the-selling-partner-api)

2. **限流与退避**：官方使用 token bucket/usage plan；遇到 429 必须退避重试；应读取 `x-amzn-RateLimit-Limit`；不应硬编码定时器；应优先事件驱动，减少高频轮询。  
   来源：[Usage Plans and Rate Limits](https://developer-docs.amazon.com/sp-api/docs/usage-plans-and-rate-limits)

3. **通知优先**：生产系统应优先使用 Amazon 通知替代高频轮询，并正确设计订阅、消费、重试和去重。  
   来源：[Notifications API v1 Use Case Guide](https://developer-docs.amazon.com/sp-api/docs/notifications-api-v1-use-case-guide)

4. **授权与轮换**：public application 使用 OAuth/LWA，通常需要卖家授权和年度/角色续授权；private application 使用 refresh token；生产必须具备凭据轮换和失效处理。  
   来源：[Authorizing Selling Partner API Applications](https://developer-docs.amazon.com/sp-api/docs/authorizing-selling-partner-api-applications)

补充判断：仓库中仍保留 SigV4 兼容层。Amazon 自 2023-10-02 起不再要求 SP-API 使用 AWS SigV4，因此该层可以保留为兼容实现，但必须确认它不会掩盖 LWA/RDT、user-agent、region 和权限契约问题，也不能因为“签名代码存在”就推断真实联调已完成。

### 2.2 GitHub 同类项目快照

以下数字为 2026-09-28 前后 GitHub API 快照，星数和 issue 数会变化，不能作为质量证明：

| 项目 | 类型 | 快照 | 可借鉴点 |
|---|---|---|---|
| [amzn/selling-partner-api-models](https://github.com/amzn/selling-partner-api-models) | 官方模型/SDK | 约 914 stars、795 forks、270 open issues、Apache-2.0，2026-09-22 有更新 | 官方 OpenAPI/模型、版本演进、契约来源 |
| [amzn/selling-partner-api-samples](https://github.com/amzn/selling-partner-api-samples) | 官方样例 | 约 204 stars、89 forks、42 open issues、MIT-0，2026-09-13 有更新 | LWA、调用样例、应用授权流程 |
| [aws-samples/selling-partner-api-bootstrap](https://github.com/aws-samples/selling-partner-api-bootstrap) | AWS 引导样例 | 约 40 stars、11 forks、1 open issue、MIT-0，已归档，最后更新 2024-06-18 | 基础设施/部署引导，但已归档，不宜直接依赖 |
| [saleweaver/python-amazon-sp-api](https://github.com/saleweaver/python-amazon-sp-api) | 第三方客户端 | 约 680 stars、255 forks、0 open issues、MIT，2026-09-26 有更新 | 限流、重试、端点和异常封装 |
| [nplszfl/OmniTradeERP](https://github.com/nplszfl/OmniTradeERP) | 跨境电商 ERP | 约 80 stars、19 forks、14 open issues，license 未识别，2026-09-24 有更新 | 业务流程、模块划分和前端交互 |
| [admin627863/Amazon-SP-ERPNext](https://github.com/admin627863/Amazon-SP-ERPNext) | Amazon/ERPNext 方向 | 约 3 stars、1 fork、0 open issues、NOASSERTION，最后更新 2022-11-30 | 仅作旧实现参考，时效性弱 |

**外部基准的结论**：

- 官方 models 和 samples 应作为字段、端点、版本和鉴权契约的事实源，不应只依赖二手博客。
- 第三方客户端项目可以借鉴限流/重试/错误分类，但 Amazon 权限、DPP/PII、财务口径和真实生产责任仍必须由本项目自行验收。
- 同类 ERP 项目普遍在“业务流程广度”和“生产级一致性/安全”之间存在明显差距；本项目不能因为模块多就跳过生产门槛。

## 3. 当前架构与能力盘点

### 3.1 技术栈

- Java 17、Spring Boot 3.3.5；
- Spring Cloud 2023.0.3、Spring Cloud Alibaba 2023.0.1.2；
- MyBatis/MyBatis-Plus、MySQL 8、Flyway 10.20.0；
- Redis/Redisson、RabbitMQ、Nacos、Sentinel、Seata（条件启用）；
- Prometheus、Grafana、Alertmanager、ELK/Logstash、SkyWalking；
- LangChain4j、ONNX Runtime、AI Agent 工具；
- 前端 Vue/Vite 体系（依据仓库结构和测试日志）。

### 3.2 业务服务

| 服务 | 主要职责 | Review 关注点 |
|---|---|---|
| user | 用户、登录、JWT、角色 | refresh token、鉴权边界、审计 |
| product | 商品、Listing、翻译、搜索相关 | 字段权限、翻译外部调用、店铺隔离 |
| order | 订单、订单项、属性、利润输入 | 幂等、状态机、索引、对账 |
| search | 搜索与聚合 | 查询性能、租户过滤、ES/缓存一致性 |
| ai | Agent、知识库、Embedding、SSE | prompt 注入、数据泄露、成本、超时 |
| spapi | SP-API 网关、凭证、限流、Outbox/Inbox | E4/E5、RDT、通知、user-agent、权限 |
| ad | Amazon Ads 报表/活动 | 授权体系独立、报表幂等、限流 |
| procurement | 采购、供应商、三单匹配 | 审批、状态机、事务、租户 |
| customer | 客服、邮件、RMA/PII | PII 分类、脱敏、审计、保留删除 |
| logistics | 物流、轨迹、仓配、签收差异 | 轨迹幂等、外部物流 API、状态机 |
| ops | 运维/运营支撑 | 管理端权限、敏感操作 |
| report | 报表与利润聚合 | 数据新鲜度、聚合索引、对账 |
| finance | 结算、回款、费用、凭证 | 财务准确性、幂等、审计、事务 |
| multiplatform | 多平台 OAuth/webhook/订单 | token 生命周期、签名验证、租户边界 |
| message | 站内信/通知 | MQ ack、DLQ、重试、幂等 |
| gateway | 路由、JWT、CORS、限流 | header 伪造、文档开关、actuator、TLS |

### 3.3 已确认的正向设计

这些是整改时必须保留的能力，不要在生产化过程中回退：

- 网关从 JWT 重写身份，剥离外部伪造的 `userId`/`shopId`：`amz-gateway/src/main/java/com/amz/filter/MyGlobalFilter.java:112-140`。
- `/internal/**` fail-closed，普通端点只有显式 `@InternalServiceAccess` 才接受服务令牌。
- SP-API 默认 `prod`，缺凭证 fail-closed；`application-prod.yml` 有生产 profile 防护。
- `CryptoUtil` 使用 AES-256-GCM，生产密钥要求 32 字节 Base64。
- `DistributedJobLock` 默认 fail-closed；只有显式幂等任务才允许降级。
- SP-API Outbox 有幂等键、状态机、重放和限流变体。
- Notification Inbox 有 lease、retry、DLQ 和指标设计。
- 合成数据工具链具备确定性生成、校验、清理和真实 DDL 灌入验证。
- 部分 MQ 消费者已手动 ack、幂等、重试和 DLQ；不能把“MQ 整体不安全”一概而论。
## 4. P0：生产阻断项与上线门禁

> 说明：P0 表示“在完成并验收前不应对外宣称生产可用”。部分问题已由代码/配置静态确认，部分属于生产环境才暴露的风险；本报告不把静态推断包装成真实攻击复现。

### P0-01 发布基线不可复现、工作区污染严重

**证据**

- `git status --porcelain` 626 项；
- `git diff --stat`：307 files changed，24,065 insertions，12,773 deletions；
- 根目录混入大量日志、临时脚本、补丁脚本、运行产物和测试辅助文件；
- `_r83_spapi_final_out.log` 约 114 MB，另有 `full3.log`、`mvn-*.log`、`_r8*.log`、`.patch_*.py`、`ProbeUpdateWrapperTest.java` 等。

**风险**

- 无法从 HEAD 复现被审查的行为；
- 生产镜像、配置、迁移和前端产物缺少唯一来源；
- 误提交凭据、客户数据、调试开关或临时测试代码的概率显著升高；
- 回滚时无法判断应该回滚到哪个代码/迁移/镜像组合。

**最低整改**

1. 冻结当前工作区，清理/迁移构建产物和日志；
2. 将真实源码改动拆成小 PR，每个 PR 有测试和迁移说明；
3. 给发布产物建立 commit SHA + 镜像 digest + Flyway 版本 + 前端构建号；
4. 禁止在仓库根目录写运行日志和临时补丁；
5. 在 CI 中检查未跟踪文件、超大文件和敏感信息。

**验收**

从干净 clone 开始，一条命令可构建出与发布清单完全一致的产物；构建日志和产物 digest 可追溯。

### P0-02 参数校验基本缺失

**证据**

- 全仓没有实际 `spring-boot-starter-validation` 依赖；
- main 代码没有有效的 Bean Validation 注解使用；
- `GlobalExceptionHandler` 虽然写了 `MethodArgumentNotValidException` 处理，但如果没有 `@Valid/@Validated`，该分支基本不会被触发：`amz-common/src/main/java/com/amz/handle/GlobalExceptionHandler.java:45-55`。

**风险**

- null、空字符串、负数、超长字段、非法枚举、错误日期/金额进入 Service；
- 触发 NPE、类型转换异常、数据库截断/约束异常或 500；
- 非法数据可能部分落库，形成后续对账和财务差异；
- 接口文档/前端无法依赖稳定的参数错误契约。

**最低整改**

- 为 DTO/请求对象增加 `@NotNull/@NotBlank/@Size/@Min/@Max/@Positive/@Email` 等约束；
- Controller 参数加 `@Valid` 或类加 `@Validated`；
- 统一 `MethodArgumentNotValidException`、`ConstraintViolationException`、`BindException` 的字段级错误响应；
- 对金额、数量、日期、枚举、ID、分页大小和排序字段做白名单校验。

**验收**

对每个写接口建立非法参数测试矩阵；错误必须是 4xx，响应体包含稳定错误码和字段定位，不能靠 500 兜底。

### P0-03 HTTP 异常契约错误

**证据**

`amz-common/src/main/java/com/amz/handle/GlobalExceptionHandler.java:38-90`：

- 业务异常 `:38-42`；
- 参数校验 `:48-54`；
- 绑定失败 `:60-63`；
- JSON 解析失败 `:69-72`；
- 运行时异常 `:78-81`；
- 最终兜底 `:87-90`。

这些处理器全部返回 `Result`，没有 `@ResponseStatus`、`ResponseEntity` 或显式 `HttpStatus`。通常表现为 HTTP 200，错误只在 body 的 `code` 中。

**风险**

- 网关、负载均衡、APM、日志告警、客户端重试无法按标准 HTTP 语义判断失败；
- 第三方/前端可能把业务失败当成功；
- 5xx 率、错误率和 SLO 统计失真；
- 参数错误、业务冲突、内部错误无法区分。

**最低整改**

- 定义 HTTP 状态矩阵：400 参数/格式、401 未认证、403 无权限、404 不存在、409 冲突、422 业务校验、429 限流、500 内部错误；
- 业务异常保留稳定业务码，同时设置正确 HTTP 状态；
- 不向调用方泄露堆栈、SQL、内部类名和敏感参数；
- 对网关、Feign、前端、监控分别做契约测试。

**验收**

错误响应在 HTTP 状态、`code`、`message`、`traceId` 四个维度一致；所有外部调用方按状态码处理。

### P0-04 操作审计没有真正落库

**证据**

- main 代码中实际 `@OperLog` 为 0；
- `OperLogAspect` 只写 logger 文件：`amz-common/src/main/java/com/amz/aspect/OperLogAspect.java:90-112`；
- `amz_oper_log` 表存在：`amz-service/amz-service-user/src/main/resources/db/migration/V1__init.sql:63`；
- 未发现 Mapper/insert 的持久化闭环证据。

**风险**

- 无法回答“谁在什么时候改了哪家店铺的哪条数据”；
- 财务、采购、权限、凭证、导出等敏感操作缺少不可抵赖记录；
- 发生越权、误操作、数据删除或客户投诉时无法追责；
- 合规审计无法仅依赖分散的日志文件。

**最低整改**

- 为敏感写操作、登录、授权、凭证轮换、导出、审批、财务调整、批量任务建立审计注解；
- 审计事件异步持久化到 `amz_oper_log` 或独立审计库；
- 记录操作者、店铺、租户、请求 ID、资源 ID、变更前后摘要、结果、IP/User-Agent、时间；
- 审计写入失败要有告警，不能静默丢失；
- 对审计数据设置保留期和防篡改策略。

**验收**

任取一条敏感操作，可从业务记录反查到审计记录；审计记录不可由普通业务管理员修改或删除。

### P0-05 字段权限 fail-open

**证据**

`amz-common/src/main/java/com/amz/service/impl/FieldPermissionServiceImpl.java`：

- `:97-100`：加载权限失败时降级为“全部可见”；
- `:104-131`：未知角色/实体返回空隐藏字段集；
- `:135-139`：参数为空直接返回 `true`；
- `amz-common/src/main/java/com/amz/aspect/FieldPermissionAspect.java:78-81`：切面异常只记录日志并跳过过滤。

**风险**

- 权限服务/缓存/数据库故障时，敏感字段反而扩大暴露；
- 新角色、新实体、拼写错误、空上下文都可能绕过字段隐藏；
- 客户 PII、财务、成本、供应商等字段可能被非授权用户看到。

**最低整改**

- 字段权限默认 fail-closed；
- 未知角色/实体返回“不可见”或明确拒绝，而不是空隐藏集；
- 权限加载失败时拒绝敏感字段访问并告警；
- 对字段权限做单元、集成和跨角色矩阵测试；
- 前端掩码只能作为展示层，后端必须最终裁决。

**验收**

在权限源不可用、角色未知、实体未配置、参数为空四种场景下，敏感字段均不可见或请求被拒绝。

### P0-06 租户/店铺隔离不是统一强约束

**证据**

- `ShopIdGuardAspect` 只支持方法级 `@ShopScoped`；
- 只检查名为 `shopId` 的 `Long @PathVariable/@RequestParam`；
- 找不到 `shopId` 参数时直接放行：`amz-common/src/main/java/com/amz/aspect/ShopIdGuardAspect.java:64-66`；
- 不支持 `@RequestBody` 内 shopId；
- `UserContext` 在无 userId 且 shops 为空时 `isShopAllowed` 返回 true：`amz-common/src/main/java/com/amz/context/UserContext.java:81-117`；
- 静态扫描显示 59 个 Controller、约 346 个 mapping 中仍有大量端点没有 `@ShopScoped`、`@RequireRole` 或 `@InternalServiceAccess` 注解。

**必须准确表述的风险边界**

- 这不等同于“所有未标注端点都可越权”。网关全局 JWT 仍然生效，很多 Service 也会手工调用 `isShopAllowed` 或校验 owner shop。
- 真正的问题是：租户 guard 不统一、静态覆盖不足、`UserContext` 存在兼容放行语义，安全结论依赖每个调用点人工正确实现，缺少“默认拒绝”的体系保证。
- 高风险域包括 customer、finance、procurement、logistics、product、multiplatform、report、knowledge 等。

**最低整改**

- 建立统一租户上下文和资源归属模型；
- 默认拒绝，只有显式声明“公开/内部/全局”的端点才可绕过；
- 对 `@RequestBody`、路径资源 ID、批量 ID、导出、异步任务和内部 Feign 调用统一做租户校验；
- 将 `isShopAllowed` 兼容放行改为显式模式，禁止生产使用；
- 为每个 Controller 建立端点权限清单和跨店铺负向测试。

**验收**

跨店铺读取、写入、批量、导出、异步回调和内部服务调用全部有负向测试；任何漏标端点由测试或启动检查直接失败。

### P0-07 `/user/refresh` 静态高风险

**证据**

- 网关白名单包含 `/user/refresh`：`amz-gateway/src/main/java/com/amz/filter/MyGlobalFilter.java:30-38`；
- 下游白名单只包含 `/user/send`、`/user/verify`、`/actuator`：`amz-common/src/main/java/com/amz/interceptor/BaseAuthInterceptor.java:31-38`；
- `LoginController.java:57-74` 从 `token` header 读取 refresh token；
- refresh token subject 形如 `refresh:<userId>`：`amz-common/src/main/java/com/amz/util/JwtUtil.java:156-164`；
- `BaseAuthInterceptor` 的 `:100` 解析 token，`:102` 直接 `Integer.valueOf(userId)`。

**静态判断**

如果 `parseToken` 得到的 subject 是 `refresh:1`，而 `Integer.valueOf` 直接转换，可能触发 `NumberFormatException`，被 catch 后返回 401。也就是说，该端点可能无法完成正常续期，或者被下游错误处理掩盖。由于本次 user 服务未运行，未做真实 HTTP 复现，必须标为“静态确认高风险、未运行时验证”。

**最低整改**

- 明确 refresh token 的解析、校验、轮换和撤销流程；
- 不在通用拦截器里把 refresh subject 当普通 userId 解析；
- 使用专用 refresh 端点/过滤器，校验 token type、过期时间、设备/会话、黑名单/版本；
- 增加旧 token 重放、并发刷新、撤销后刷新、跨用户刷新等测试。

**验收**

真实 HTTP 测试覆盖刷新成功、过期、撤销、重放、并发和跨用户场景；日志不记录 token 原文。

### P0-08 OAuth token 明文入库且生命周期不完整

**证据**

- `amz-service/amz-service-multiplatform/src/main/resources/db/migration/V1__init.sql:148-161`：
  - `access_token VARCHAR(256)`；
  - `refresh_token VARCHAR(256)`；
  - `expires_at DATETIME`；
  - `idx_expires`；
- `OauthToken.java:18-19` 为明文字段；
- `MultiplatformServiceImpl.java:563-575` 使用 UUID 生成 token 并直接插库，access token 有效期 30 天；
- 未发现 OAuth token 的校验、刷新、撤销、过期清理和资源服务鉴权闭环；
- `amz_oauth_app.app_secret_encrypted` 演示值仍为 `base64encrypted_placeholder`。

**风险**

- 数据库/备份/只读副本泄露会直接暴露长期 token；
- token 无轮换/撤销，账号被移除或授权变更后仍可能有效；
- 与 Amazon OAuth/LWA 的安全模型不一致；
- 审计和合规无法证明 token 生命周期受控。

**最低整改**

- token 至少加密存储，优先使用 KMS/Vault/信封加密；
- 明确 access/refresh token 的 TTL、轮换、撤销、失效传播；
- 每次使用校验 shop、client、scope、过期和撤销状态；
- 增加过期清理任务和密钥轮换；
- 日志、异常、监控、APM 中禁止输出 token 原文；
- 与 SP-API LWA token 缓存和 RDT 生命周期分开建模。

**验收**

数据库中不可读明文 token；撤销后旧 token 立即失效；密钥轮换有演练记录；日志扫描无 token 泄漏。

### P0-09 多平台 webhook 缺少签名/来源验证证据

**证据**

- `MultiplatformController.java`：
  - `/account/{id}/test` L51 无 `@ShopScoped`，Service L128-133 手工校验；
  - `/webhook/{platform}/{eventType}` L132 无 `@ShopScoped`、无签名验证证据；
  - `/oauth/token` L167 无 `@ShopScoped`，Service L529-548 校验 app secret 和 owner shop；
  - `/order/{orderId}/ship` L201 无 `@ShopScoped`，Service L699-704 手工店铺校验。

**准确表述**

这些端点仍可能受网关全局 JWT 保护，不能简单写成“未鉴权”。真正问题是租户 guard 不统一；如果 webhook 需要公网无 JWT 接入，则必须有平台签名、时间戳、重放防护和来源校验，目前没有看到完整证据。

**最低整改**

- 明确 webhook 是公网、内网还是网关白名单；
- 验证平台签名/HMAC/证书、时间戳、事件 ID 和重放窗口；
- webhook 入站先持久化原始事件，再异步处理；
- 按事件 ID 幂等，失败进 DLQ；
- 对 platform/eventType 做白名单和大小限制。

**验收**

伪造签名、过期时间戳、重复事件、乱序事件、超大 payload 和未知平台全部被拒绝或安全落入 DLQ。

### P0-10 Redis 是启动期强依赖，且无 Sentinel/Cluster

**证据**

- 历史日志 `round33-spapi-startup-failed-redis-down-p0-66.log` 记录 Redis 不可用时 spapi 启动级联失败；
- README 第 251 行承认 Redis 单点，无 Sentinel/Cluster；
- 幂等、分布式锁、Sentinel 规则拉取、LWA token 缓存等均依赖 Redis。

**风险**

- Redis 故障可能导致服务无法启动或关键业务降级；
- 单实例故障影响跨服务幂等、锁和限流；
- 没有明确 Redis 不可用时的分级降级策略；
- 生产缺少持久化、主从、哨兵/集群、备份和恢复演练。

**最低整改**

- 区分启动必需依赖与运行时可降级依赖；
- 对缓存、锁、幂等、Sentinel 规则分别定义降级矩阵；
- 生产部署 Redis Sentinel/Cluster 或托管高可用实例；
- 配置超时、连接池、隔离、重连和故障告警；
- 做 Redis 故障注入和恢复演练。

**验收**

Redis 单节点故障不会导致全系统不可用；关键写路径有明确 fail-closed/fail-safe 策略；恢复后幂等和锁状态可自愈。

### P0-11 Prometheus 与告警链不可用

**证据**

- `prometheus/prometheus.yml` 抓取 16 个 target；
- 网关 `amz-gateway/src/main/resources/application.yml:151-155` 仅暴露 `health,info`，未暴露 `prometheus`；
- user target 写 `amz-service-user:8086`，实际端口 `8080`；
- product target 写 `amz-service-product:8087`，实际端口 `8095`；
- `prometheus/rules/amz-alerts.yml:46` 使用 `rabbitmq_queue_messages_ready`；
- `prometheus/rules/amz-alerts.yml:56` 使用 `node_filesystem_*`；
- Compose/K8s 未见 RabbitMQ exporter 或 node exporter scrape 配置；
- `alertmanager/alertmanager.yml:17-18` 的 `default`、`critical` receiver 均为空。

**结论**

即使部分服务能暴露指标，当前抓取目标和告警路由也不能视为可用生产告警链。该问题会直接影响故障发现、SLO 计算和值班。

**最低整改**

- 修正所有服务端口/服务名；
- 暴露 `/actuator/prometheus`，但通过内网/鉴权限制；
- 部署 RabbitMQ exporter、node exporter、MySQL/Redis exporter（如告警需要）；
- 配置 Alertmanager 邮件/Webhook/IM receiver，并做测试告警；
- 定义告警分级、抑制、静默、值班和升级策略；
- 对“监控系统本身不可用”建立独立探活。

**验收**

Prometheus 所有 target `UP`；注入故障后 5 分钟内产生可送达告警；值班人员能收到并确认。

### P0-12 部署安全未达生产标准

**证据**

- `k8s/ingress.yaml:23`：`ssl-redirect: "false"`；
- 未见完整 TLS 证书/证书管理配置；
- `/actuator` 暴露到网关，`k8s/ingress.yaml:39-45`；
- `k8s/secret.yaml:3` 明确 `CHANGE_ME_*` 是强制替换占位；
- `amz-frontend/.env.production`：
  - `VITE_API_BASE_URL=https://api.yourdomain.com`；
  - `VITE_WS_URL=wss://api.yourdomain.com/ws/socket`；
- SP-API 服务自身 `springdoc.swagger-ui.enabled: true`：`amz-service/amz-service-spapi/src/main/resources/application.yml:224-229`。

**风险**

- 明文 HTTP 导致 token、cookie、业务数据被窃听/篡改；
- Actuator 可能泄露运行状态、配置或健康细节；
- Secret 占位值若未替换，服务可能启动失败或使用错误凭据；
- 前端构建指向占位域名，生产环境不可用或误连测试环境；
- Swagger 直连 ClusterIP/Service 时泄露 API 结构。

**最低整改**

- Ingress 强制 HTTPS/redirect，配置证书自动轮换；
- Actuator 仅内网/管理面暴露，敏感端点加认证；
- Secret 使用 External Secrets/Vault/KMS/SealedSecret；
- 前端生产域名、CSP、WS 地址纳入发布参数校验；
- 生产 profile 默认关闭 Swagger/OpenAPI，仅受控环境开启；
- 做镜像扫描、SBOM、签名和部署准入。

**验收**

生产 URL 全链路 TLS；Actuator 和 Swagger 不可从公网访问；Secret 无占位值；发布清单能证明前端指向正确域名。

### P0-13 CORS 默认保留本地开发 origin

**证据**

`amz-gateway/src/main/java/com/amz/config/CorsConfig.java`：

- `:21` 默认允许 `http://localhost:*`、`https://localhost:*`、`http://127.0.0.1:*`；
- `:32` `setAllowCredentials(true)`；
- `:34` `addAllowedHeader("*")`；
- `:36` `addAllowedMethod("*")`。

**风险**

- 如果生产未显式覆盖 `amz.cors.allowed-origins`，生产会保留本地开发 origin；
- 虽然公网攻击者通常不能控制用户 localhost，但这仍是发布配置缺陷；
- 与 Cookie/Authorization 凭证、CSRF 防护、前端域名白名单必须一起验收；
- 通配 header/method 会扩大预检和攻击面。

**最低整改**

- 生产 profile 强制显式 origin 白名单，禁止默认本地 origin；
- 只允许必要 header/method；
- 明确是否使用 Cookie；若使用，必须加 CSRF 防护和 SameSite；
- 对 CORS 配置做启动校验和集成测试。

**验收**

生产响应头只包含真实前端域名；未授权 origin 的预检和凭证请求被拒绝。

### P0-14 CI 门禁不足

**证据**

`.github/workflows/ci.yml`：

- 仅 main/master push/PR 触发；当前 `codex/api-ready-connectors` 分支不触发；
- checkstyle `continue-on-error: true`（第 13 行）；
- docker job 仅 master（第 214 行附近）；
- 缺少覆盖率、SpotBugs、依赖漏洞扫描、镜像签名、SBOM、部署验收门禁。

**风险**

- 特性分支可以在无 CI 的情况下长期漂移；
- 静态检查和代码质量门禁不会阻断合并；
- 生产镜像缺少供应链可追溯性；
- 单元测试通过不等于迁移、启动、契约、性能和安全性通过。

**最低整改**

- 所有目标分支都触发 CI；
- checkstyle、编译、单测、迁移、前端类型检查/构建、合成数据校验作为硬门禁；
- 增加依赖漏洞、Secret 扫描、SAST、镜像扫描、SBOM、签名；
- 增加 API 契约、租户隔离、迁移升级/回滚、启动冒烟；
- 发布只允许来自受保护分支和受保护 tag。

**验收**

任何一项硬门禁失败都不能合并/发布；发布产物能追溯到 commit、CI run 和签名。

### P0-15 真实 Amazon 接入尚未达到 E4/E5

**证据**

- 仓库自身定位为 API-Ready，未完成真实联调；
- 最高证据是进程内桩 + 官方 OpenAPI 快照；
- 历史测试中跳过项为需要真实凭证的 `SpApiIntegrationTest`；
- 没有真实 marketplace、权限、RDT、SQS/SNS、字段契约和生产对账证据。

**风险**

- 代码可以编译、测试通过，但真实 API 可能因权限、版本、marketplace、限流、字段缺失或签名细节失败；
- 订单、库存、财务、广告、通知等关键链路的业务正确性未证明；
- 无法据此承诺“拿到 API 就能直接生产使用”。

**最低整改**

- 完成 SP-API 应用注册/审核和卖家授权；
- 配置 LWA、RDT、角色、region、marketplace、SQS/SNS；
- 在沙箱/测试环境完成订单、库存、报表、通知、RDT 的 E4 联调；
- 选择小范围真实店铺做 E5 试点，进行订单/库存/财务/通知对账；
- 记录每个 operation 的限流、错误、重试、字段差异和验收结果；
- 只有在 E4/E5 通过后才更新对外措辞。

**验收**

E4/E5 证据可复现、可审计；关键业务对账无未解释差异；生产发布/回滚/值班/凭据轮换均已演练。
## 5. P1：规模化前必须收敛的高风险项

### P1-01 MQ 消费链质量不一致

**已确认的正向项**

- `FinanceVoucherConsumer`：手动 ack；非法字段/异常 `basicNack(requeue=false)` 进 DLQ；有测试。
- `ProfitMQConsumer`：手动 ack；幂等；异常进 DLQ。
- `OrderConsumer`：有幂等键、重试上限、DLQ 处理。

**已确认的薄弱项**

- `amz-service-message/src/main/java/com/amz/mq/consumer/MessageNoticeConsumer.java:19-22` 的 `@RabbitListener` 仅打印 `userId`；
- 无手动 ack、无 DLQ、无 retry、无幂等；
- `amz-service-message/src/main/java/com/amz/config/MqConfig.java:25-38` 仅 durable queue/binding，无 DLX；
- `application.yml` 未看到 listener ack/retry 配置。

**风险**

- 消息处理失败可能丢失或无限重投；
- 重复消费造成重复通知/重复业务状态；
- poison message 可能阻塞队列；
- 队列积压时没有统一 backpressure/DLQ 运维入口。

**建议**

- 统一 RabbitMQ 消息契约、ack 模式、重试上限、退避、DLQ、幂等键和监控；
- 对每个消费者写故障注入测试；
- 对通知类消费者采用 outbox/inbox 或等价幂等表；
- 配置队列深度、消费延迟、DLQ 增长告警。

### P1-02 事务回滚边界不完整

**证据**

- `MultiplatformServiceImpl` 多处仅使用 `@Transactional`，未显式 `rollbackFor`；
- Spring 默认只对 `RuntimeException/Error` 回滚；如果业务方法抛出 checked exception，可能提交部分状态。

**风险**

- 外部平台调用成功、本地状态部分提交，或反过来；
- token、订单、账户、消息等多表写入不一致；
- 对账和重试时产生重复或幽灵记录。

**建议**

- 按方法梳理可能抛出的 checked exception；
- 明确 `@Transactional(rollbackFor = Exception.class)` 或更精确的回滚规则；
- 外部调用与本地事务分离，使用 outbox/inbox、补偿和对账；
- 对多平台账户、订单、token、webhook 入站建立一致性测试。

### P1-03 调度器与分布式锁覆盖不均

**证据**

- `@Scheduled` 共 23 处；
- 只有 spapi 配置了 8 线程调度池：`amz-service-spapi/src/main/java/com/amz/config/SchedulingConfig.java:21-29`；
- 其他服务可能共享默认单线程 scheduler，长任务会互相饿死；
- `DistributedJobLock` 默认 fail-closed，是正向项：`amz-common/src/main/java/com/amz/lock/DistributedJobLock.java:27-30,144-157,177-198`；
- 未直接注入 `DistributedJobLock` 的 spapi scheduler 包括 `SpApiOutboxReplayScheduler`、`NotificationInboxWorkerScheduler`、`NotificationIngestionScheduler`、`NotificationSubscriptionSyncScheduler`；
- `SpApiOutboxReplayScheduler.java:20` 明确依赖数据库原子领取，不能仅因没有 Redis 锁就判 P0；但 Notification/Inbox 是否完全依靠数据库 claim/lease 仍需逐项确认。

**风险**

- 多实例重复执行调度任务；
- 单线程 scheduler 被长任务阻塞，导致其他任务延迟；
- Redis 故障时不同任务采用不同降级策略，行为不可预测。

**建议**

- 为每个调度任务标注幂等性、锁策略、超时、最大执行时间、重试和告警；
- 统一分布式锁/数据库 claim/lease 的使用规则；
- 配置独立线程池和任务隔离；
- 做多实例并发调度测试和 Redis/DB 故障注入。

### P1-04 读写分离名义存在、实际未使用

**证据**

- 只有 order、spapi 两个模块引入 dynamic-datasource 和 slave 配置；
- 全仓实际 `@DS` 注解为 0；
- 仅在注释中提到 `@DS("slave")`：`amz-service-order/.../DataSourceConfig.java:19`、`amz-service-spapi/.../DataSourceConfig.java:19`。

**结论**

README 中的“读写分离”目前没有实际读路由收益；slave 配置可能只是备用连接。不能把“配置了从库”描述成“读写分离已生效”。

**建议**

- 要么真正为读多写少的查询加 `@DS("slave")` 并处理主从延迟；
- 要么删除/弱化文档中的读写分离表述；
- 明确哪些查询允许读从库，哪些必须读主库；
- 做主从延迟、切换和一致性测试。

### P1-05 出站 HTTP 客户端未完全收口

**正向项**

- `ResilientHttpClient` 具备超时、指数退避、熔断、Micrometer 指标，是统一治理的良好基础。

**仍存在的独立客户端**

- `amz-common/.../util/HttpUtil.java:17`；
- `amz-common/.../service/impl/EmbeddingServiceImpl.java:40`；
- `amz-service-ai/.../config/AiHttpClientConfig.java:31`；
- `amz-service-ai/.../knowledge/KnowledgeEsClient.java:42`；
- `amz-service-ai/.../agent/selection/SelectionAnalysisServiceImpl.java:38`；
- `amz-service-ai/.../agent/review/ReviewAnalysisServiceImpl.java:39`；
- `amz-service-ai/.../service/impl/AiServiceImpl.java:35`；
- `amz-service-product/.../service/TranslationService.java:79`；
- `amz-service-product/.../client/impl/KeepaRealClient.java:51`；
- `amz-service-spapi/.../config/HttpClientConfig.java:24`；
- `amz-service-spapi/.../auth/LwaTokenManager.java:409`；
- `amz-service-ad/.../client/AdvertisingApiRealClient.java:714`。

**风险**

- 不同客户端超时、重试、熔断、连接池、TLS、代理、指标和日志脱敏不一致；
- 外部服务慢/挂时可能耗尽线程或连接；
- 无法统一治理第三方 API 成本、配额和故障。

**建议**

- 统一通过 `ResilientHttpClient` 或明确的 client factory；
- 每个外部依赖定义 timeout、retry、circuit breaker、bulkhead、metrics 和日志脱敏；
- 禁止在业务代码中直接 new HTTP client；
- 对外部调用建立依赖清单和故障演练。

### P1-06 数据库索引和查询计划存在潜在缺口

**历史实测（上一轮，本轮未复验）**

- `amz_finance.amz_payment_collection`：9,755 行，DATA 1.52 MB，INDEX 2.23 MB；
- `amz_logistics.amz_shipment`：4,940 行，DATA 1.52 MB，INDEX 1.25 MB；
- `amz_logistics.amz_tracking_event`：29,784 行，DATA 5.52 MB，INDEX 8.06 MB；
- `amz_order.amz_order_attribute`：10,070 行；
- `amz_report.amz_profit_detail`：5,063 行；
- `amz_multiplatform.amz_oauth_token`：18 行。

**历史 EXPLAIN 观察**

- `amz_order_attribute WHERE order_id`：`type=ALL`，无索引，10,070 行全表扫描；当前源码只看到写入，未看到读取，因此应描述为潜在索引缺口；
- `amz_tracking_event` 按 `shipment_id + DELIVERED`：命中 `idx_shipment_status_time`，`Using index`；
- `amz_shipment` 按 `shop_id + master_tracking_no`：只命中 `idx_shop`，mock 选择性不足，需生产数据复测；
- `amz_payment_collection` 按 `shop_id + status`：观察到 `uk_shop_amazon_order`，有 `Using filesort`；
- `amz_settlement_detail`：命中 `idx_shop`，未走组合索引；
- `amz_profit_detail`：命中 `idx_shop_date`，反向扫描正常；
- `amz_profit_report` 月度 SKU 聚合：命中 `uk_shop_order_sku`，`Using temporary`。

**源码核对**

- `amz_order_attribute`：`amz-service/amz-service-order/src/main/resources/db/migration/V1__init.sql:8-14` 只有 `PRIMARY KEY(id)`，无 `order_id` 索引；
- `amz_profit_report`：同文件 `:94-113` 唯一键 `(shop_id, amazon_order_id, sku)`，`idx_date(stat_date)`，缺 `(shop_id, stat_date)` 或 `(shop_id, sku, stat_date)`；
- `amz_payment_collection` 当前 V3 唯一键为 `uk_shop_order (shop_id, order_id)`，`V7__order_number_column_rename.sql:25` 再重命名为 `uk_shop_amazon_order`。

**建议**

- 用生产级数据量和真实分布重跑 EXPLAIN ANALYZE；
- 为高频过滤/排序/聚合建立组合索引；
- 避免为低选择性字段单独建索引；
- 对报表查询做物化/汇总表或预聚合；
- 统一分页，禁止深分页全表扫描；
- 监控慢查询、锁等待、临时表和磁盘排序。

### P1-07 Swagger/OpenAPI 生产暴露

**证据**

- `amz-service-spapi/src/main/resources/application.yml:224-229` 默认 `springdoc.swagger-ui.enabled: true`；
- 网关文档默认关闭，但服务本身如果直连 ClusterIP、端口或 Service 暴露，API 结构会泄露。

**建议**

- 生产 profile 默认关闭 API docs 和 Swagger UI；
- 仅 local/受控环境开启；
- 如果必须开启，放在管理面并加认证；
- 把文档开关纳入启动配置校验。

### P1-08 README 与实现存在漂移

**已确认的漂移**

- README 第 253 行说 Swagger 默认放行，但 `MyGlobalFilter.java:52-54` 实际 `docsEnabled` 默认 false；
- README 第 294 行说 Compose 全域缺 `REDIS_HOST`、仅 spapi 有 `MYSQL_HOST`，当前 Compose 已为多数服务显式配置；
- README 提到“读写分离”，但全仓实际 `@DS` 为 0；
- README 的“全局异常处理器”没有说明 HTTP 200 语义问题；
- README 的“MQ 死信队列”只对部分队列成立，不能覆盖 `MessageNoticeConsumer`；
- README 的“TLS 可选”不是生产安全状态。

**风险**

- 运维/开发按文档操作会得到错误预期；
- 生产开关、端口、依赖关系和实际行为不一致；
- 审计报告和安全验收会被错误信息误导。

**建议**

- 把 README 拆成“当前事实”和“未来目标”；
- 所有配置/开关以代码和启动校验为事实源；
- 增加文档漂移测试（例如检查端口、profile、feature flag）；
- 每个生产化 PR 同步更新 runbook 和验收清单。

### P1-09 前端生产降级数据可能掩盖后端故障

**证据**

README 明确说明前端各页在后端不可达时降级到内置样例数据（仅演示），生产建议关闭降级或显示明确不可用态。

**风险**

- 生产用户可能把样例数据当成真实经营数据；
- 后端故障被前端静默掩盖，监控无法发现；
- 财务、库存、订单决策可能基于错误数据。

**建议**

- 生产构建禁用 mock fallback；
- 后端不可达时展示明确错误/不可用状态；
- 所有演示数据加醒目标识；
- 在 E2E 中验证生产模式不出现 mock 数据。

### P1-10 健康探针与启动依赖没有全量验证

**证据**

- 配置中有 Actuator health/liveness/readiness；
- 但本次没有启动全部服务，未验证探针真实 HTTP 返回；
- Redis 故障历史日志已证明启动期依赖问题；
- 数据库连接、RabbitMQ、Nacos、Elasticsearch、MongoDB 等依赖的启动行为未做全矩阵验证。

**建议**

- 为每个服务定义启动依赖和就绪条件；
- readiness 不因非关键依赖永久失败，liveness 不应误杀正在恢复的实例；
- 做依赖逐个不可用的启动/恢复测试；
- 在 K8s 中配置 startupProbe、readinessProbe、livenessProbe 的合理阈值。

### P1-11 前后端 API 契约未全量核对

**证据**

- Controller mapping 约 346 个；
- 前端 API 层有大量模块；
- 本次没有完成前端 `src/api/*` 与后端端点、字段、错误码、分页和时间的全量比对。

**风险**

- 404/字段错位/枚举不一致/空数组 vs null/日期格式等在生产才暴露；
- 前端 mock 与后端真实契约不一致；
- 后端改字段没有前端门禁。

**建议**

- 用 OpenAPI/类型生成或契约测试固定接口；
- 对 finance/logistics/ad/warehouse 等重点域先做端到端契约；
- 统一分页、时间、金额、枚举、错误码和空值语义；
- CI 中检测 breaking change。

### P1-12 业务状态机与幂等没有全量统一

**正向项**

- 部分物流/调拨/签收流程已有状态流转白名单和幂等设计；
- 订单、财务、利润、Outbox/Inbox 有部分幂等机制。

**风险**

- 跨服务调用、外部回调、定时补偿和用户重试可能产生重复状态；
- 订单、库存、财务、广告、通知的幂等键和状态机没有统一标准；
- 外部平台返回超时但实际成功时，缺少可靠对账。

**建议**

- 为每个核心业务对象定义状态机、允许迁移、幂等键和补偿策略；
- 外部写操作优先“本地意图 + outbox + 对账”；
- 统一“重复请求返回原结果”的语义；
- 建立状态机测试和跨域事件测试。

### P1-13 数据库权限与视图安全

**证据**

- 发现 `v_profit_summary_by_sku` 为 `SQL SECURITY DEFINER`，定义者 `amz@%`；
- 需要关注跨库访问和最小权限；
- 运行时 schema 与当前源码仍需复验。

**风险**

- DEFINER 视图可能放大数据库权限；
- 迁移账号、应用账号、只读账号权限边界不清；
- 备份、报表、运维工具可能读取超出业务需要的数据。

**建议**

- 使用最小权限账号，按库/表/操作拆分；
- 审计 DEFINER 视图/存储过程；
- 禁止应用使用 root/DDL 账号；
- 对报表和备份账号单独授权和审计。

### P1-14 连接器与凭证状态显示可能误导运营

**证据**

- 仓库已有连接器状态中心和自检接口；
- README 明确说“有凭证”或模拟自检成功不应显示“已接通”；
- 但真实状态、证据等级、最近自检结果和人工确认之间仍需要强约束。

**风险**

- 运营误以为已联调/已生产；
- 自检把 `SKIP` 显示为 `PASS` 或把“配置存在”显示为“接口可用”；
- 凭据过期、权限缺失、marketplace 不支持被误判为正常。

**建议**

- 状态机区分 `NOT_CONFIGURED / CONFIGURED / SELF_TEST_PASSED / SANDBOX_VERIFIED / PRODUCTION_VERIFIED`；
- `SKIP` 绝不显示为 `PASS`；
- 展示证据等级、测试时间、测试环境、操作清单和失败原因；
- 只有 E4/E5 证据达标才显示“已接通/生产可用”。
## 6. 业务功能模块升级建议

以下建议按“生产 ERP 真正闭环”而不是“页面有入口”评估。当前项目功能面已经较宽，但闭环质量、异常路径、财务口径和运维能力需要补强。

### 6.1 订单域

**目标**

- 支持多 marketplace、多币种、多时区、取消/退款/退货/换货/补发；
- 订单、订单项、金额、税费、运费、促销、地址、买家信息按 Amazon 字段契约建模；
- 支持增量同步、历史回补、通知驱动更新和对账；
- 明确订单状态机，避免重复发货、重复退款、状态回退。

**优先升级**

1. 建立订单主表 + 订单项 + 金额/税/费用明细 + 地址快照 + 事件日志；
2. 以 `(shopId, amazonOrderId)` 为幂等键，所有写路径使用 upsert/CAS；
3. 引入订单事件流（下单、付款、发货、取消、退款、A-to-Z、索赔）；
4. 对 Amazon 通知与轮询建立对账；
5. 为 PII 地址/买家信息做字段级权限、加密、脱敏和保留策略；
6. 建立重复订单、延迟通知、乱序通知、部分退款、跨币种测试。

### 6.2 库存与 FBA/WMS 域

**目标**

- 区分可售、预留、在途、待检、不良、锁定、FBA 在仓、海外仓、国内仓；
- 支持批次/序列号/有效期/成本层（FIFO）；
- 支持入库、出库、调拨、盘点、报损、退货、补货；
- 防止超卖、重复扣减、负库存和跨店库存串用。

**优先升级**

1. 建立库存台账和库存流水，不只维护余额；
2. 所有库存变动必须有业务单据、幂等键和审计记录；
3. 统一库存可用量计算和并发扣减策略；
4. 对 FBA 库存、在途库存、仓库库存分别建模；
5. 建立库存对账：ERP 台账 vs SP-API vs 仓库/WMS；
6. 增加负库存、重复回调、乱序事件、批次跨店、盘点差异测试。

### 6.3 商品、Listing 与搜索域

**目标**

- 商品主数据、SKU/ASIN、父子变体、marketplace 属性、图片、类目、合规字段；
- 多语言翻译、价格/促销、Listing 复制、批量更新和发布结果追踪；
- 搜索索引与数据库一致性、可重建、可回放。

**优先升级**

1. 建立商品主数据与 marketplace Listing 的分层模型；
2. 对批量更新使用 Feed/任务/结果回执和幂等；
3. 翻译、Keepa、AI 生成内容等外部调用统一超时/重试/成本控制；
4. 对价格、库存、Listing 状态建立变更历史和审批；
5. 搜索索引增加版本、重放和租户过滤；
6. 对类目属性做官方 schema 校验，避免“提交成功但平台拒绝”。

### 6.4 采购、供应商与三单匹配

**目标**

- 采购申请、采购订单、收货、质检、入库、供应商发票、付款、退货；
- 三单匹配：PO、收货单、发票；
- 审批、权限、预算、账期和差异处理。

**优先升级**

1. 建立采购单状态机、审批流和变更历史；
2. 对收货和发票做数量/金额容差、差异挂账和人工处理；
3. 统一供应商、SKU、币种、税率、含税/未税口径；
4. 采购成本回写到库存成本层和利润核算；
5. 增加超收、少收、错货、重复发票、跨店采购测试；
6. 对采购/付款/供应商银行信息做严格字段权限和审计。

### 6.5 物流、仓配与轨迹

**目标**

- 头程、尾程、海外仓、FBA 货件、调拨、签收、异常、运费和轨迹；
- 物流 API 可开关、可降级、可回放；
- 轨迹按 `(shipmentId, trackingNo, eventTime, status)` 幂等。

**优先升级**

1. 统一货件、运单、轨迹、费用和签收差异模型；
2. 外部轨迹 API 与手工导入共用落库核心；
3. 物流状态机、异常状态和超时补偿；
4. 运费/燃油/附加费/币种分摊到订单/商品成本；
5. 对重复轨迹、乱序轨迹、承运商改名、单号复用做测试；
6. 对 17TRACK 等第三方 API 做限流、缓存、成本和合规评估。

### 6.6 客服、邮件、RMA 与 PII

**目标**

- 客服工单、邮件、站内信、退货/退款/换货/补发；
- SLA、升级、模板、附件、多语言；
- PII 最小化、脱敏、访问审计、保留和删除。

**优先升级**

1. 建立工单状态机、SLA、优先级、负责人和审计；
2. 邮件/消息入站出站统一幂等、重试、退信和附件安全；
3. RMA 与订单、库存、财务联动；
4. PII 字段分类、加密、掩码、导出审计和删除流程；
5. 禁止把客户 PII 发送给未经批准的 AI/第三方服务；
6. 建立越权读取、批量导出、注入、附件病毒和模板滥用测试。

### 6.7 财务、结算、回款与利润

**目标**

- 订单收入、平台费、广告费、退款、仓储费、运费、税费、采购成本、头程成本、汇率和利润；
- 结算/回款/索赔/费用差异/凭证/总账；
- 可追溯到原始事件和单据，可对账、可审计、可重算。

**优先升级**

1. 建立复式/事件型财务账本，而不是只存聚合报表；
2. 明确收入、成本、费用、税费、退款、汇兑损益的确认时点和币种；
3. 结算、回款、索赔、费用差异建立对账状态机；
4. 所有财务写入有幂等键、审计、审批和不可变流水；
5. 利润报表必须能回溯到订单/商品/广告/采购/物流明细；
6. 对跨月、跨币种、部分退款、促销、负利润、费用重分类做测试；
7. 与金蝶等外部财务系统的字段、币种、税率、凭证状态必须真实联调。

### 6.8 广告域

**目标**

- Amazon Ads 授权独立于 SP-API；
- 活动、广告组、关键词、商品、预算、竞价、报表、归因和成本；
- 报表同步可回补、可幂等、可对账。

**优先升级**

1. 明确 Ads profile/账号/店铺映射和权限；
2. 报表按日期/类型/指标建立幂等键和版本；
3. 元数据同步失败不能伪装成整店失败，但必须有告警和重试；
4. 广告成本与订单/利润关联，处理归因延迟和跨期；
5. 对 API 限流、报表延迟、字段变更、重复报表做联调；
6. 建立广告预算/竞价变更审批和审计。

### 6.9 AI Agent 与知识库

**目标**

- Agent 工具可审计、可限权、可限额、可回放；
- 知识库与业务数据隔离，检索结果受租户权限约束；
- Prompt/输出可追踪，敏感信息不外泄。

**优先升级**

1. 每个 Agent 工具明确所需角色、店铺、数据范围和写操作审批；
2. 对写操作增加确认、幂等、审计和回滚/补偿；
3. 对外部 LLM/Embedding 做超时、重试、配额、成本和日志脱敏；
4. 防止 prompt injection、越权检索、数据外泄和工具链误用；
5. 保存模型、prompt 版本、输入摘要、工具调用和结果，支持回放；
6. 建立 AI 输出免责声明和人工复核边界，尤其财务/库存/合规场景。

### 6.10 报表与搜索

**目标**

- 报表可复算、可对账、可解释；
- 数据新鲜度和延迟可见；
- 大查询不拖垮在线事务库。

**优先升级**

1. 区分 OLTP、报表汇总和搜索索引；
2. 建立 ETL/ELT 或 CDC 链路，带水位、重跑和回填；
3. 报表显示数据截止时间、来源、口径和异常标记；
4. 对利润、库存、订单、广告、物流建立指标字典；
5. 大报表走预聚合/物化视图/列式存储，避免在线库临时表排序；
6. 对报表权限和导出做租户与字段级校验。

## 7. 性能与容量优化

### 7.1 数据库

- 以生产数据分布重跑 EXPLAIN ANALYZE，不只依赖 1 万行 mock；
- 为订单、轨迹、结算、利润、广告、通知建立组合索引；
- 对大表做分区/归档/冷热分离；
- 避免深分页、全表 count、N+1 查询和无界导出；
- 读写分离要么真正实现，要么从文档移除；
- 明确主从延迟对读一致性的影响；
- 建立慢查询、锁等待、连接池、临时表、磁盘排序监控。

### 7.2 缓存

- 区分本地缓存、Redis 缓存、分布式锁、幂等键和限流计数；
- 每个缓存定义 TTL、失效、击穿、雪崩、穿透策略；
- 租户/店铺维度必须进入缓存 key；
- 对 token、PII、财务数据设置更严格的 TTL 和加密；
- Redis 故障要有降级矩阵和恢复策略。

### 7.3 异步与消息

- 统一 outbox/inbox、ack、retry、DLQ、幂等和监控；
- 队列积压要有 backpressure，不能无限打满内存；
- 定时任务与事件驱动结合，减少 SP-API 轮询；
- 对大批量同步做分片、限速、断点续传和进度展示；
- 建立消息延迟、失败率、DLQ 深度、重复率和消费耗时指标。

### 7.4 SP-API 限流

- 以官方 usage plan 为事实源，定期对账 operation 和限流值；
- 读取 `x-amzn-RateLimit-Limit` 并动态调整；
- 429 使用指数退避 + jitter，设置最大重试和最大延迟；
- 按 shop/operation/region/marketplace 隔离令牌桶；
- 优先通知/事件驱动，减少高频轮询；
- 对每个 operation 记录成功率、429、5xx、P95/P99、重试和熔断。

### 7.5 前端性能

- 生产构建关闭 mock fallback；
- 路由懒加载、代码分割、图表按需加载；
- 大列表虚拟滚动、分页/游标和导出任务化；
- 统一请求取消、重试、超时、错误态和 skeleton；
- 监控首屏、接口耗时、JS 错误和慢页面；
- 对财务/库存/订单等关键页面做真实数据量性能测试。

### 7.6 容量模型

在生产数据前无法给出可靠 QPS/存储数字。建议先确定：

- 店铺数、SKU 数、订单/日、订单项/订单、广告报表量；
- 通知事件峰值、历史回补窗口、报表保留期；
- 同时在线用户、导出/批量操作峰值；
- 每个 marketplace/region 的 API 配额；
- RPO/RTO、可用性和恢复时间目标。

在这些参数确定前，任何“支持多少店铺/多少订单”的结论都不可信。

## 8. 安全、合规与数据治理

### 8.1 身份与授权

- 人机身份、服务身份、外部平台身份分层；
- JWT access/refresh 分离，支持撤销、轮换、设备/会话管理；
- 服务间使用 mTLS/服务令牌和网络策略；
- 所有 Controller 默认拒绝，显式声明公开/内部/租户范围；
- 权限变更要有审批、审计和缓存失效策略。

### 8.2 密钥与凭据

- 数据库、Redis、RabbitMQ、JWT、加密密钥、LWA、RDT、Ads、物流、AI、OSS、金蝶全部纳入 Secret 管理；
- 使用 Vault/KMS/External Secrets/SealedSecret；
- 禁止明文 token、密码、私钥进入 Git、日志、镜像和 APM；
- 建立密钥轮换、吊销、访问审计和泄漏响应；
- 区分开发/测试/生产凭据和环境。

### 8.3 PII 与 DPP

- 对买家姓名、地址、电话、邮箱、订单号、消息内容、物流地址做分类；
- 明确收集目的、最小化、访问、保留、删除和跨境传输；
- RDT 只用于必要受限数据操作，不把 RDT 持久化；
- 对 PII 访问建立审计和异常检测；
- 与 Amazon DPP、卖家协议、GDPR/CCPA 等要求逐项映射；
- 对导出、报表、AI、日志、备份、测试数据全部覆盖 PII 规则。

### 8.4 审计与不可否认

- 敏感操作必须有不可变审计记录；
- 审计内容包括主体、租户、店铺、资源、动作、时间、结果、变更摘要、来源；
- 审计写入失败要告警；
- 审计数据单独保留和访问控制；
- 定期做审计完整性和可追溯性演练。

### 8.5 供应链与部署安全

- 依赖漏洞扫描、License 检查、SBOM、镜像签名；
- 基础镜像最小化、非 root 运行、只读文件系统；
- K8s NetworkPolicy、RBAC、PodSecurity、Secret 加密；
- Ingress/WAF/DDoS 限流、TLS、HSTS、CSP；
- 发布前 SAST/DAST/Secret scan；
- 备份加密、恢复演练和跨区域策略。
## 9. API-Ready 对接能力设计与验收

### 9.1 “API-Ready”应该满足什么

真正的 API-Ready 不是“代码里有一个 RealClient”，而是满足以下条件：

1. **凭证契约**：每个平台/店铺有明确的 client id、secret、refresh token、region、marketplace、endpoint、scope、过期和轮换模型。
2. **授权契约**：卖家授权、应用审核、角色/权限、RDT/PII 授权有状态和操作入口。
3. **配置契约**：环境变量、Secret、外部配置、profile、多店铺覆盖关系有启动校验。
4. **协议契约**：签名/认证头、user-agent、限流、重试、超时、错误分类和响应解析符合官方要求。
5. **业务契约**：operation 与业务动作映射明确，字段、枚举、金额、币种、时区和空值语义可验证。
6. **一致性契约**：Outbox/Inbox、幂等键、状态机、DLQ、重放、对账和补偿齐全。
7. **安全契约**：租户/店铺隔离、字段权限、PII、审计、日志脱敏和密钥轮换齐全。
8. **运维契约**：指标、日志、trace、告警、runbook、回滚、演练和值班齐全。
9. **证据契约**：每个 operation 有 E1-E5 证据等级、最近测试时间和环境。
10. **边界契约**：未联调时 UI 和文档不得显示“已接通/生产可用”。

### 9.2 建议的连接器状态机

| 状态 | 含义 | 可否显示“已接通” |
|---|---|---|
| `NOT_CONFIGURED` | 没有配置 | 否 |
| `CONFIGURED` | 配置存在，但未验证 | 否 |
| `SELF_TEST_PASSED` | 本地/模拟自检通过 | 否 |
| `SANDBOX_VERIFIED` | 真实沙箱/测试环境通过 | 否（只能显示“沙箱已验证”） |
| `PRODUCTION_VERIFIED` | 真实生产试点、对账、运维验收通过 | 是 |
| `DEGRADED` | 曾接通但当前限流/权限/依赖异常 | 否，显示降级原因 |
| `REVOKED` | 授权被撤销/过期 | 否 |
| `FAILED` | 联调或运行失败 | 否 |

`SKIP`、`NOT_TESTED`、`MOCK_PASSED` 绝不能映射为 `PASS`。

### 9.3 SP-API 联调矩阵（E4 最低要求）

| 能力 | 沙箱/测试要求 | 证据 |
|---|---|---|
| LWA | 获取 access token，验证过期/刷新/撤销 | token 生命周期报告 |
| Sellers | marketplace participations | 真实响应字段 |
| Orders | 增量/历史订单、订单项、金额、地址 | 订单对账 |
| Inventory | FBA/自配送库存、在途/预留 | 库存对账 |
| Reports | 创建、轮询、下载、解析、幂等 | 报表对账 |
| Feeds | 提交、状态、错误回执 | 结果回执 |
| Notifications | SQS/SNS/EventBridge 订阅、消费、重试 | 通知入站日志 |
| RDT | 受限数据授权、请求、不持久化 | PII 访问审计 |
| Ads | 独立授权、profile、报表、限流 | 广告对账 |
| 限流 | 429、`x-amzn-RateLimit-Limit`、退避 | 限流测试报告 |
| 错误 | 400/401/403/404/429/5xx、超时、部分失败 | 错误分类矩阵 |
| 对账 | ERP 与 Amazon 数量/金额/状态差异 | 差异清单和关闭记录 |

### 9.4 E5 生产试点最低要求

- 选择 1-3 个真实店铺、有限 marketplace 和时间窗；
- 只读链路先上线，再开放写操作；
- 订单、库存、报表、通知、财务先对账；
- 每个写操作有幂等、审计、人工确认和回滚/补偿；
- 监控告警、值班、Runbook 和故障演练到位；
- 出现无法解释的财务/库存差异立即停止扩店；
- E5 通过后才更新对外“生产可用”表述。

## 10. 模拟数据与测试策略

### 10.1 已有能力

仓库已有 `tools/synthetic-data/`：

- 14 个数据库、113 张表、49 个迁移；
- demo 档约 225,734 行，ci 档约 25,484 行；
- 可生成、可校验、可清理、可登记；
- 可验证 DDL 类型/长度/精度/NOT NULL/日期/JSON/唯一键；
- 可在真实 MySQL 8 上导入、行数核对、cleanup 闭环；
- 数据带 `SYNTHETIC`、保留 ID 段和 `amz_ops.amz_synthetic_dataset_registry` 标记。

这是很好的离线基线，但它不是 Amazon 联调证据，也不能证明应用层校验、审计、权限和真实字段契约。

### 10.2 还需要补充的模拟数据

**租户与权限**

- 多租户、多店铺、跨店资源、无店铺用户、ADMIN、OPERATOR、只读、供应商、客服、财务角色；
- 跨店读取/写入/导出/批量/异步回调负向样本。

**订单与库存**

- 多 marketplace、多币种、多时区；
- 取消、退款、部分退款、退货、换货、补发；
- 超卖、负库存、重复扣减、乱序事件、延迟通知。

**物流**

- 多承运商、单号复用、轨迹乱序、重复、丢失、签收差异；
- 头程/尾程/海外仓/FBA 货件。

**财务**

- 平台费、广告费、退款、仓储费、运费、税费、采购成本、头程成本、汇率、跨月、负利润；
- 结算、回款、索赔、费用差异、凭证、对账差异。

**外部 API fixture**

- 官方 OpenAPI 快照对应的成功/空/部分/错误响应；
- 429/5xx/超时/非法 JSON/字段缺失/未知枚举；
- SQS/SNS/EventBridge 通知乱序、重复、延迟、死信。

**安全**

- 非法参数、超长字段、SQL/模板注入、越权、PII 导出、伪造 webhook、重放、恶意附件；
- 密钥泄漏扫描和日志脱敏测试。

### 10.3 测试层级

| 层级 | 目标 | 当前状态 |
|---|---|---|
| 单元测试 | 业务规则、状态机、幂等 | 已有大量 |
| 集成测试 | DB/MQ/Redis/外部桩 | 部分已有 |
| 契约测试 | OpenAPI、字段、错误码 | 部分已有，需全量 |
| 安全测试 | 鉴权、租户、PII、注入 | 不足 |
| 性能测试 | 容量、限流、慢查询 | 不足 |
| 故障演练 | Redis/MQ/DB/外部 API 故障 | 不足 |
| E4 沙箱 | 真实 Amazon 沙箱/测试 | 未取得 |
| E5 生产试点 | 真实店铺对账 | 未取得 |
| E2E | 前端全交互 | 有历史证据，本轮未复跑 |

## 11. 升级路线图

以下路线图假设目标是“可生产部署”，不是继续堆功能。工期取决于团队规模、Amazon 审核速度和真实业务范围，以下为相对阶段而非承诺日期。

### Phase 0：冻结与可发布基线（立即）

**目标**：让当前代码可审计、可复现。

- 清理日志、临时脚本、补丁、运行产物；
- 拆分未提交改动，建立 PR/评审/测试流程；
- 固定 Maven/npm/镜像/迁移版本；
- 建立干净 clone 构建和启动冒烟；
- 配置 Secrets、SBOM、镜像签名和发布清单；
- 关闭仓库根目录写日志。

**退出条件**：干净 clone 可构建；发布产物有 commit/digest/Flyway/前端版本。

### Phase 1：安全与正确性门禁（最高优先）

- 参数校验；
- HTTP 状态契约；
- 审计落库；
- 字段权限 fail-closed；
- 租户 guard 统一和跨店负向测试；
- refresh token 修复；
- OAuth token 加密/轮换/撤销；
- webhook 签名和重放防护；
- CORS、TLS、Actuator、Swagger、Secret 修复；
- CI 硬门禁。

**退出条件**：所有写接口有校验和权限测试；安全验收通过；错误契约稳定。

### Phase 2：可靠性与可观测性

- Redis 高可用和启动依赖矩阵；
- MQ ack/retry/DLQ/幂等统一；
- 调度锁/线程池/多实例测试；
- Prometheus target、exporter、Alertmanager receiver 修复；
- SLO、告警、值班、Runbook；
- 数据库备份恢复、主从/故障切换、灾备演练。

**退出条件**：依赖故障不会导致全系统不可用；关键告警可送达；RPO/RTO 演练通过。

### Phase 3：数据与性能

- 生产级索引和 EXPLAIN 复核；
- 大表分区/归档/冷热分离；
- 报表预聚合/物化；
- 缓存策略和主从一致性；
- 容量模型和压测；
- 前端大数据量优化。

**退出条件**：达到明确 SLO；压测无系统性瓶颈；报表和在线事务互不拖垮。

### Phase 4：业务闭环

- 订单/库存/采购/物流/客服/财务/广告状态机和幂等；
- 三单匹配、库存台账、财务账本、对账；
- RMA/PII；
- 外部系统（金蝶、物流、广告）字段和币种联调；
- 指标字典和数据质量规则。

**退出条件**：核心域端到端可对账；异常路径有补偿；财务/库存差异可解释。

### Phase 5：E4 沙箱联调

- SP-API/Ads/LWA/RDT/Notifications；
- 所有 operation 的权限、限流、字段、错误；
- SQS/SNS/EventBridge；
- 真实凭证轮换和撤销；
- 契约测试和联调报告。

**退出条件**：E4 矩阵全绿，关键 operation 有可复现证据。

### Phase 6：E5 生产试点与发布

- 小范围店铺/市场；
- 只读先上线；
- 对账、告警、值班、演练；
- 灰度、回滚、扩容、故障恢复；
- 人工确认后更新“生产可用”措辞。

**退出条件**：E5 验收通过；RPO/RTO/SLO 达标；无未解释财务/库存差异；管理层签署上线。

## 12. 生产验收清单

### 12.1 发布

- [ ] 干净 clone 可构建；
- [ ] commit + image digest + Flyway + 前端版本可追溯；
- [ ] 无日志/临时文件/Secret/大文件进入发布；
- [ ] CI 全门禁通过；
- [ ] 镜像签名、SBOM、漏洞扫描通过；
- [ ] 回滚脚本和演练通过。

### 12.2 安全

- [ ] 参数校验覆盖所有写接口；
- [ ] HTTP 状态契约测试通过；
- [ ] 租户/店铺跨店负向测试通过；
- [ ] 字段权限 fail-closed；
- [ ] 审计落库且不可篡改；
- [ ] token 加密、轮换、撤销；
- [ ] webhook 签名和重放防护；
- [ ] TLS/HSTS/CORS/Actuator/Swagger 收敛；
- [ ] Secret 管理、日志脱敏、PII 保留删除通过。

### 12.3 可靠性

- [ ] Redis/MQ/DB/外部 API 故障演练通过；
- [ ] 消息 ack/retry/DLQ/幂等测试通过；
- [ ] 调度多实例不重复；
- [ ] 备份恢复、主从切换、灾备演练通过；
- [ ] RPO/RTO 达标。

### 12.4 可观测性

- [ ] Prometheus 所有 target UP；
- [ ] 告警 receiver 可送达；
- [ ] SLO、错误率、延迟、队列、数据库、外部 API 指标齐全；
- [ ] 日志含 traceId 且无敏感信息；
- [ ] 值班、升级、Runbook、故障复盘机制生效。

### 12.5 业务与 API

- [ ] 订单/库存/财务/通知对账；
- [ ] SP-API/Ads E4/E5 证据；
- [ ] 限流、429、重试、退避验证；
- [ ] RDT/PII 访问合规；
- [ ] SQS/SNS/EventBridge 通知可靠；
- [ ] 外部系统字段、币种、税率、凭证状态联调；
- [ ] 连接器状态不把配置/模拟/跳过显示为已接通。

## 13. 成本、资源与风险提醒

### 13.1 成本不是只有云主机

必须同时预算：

- 开发：安全、租户、校验、审计、token、webhook、契约；
- 测试：E4/E5、压测、故障注入、安全测试；
- 运维：监控、值班、备份、灾备、日志存储、告警通道；
- 合规：PII/DPP、保留删除、审计、供应商安全；
- 外部系统：Amazon 开发者/卖家账号、Ads 授权、物流/ERP/AI/OSS 等费用；
- 人力：后端、前端、QA、SRE、安全、财务业务专家；
- 机会成本：Amazon 应用审核、权限申请、联调排期、卖家授权可能等待数周至数月。

### 13.2 资源量级（非报价）

在范围未确定前不能可靠报价。最低建议配置：

- 1 名技术负责人；
- 2-4 名后端；
- 1-2 名前端；
- 1 名 QA/自动化；
- 1 名 SRE/平台工程师；
- 1 名安全/合规负责人（可兼职但必须有明确责任）；
- 财务、采购、物流、客服业务专家按域参与验收。

生产环境至少需要独立 staging、生产、灾备/备份、密钥管理、监控告警和日志存储。小规模试点可以先从托管 MySQL/Redis/RabbitMQ 和单区域多可用区开始，但不能把“单机 Compose”当生产拓扑。

### 13.3 主要风险

1. **把“有客户端代码”误判为“已接通”**；
2. **把“测试通过”误判为“生产正确”**；
3. **财务口径错误**：币种、税、退款、广告费、采购成本、跨期；
4. **租户越权**：跨店数据、导出、异步回调、内部服务；
5. **凭据泄漏**：token、Secret、日志、备份、AI/第三方；
6. **限流/通知不可靠**：429、重试风暴、重复事件、DLQ 积压；
7. **迁移不可逆**：Flyway 顺序、多库、回滚、存量数据；
8. **监控失效**：指标目标错误、告警无人接收；
9. **成本失控**：AI、广告、日志、存储、外部 API；
10. **组织风险**：没有明确 owner、没有值班、没有上线签字。

## 14. 未验证事项与后续取证清单

本次不能宣称以下事项已验证：

- RabbitMQ down 对 message/outbox/DLQ/通知链路的真实影响；
- gateway/user 的 readiness/liveness 实际 HTTP 返回；
- `/user/refresh` 的真实 HTTP 行为（目前仅静态高风险）；
- 前端 `src/api/*` 与后端 346 个端点的全量契约；
- 连接器中心是否把 `SKIP` 错误显示为 `PASS`；
- README 文档漂移的全部条目；
- 每个 `isShopAllowed(...)` 调用点的入口类型（公网 Controller、内部服务、定时任务、白名单）；
- OAuth webhook 是否公网、是否有平台签名验证、`/oauth/token` 的租户边界；
- Docker Compose 与 K8s 服务名、端口、环境变量全量一致性；
- 备份恢复、Flyway 迁移顺序、14 库执行顺序；
- Prometheus 所有 exporter 的部署状态和告警送达；
- 全量后端/前端验收的本轮复跑；
- MySQL 当前凭据和当前 schema/行数/EXPLAIN；
- 4 个 `@RabbitListener` 的 DLQ/重试/幂等/poison message 全量测试；
- SP-API 底层组件 `SpiRateLimiter`、`UsagePlan`、`AwsSigV4Signer`、`LwaTokenManager`、`SpApiUserAgent`、`RestrictedDataTokenManager`、`TokensClient`、`SpApiGateway`、通知 Inbox/SQS 的逐项真实行为；
- 生产安全渗透、容量、灾备和合规验收。

## 15. 最终结论

### 15.1 这个项目做对了什么

- 模块和业务域覆盖广，已经不是简单 CRUD 原型；
- SP-API 适配层、限流、Outbox/Inbox、通知、凭证、RBAC、店铺隔离、合成数据等方向找得对；
- 已有不少单元测试、契约测试、前端测试和历史构建日志；
- README 已经主动承认“不能直接生产部署”，这是正确的风险态度；
- API-Ready 的目标是合理的，不需要推翻重做。

### 15.2 为什么仍然不能生产上线

生产上线不是“功能能点开”，而是“在真实数据、真实权限、真实故障和真实攻击下仍能正确、可审计、可恢复地运行”。当前项目在以下方面没有达到门槛：

- 安全与租户隔离没有默认拒绝的统一保证；
- 参数、HTTP 错误、审计、字段权限、token 生命周期存在明确缺陷；
- 监控告警和部署安全配置不可用；
- 发布基线不干净，CI 门禁不足；
- 没有 E4/E5 真实 Amazon 联调和对账证据；
- 财务、库存、通知、限流、灾备的正确性尚未证明。

### 15.3 建议的对外表述

对外可以写：

> 本项目已具备 Amazon SP-API/Ads 的对接能力，完成 API-Ready 底座建设，包括凭证模型、限流、Outbox/Inbox、通知、连接器台账和店铺隔离等。当前尚未完成真实沙箱/生产联调，不能宣称已接通或生产可用。

不要写：

> 有 API 凭据即可直接生产使用。

### 15.4 下一步唯一正确的优先级

1. 先清理发布基线并建立可复现构建；
2. 再修安全、校验、审计、租户、token 和 HTTP 契约；
3. 同时修监控、告警、TLS、Secret 和 CI 门禁；
4. 再进入 E4 沙箱联调；
5. 最后才做 E5 生产试点和业务扩容。

在 1-4 完成前继续增加业务页面或 AI 工具，只会扩大未验证的攻击面和运维债务。

## 16. 附录：关键证据索引

| 主题 | 关键文件/位置 |
|---|---|
| 全局异常 HTTP 语义 | `amz-common/src/main/java/com/amz/handle/GlobalExceptionHandler.java:38-90` |
| 操作审计 | `amz-common/src/main/java/com/amz/aspect/OperLogAspect.java:90-112`；`amz-service/amz-service-user/src/main/resources/db/migration/V1__init.sql:63` |
| 字段权限 fail-open | `amz-common/src/main/java/com/amz/service/impl/FieldPermissionServiceImpl.java:97-139`；`amz-common/src/main/java/com/amz/aspect/FieldPermissionAspect.java:78-81` |
| 租户上下文 | `amz-common/src/main/java/com/amz/context/UserContext.java:81-117` |
| Shop guard | `amz-common/src/main/java/com/amz/aspect/ShopIdGuardAspect.java:64-66` |
| 网关身份重写 | `amz-gateway/src/main/java/com/amz/filter/MyGlobalFilter.java:112-140` |
| 网关文档开关 | `amz-gateway/src/main/java/com/amz/filter/MyGlobalFilter.java:52-54` |
| CORS | `amz-gateway/src/main/java/com/amz/config/CorsConfig.java:21,32-36` |
| refresh 静态风险 | `amz-common/src/main/java/com/amz/interceptor/BaseAuthInterceptor.java:31-38,100-102`；`amz-common/src/main/java/com/amz/util/JwtUtil.java:156-164`；`amz-service/amz-service-user/src/main/java/com/amz/controller/LoginController.java:57-74` |
| OAuth token | `amz-service/amz-service-multiplatform/src/main/resources/db/migration/V1__init.sql:148-161`；`amz-service/amz-service-multiplatform/src/main/java/com/amz/model/OauthToken.java:18-19`；`MultiplatformServiceImpl.java:563-575` |
| 多平台端点 | `amz-service/amz-service-multiplatform/src/main/java/com/amz/controller/MultiplatformController.java:51,132,167,201` |
| MQ 薄弱点 | `amz-service/amz-service-message/src/main/java/com/amz/mq/consumer/MessageNoticeConsumer.java:19-22`；`amz-service/amz-service-message/src/main/java/com/amz/config/MqConfig.java:25-38` |
| 分布式锁 | `amz-common/src/main/java/com/amz/lock/DistributedJobLock.java:27-30,144-157,177-198` |
| SP-API Swagger | `amz-service/amz-service-spapi/src/main/resources/application.yml:224-229` |
| 网关 Actuator | `amz-gateway/src/main/resources/application.yml:151-155` |
| Prometheus | `prometheus/prometheus.yml`；`prometheus/rules/amz-alerts.yml:46,56`；`alertmanager/alertmanager.yml:17-18` |
| K8s 安全 | `k8s/ingress.yaml:23,39-45`；`k8s/secret.yaml:3` |
| 前端生产配置 | `amz-frontend/.env.production` |
| CI | `.github/workflows/ci.yml:13,214` |
| 订单属性索引 | `amz-service/amz-service-order/src/main/resources/db/migration/V1__init.sql:8-14` |
| 利润报表索引 | `amz-service/amz-service-order/src/main/resources/db/migration/V1__init.sql:94-113` |
| 合成数据 | `tools/synthetic-data/`；`docs/superpowers/runbooks/mock-data-seed-and-cleanup-runbook.md` |
| 生产设计事实源 | `docs/superpowers/specs/2026-09-24-amazon-erp-production-design.md` |
| API-Ready 计划 | `docs/superpowers/plans/2026-09-24-connector-api-ready-phase0.md` |

## 17. 事实源与外部链接

### Amazon 官方

- [Connecting to the Selling Partner API](https://developer-docs.amazon.com/sp-api/docs/connecting-to-the-selling-partner-api)
- [Usage Plans and Rate Limits](https://developer-docs.amazon.com/sp-api/docs/usage-plans-and-rate-limits)
- [Notifications API v1 Use Case Guide](https://developer-docs.amazon.com/sp-api/docs/notifications-api-v1-use-case-guide)
- [Authorizing Selling Partner API Applications](https://developer-docs.amazon.com/sp-api/docs/authorizing-selling-partner-api-applications)

### 官方/开源参考

- [amzn/selling-partner-api-models](https://github.com/amzn/selling-partner-api-models)
- [amzn/selling-partner-api-samples](https://github.com/amzn/selling-partner-api-samples)
- [aws-samples/selling-partner-api-bootstrap](https://github.com/aws-samples/selling-partner-api-bootstrap)
- [saleweaver/python-amazon-sp-api](https://github.com/saleweaver/python-amazon-sp-api)
- [nplszfl/OmniTradeERP](https://github.com/nplszfl/OmniTradeERP)
- [admin627863/Amazon-SP-ERPNext](https://github.com/admin627863/Amazon-SP-ERPNext)

---

**最终判断：当前项目是一个值得继续演进的 API-Ready 亚马逊 ERP 工程，但不是一个已经达到生产部署门槛的系统。下一步应优先完成发布治理、安全/租户/审计/凭据、HTTP 契约、可观测性、CI/CD 和 E4/E5 联调，而不是继续扩张功能面。**