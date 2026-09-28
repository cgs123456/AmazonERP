# 连接器验收 runbook（A5 取证：凭证到位当天）

> **状态：runner 已实现，尚未用真实凭证执行。** 本仓库当前**没有任何平台凭证**；离线自检和桩端到端可执行，但**没有真实 Amazon 联调记录**。
> runner 已实现 §3 的离线/桩取证命令；凭证到位当天仍需按本文件对真实服务、数据库、Redis、Nacos、Gateway 和 Amazon 环境执行。
> **不得**把本文件的存在当作「已接通」或「已验收」的证据（spec §1.9、§1.9.1(1)）。

关联文档：spec `docs/superpowers/specs/2026-09-24-amazon-erp-production-design.md` §1.9 / §1.9.1 / §1.9.2；计划 `docs/superpowers/plans/2026-09-24-connector-api-ready-phase0.md` Task 11 Step 6。

---

## 0. 三条硬约束（先读，违反则整份验收记录作废）

| # | 约束 | 依据 |
|---|---|---|
| C1 | **必须以 `SPRING_PROFILES_ACTIVE=prod` 启动被测服务** | 当前 `application.yml` 默认 `prod`（fail-closed），只有显式设置 `mock` 才启用样例客户端；`prod` 与 `mock` 同时激活会被 `ProductionProfileGuard` 在启动期拒绝。mock profile 下 `ReportsMockClient`/`FinancesMockClient`/`FeesMockClient` 会返回**样例数据**，据此产出的「成功样例」是假证据（P0-01 / P0-24） |
| C2 | **验收 JSON 中不得出现任何明文密钥** | clientId / clientSecret / refreshToken / accessKey / secretKey / LWA access_token 一律不写入；sellerId 打码（§4.6） |
| C3 | **每个 operation 至少一次真实成功 + 四类错误码（401/403/404/429）** | A5 的定义是「联调记录」，缺项即判定「未接通」，不得用「其余都通过了」折算（spec §1.9.1(5)） |

**证据等级口径**（`com.amz.connector.ConnectorEvidencePolicy`）：E0 无证据 / E1 自证 / E2 桩回放 / E3 契约锁定 / **E4 沙箱联调** / **E5 生产联调**。
本 runbook 的唯一产出目标：把 **A5 从 E1 抬到 E4/E5**；A1/A3/A4/A7/A8 的 `requiredLevel` 也是 E4，故必须同批取证。

---

## 1. 前置条件

### 1.1 平台侧（**非零成本**，必须提前启动）

| 项 | 事实 | 来源 |
|---|---|---|
| 沙箱应用注册 | 开发者档案审核期间即可自助注册，但**需企业证件 + 视频核验** | spec §1.9.2(5) |
| 沙箱地址 | `sandbox.sellingpartnerapi-{na,eu,fe}.amazon.com` | 同上 |
| 沙箱限流 | **5 rps / burst 15** → runner 必须串行且 ≥200ms 间隔，否则得出的「限流正常」是假象 | 同上 |
| 沙箱覆盖范围 | 官方仅对 **2xx 与 400** 给出覆盖说明 → **401/403/404/429 不能靠沙箱取证**，必须到生产或按官方指引构造（§4.3 给出四种构造法） | 同上 |

### 1.2 环境与配置

```powershell
# 1) profile 必须是 prod（C1）
$env:SPRING_PROFILES_ACTIVE = 'prod'
# 2) 凭证走环境变量/Secret，绝不写仓库、绝不进 shell 历史文件
$env:SPAPI_APP_NAME = 'AmazonERP'
$env:SPAPI_APP_VERSION = '<发布版本>'
# 3) 端点覆盖键在 prod 下必须留空；非空即拒绝启动（SpApiEndpointResolver 构造期抛 IllegalStateException）
#    若启动失败并报 SPAPI_ENDPOINT_NOT_ALLOWED / IllegalStateException，说明环境被污染，先清理再继续
```

被测服务：`amz-service-spapi`，默认端口 **8096**（`application.yml:2`）。

### 1.3 凭证到位当天的前置能力（P0-52；**a/b/c/d 已修复**）

四项前置能力均已在代码层落地：a 第 45/60/61 轮、b 第 59 轮、c/d 第 48 轮（见下表与 §3.4 / §7.1 / §7.8）。b 现在有结构化记录、只读端点与 Micrometer/Prometheus 出口，不再需要从 WARN 日志抄写；a 现在同时提供脱敏诊断文本与机器可读 `Result.error`；但真实平台错误字段、requestId 与 `x-amzn-RateLimit-Limit` / 429 语义仍只能在 A5 凭证联调时确认。

| 编号 | 缺口 | 实测证据 | 最小修复 |
|---|---|---|---|
| P0-52a 【**已修复（第 45/60/61/62 轮）**】 | **平台原始错误码在部分端点被吞** | 修复前：`SpapiController.syncOrders`、`InventoryController.sync` 只回 `"sync failed"`，`FeedsController.submit/status` 只回 `"feed submit failed"`/`"feed status failed"`；`FinancialDataController` 虽透出 `e.getMessage()`，却会把预签名 S3 URL 原文带进响应（→ P0-53） | 修复落点：新增 `connector/ErrorSummary.java`（① 沿 `getCause()` 取**最深层根因**——订单/库存的熔断 fallback 把平台错误包在 `degraded (circuit-breaker/exception)` 里，只看最外层会再次丢信息；② 掩掉签名/令牌/密钥/口令；③ 压单行 + 1000 字符上限），9 处边界出口（财务域 5 + 订单/库存/Feeds 4）统一改用它；第 60/61 轮新增 `ApiError`、`SpApiCallException` 与 `LocalApiException`，第 62 轮把 8 个 controller 的 **42 个 `Result.failure` 出口全部结构化**，并由 `ControllerFailureContractTest` 锁定。**边界**：真实平台字段与 401/403/404/429 语义仍未取证 |
| P0-52b 【**已修复（第 59 轮）**】 | **`x-amzn-RateLimit-Limit` 回填值原先无结构化出口** | 头已被读取并回填本地窗口（`OrdersClient.java`、`FbaInventoryClient.java`、`FeedsClient.java`、`SpApiGateway.java`），修复前唯一出口是收紧时的一条 WARN。第 59 轮新增 `RateLimitObservation` 不可变快照、`GET /spapi/connectors/rate-limits` 与 `spapi.ratelimit.limit` Gauge（`kind=observed|effective`），并暴露 `/actuator/prometheus`。 | **已落地**；真实平台头值单位/精度、缺失语义及 429 联调仍属 A5，不得据本地桩宣称 E4/E5。 |
| P0-52c 【**已修复（第 48 轮）**】 | **不存在验收 runner** | 修复前：全仓 `rg -i acceptance` 命中 0（仅文档引用，见 §3.4） | 落点 `tools/connector-acceptance/`（与 `tools/synthetic-data/` 同风格：Python + `.ps1`/`.sh` 包装）：`acceptance_runner.py` + `run.ps1` + `run.sh` + 桩 `fake-service.py`。两道闸门：①C1 不满足 → 退出码 2 **且不产出记录**；②桩自描述 `stub=true` 默认拒绝，须显式 `--allow-stub` 且 **A5 封顶 E2** |
| P0-52d 【**已修复（第 48 轮）**】 | **连接器自描述缺失**：`GET /spapi/status` 只回固定串 `"SP-API service running"`，C1 的四条硬约束（prod / 非 mock / 自检已跑 / 凭证 ≥1）在**进程外无法核验** | 修复前 mock profile 下 `ReportsMockClient`/`FinancesMockClient`/`FeesMockClient` 返回离线样例，「成功样例」是假证据 | 落点 `connector/ConnectorSelfDescription.java` + `SpapiController.status()`：返回 `{service, connector, profile, mockClientsActive, startupCheckRan, startupRequireCredentials, loadedCredentialCount}`（启动快照优先；未跑自检时如实写 `startupCheckRan=false`、`loadedCredentialCount=-1`；**不含任何机密**）；`ConnectorSelfDescriptionTest` 6 例锁死键集合。**副作用**：`data` 由字符串变对象，接入方若有外部消费者需同步 |

> a/b/c/d 均已在代码层落地（a 的机器可读结构在第 60/61 轮补齐），并决定「凭证到位当天能否一条命令出报告」。计划 DoD 中「`connector-acceptance-runbook.md` 落盘且可执行」自第 48 轮起前后半句均已满足；b 的限流观测出口于第 59 轮补齐。但「可执行」目前仍是在**本地桩**上验证的（§7.1），真实服务仍未跑过。

### 1.4 P0-53：预签名 S3 URL 经错误文本外泄（**已修复（第 45 轮）**）

`SpApiGateway.downloadBytes` 旧实现在下载非 200 时抛
`"download failed status=<code> url=<完整预签名 URL>"`；该文本经 `FinancialDataController.downloadDocument`
的 `e.getMessage()` **直接进入 HTTP 响应体**，同时被 `log.error(..., e)` 写进服务日志。
预签名 URL 的查询串本身就是凭证：`X-Amz-Credential=AKIA…`、`X-Amz-Signature=…`（样例 `X-Amz-Expires=300`），
有效期内任何拿到响应或日志的人都能直接下载结算原表（含订单级金额与买家信息）。

修复（同批三处 + 一条链路规则，缺一不可）：

1. **源头（读侧）**：网关只回显「状态码 + 对象路径」——`download failed status=403 path=/report/2026-09-24/settlement.tsv`；
   不拼接完整 URL，也不回显 S3 错误体（`SignatureDoesNotMatch` 等错误体会内嵌规范请求与凭证材料）；
   `URI.create` 失败的入口（同样会把完整 URL 回显进 JDK 异常文本）改为只回显对象路径 + 已脱敏原因。
2. **源头（写侧）**：`FeedsClient.uploadDocument` 是第二个预签名 URL 构造点（`PUT` 上传），
   修复前同样把 `URI.create` 失败的原话抛到边界——而**上传**预签名 URL 带的是**写权限**（可覆盖 Feed 文档）。
   现与读侧同构：只回显对象路径 + 已脱敏原因。
   **口径差异（有意）**：读侧的 403/传输失败路径完全不出现 URL 键名；`URI.create` 失败路径保留 JDK 原因短语
   （含参数**键名**与主机/对象路径，非机密），但**值**一律掩成 `***`。
3. **边界**：五个 controller 的失败出口统一走 `ErrorSummary.of(e)`，即使上游（含未来的新调用方）把 URL 写进异常文本，响应侧仍会被掩成 `X-Amz-Signature=***`。

**一条容易忽略的链路规则**：两处源头在 `URI.create` 失败时都**刻意不链 cause**。
原因：controller 用 `log.error("...", e)` 打印的是**整条异常链**，若链上保留 JDK 原异常
（其文本形如 `Illegal character in path at index 49: <完整预签名 URL>`），结果就是「响应侧干净了、日志侧还在漏」。
两条用例因此显式断言 `getCause() == null`，防回归。

锁定用例：`SpApiGatewayDownloadErrorTest`（4 例，E2：URL 原样出站 / 异常文本含状态码与路径但不含签名 / 传输失败不泄露 / `URI.create` 失败不链 cause）、
`FeedsClientUploadUrlLeakTest`（1 例，E2：写侧同口径——签名与凭证值不出现、不链 cause、失败即停在上传步骤）、
`FinancialDataControllerErrorTextTest`（3 例，E1：预签名 URL 脱敏、`degraded` 包装透出平台 `QuotaExceeded`、缺凭证显式失败）、
`ControllerErrorTextContractTest`（5 例，E1：四处固定文案端点 + 上游异常文本带 URL 的兜底）。

### 1.5 P0-54：Reports 路径版本号 `2021-09-01` **从未由 Amazon 发布**（**已修复（第 49 轮）**）

**症状**：客户端把 Reports 路径写成 `/reports/2021-09-01/reports` 与
`/reports/2021-09-01/documents`。该版本号不存在，意味着凭证到位当天
`createReport` → `getReport` → `downloadDocument` 整条结算/对账链路**必然 404**，
不是降级、不是空数据。本 runbook §4.5 的 Reports 闭环步骤在修复前**不可能通过**。

**三源核实（2026-09-24 实测，均为外部事实而非本仓自证）**：

| 来源 | 结果 |
|---|---|
| 官方模型仓库目录清单 `amzn/selling-partner-api-models` → `models/reports-api-model/` | **只有** `reports_2020-09-04.md`（158 B，废弃指针）与 `reports_2021-06-30.json`（83,685 B） |
| 直接取 `reports_2021-09-01.json` | **HTTP 404**，响应体 14 B 的 `404: Not Found` |
| 官方文档站 `.../reports-api-v2021-09-01-reference` | HTTP **200**（SPA 外壳），但 `<title>=Page Not Found`、页内 `2021-09-01` 命中 **0** 次 |
| 对照：`.../reports-api-v2021-06-30-reference` | `<title>=Reports v2021-06-30`、页内命中 **105** 次 |

**为什么一直没被发现——自证循环**：客户端、进程内桩、`acceptance_runner.py`、本 runbook
四方写的是**同一个错版本号**，所有 E1/E2 级测试都拿错版本号当期望值，永远绿灯；
**修复前** `docs/**` 里从未登记过该版本号（`git grep "2021-09-01" docs` 命中 0，即：文档从未写过它，也就从未被外部核对过），
而 `ReportsFieldContractTest` 只锁**字段名**、不锁**路径**。
真正的期望值必须来自仓库外的官方 OpenAPI 模型（即 §7 所列 E3 证据的来源）。

**修复（第 49 轮）**：10 个文件的路径字面量字节级替换为已发布版本 `2021-06-30`
（源码 4 + 测试 4 + 工具 2），全仓残留检查 0 命中；新增 `SpApiPathContractTest`
（4 例，E3）把「`src/main/java` 里的 SP-API 路径字面量 ⊆ 官方模型声明的 `paths`」
变成回归护栏，并冻结 6 份官方模型快照的字节数与 sha256。

**验收影响（诚实边界）**：这条修复只保证**我方请求路径与官方模型一致**（**E3**），
**不**证明平台接受我方请求——A5 仍需凭证联调（§4.5 与 §8 第 5 条）。

**同类风险未归零**：路径扫描只覆盖 `src/main/java`、只认 6 个路径根
（`/orders/ /reports/ /feeds/ /fba/ /finances/ /products/`）。将来接入
Listings / Notifications / FBA Inbound 等新 API 家族时，必须同步扩 `PATH_ROOTS`
与 `contracts/` 快照表，否则同类「版本号/路径写错但自证全绿」的缺陷仍可复现。

---

## 2. 判定口径（A1–A8）

| # | 达标定义（`ConnectorEvidencePolicy.Criterion.requiredLevel`） | 本 runbook 中的取证动作 |
|---|---|---|
| A1 | E4：认证/分页/幂等/错误分类 | §4.2 每个 operation 成功样例 + §4.3 四类错误码 |
| A2 | E3：缺凭证显式失败（不得空列表/null/占位号） | §4.1 步骤 3（`/spapi/status` + 无凭证店铺调用必须返回明确错误码） |
| A3 | E4：启动自检（缺凭证 / 非法密钥长度 / mock profile 三种情形均拒绝启动） | §4.1 步骤 1–2 |
| A4 | E4：凭证归属与轮换（两店隔离 + 密文落库 + 轮换/吊销） | §4.2 两店铺交叉验证（同一 token 不得跨店复用） |
| A5 | E4：**联调记录**（唯一取证路径） | 本文件全部章节的产出 |
| A6 | E3：能力清单与实现一致 | 服务直连路径是 `GET /spapi/connectors`（另有 `/spapi/connectors/{code}` 与自检端点）；网关别名 `GET /api/connectors` 已接入并重写到服务路径。验收时应按实际可达路径探测，不能把路径偏差写成未实现。 |
| A7 | E4：失败可重放（持久化重试 / DLQ / 原始响应留存） | **已实现（第 65 轮）**：持久化 Outbox、429/5xx/传输失败重试、4xx DLQ、GET/HEAD 自动重放、DLQ 人工重放。桩环境封顶 E2；真实 429/5xx 回放成功回读后才达 E4。 |
| A8 | E4：限流与配额真实 | §4.4；结构化观测出口已落地（第 59 轮），但真实 429/响应头与 usage plan 对账仍需凭证联调 |

> A6/A7 不通过时，整体 `displayText` 只能是「已接通（联调中）」，**不得**写「API-Ready」（`ConnectorEvidencePolicy.Assessment.displayText()` 的三种取值是唯一合法的对外文案）。

---

## 3. 一条命令（接口契约）

### 3.1 目标形态

```powershell
pwsh -File tools/connector-acceptance/run.ps1 `
  -ServiceUrl http://127.0.0.1:8096 `
  -Connector spapi `
  -ShopId 1001 `
  -MarketplaceId ATVPDKIKX0DER `
  -Operations orders,inventory,feeds,reports,finances,fees `
  -OutDir .\acceptance-out
```

等价直调：

```bash
python tools/connector-acceptance/acceptance_runner.py \
  --service-url http://127.0.0.1:8096 --connector spapi --shop-id 1001 \
  --marketplace-id ATVPDKIKX0DER --operations orders,inventory,feeds,reports,finances,fees \
  --out-dir ./acceptance-out
```

### 3.2 产出

- `connector-acceptance-<connector>-<yyyyMMddHHmmss>.json`
- 同名 `...json.sha256`（`sha256sum` 格式：`<hex>  <filename>`）
- 退出码：`0` = A1–A8 全部达到 `requiredLevel` 且有 A5 证据；`1` = 已执行但有缺项；`2` = 环境不可用/前置不满足（**不得**用 0 表示「跳过」）

### 3.3 目标 JSON schema

```json
{
  "schemaVersion": "connector-acceptance/1",
  "connector": "spapi",
  "generatedAt": "2026-09-24T21:30:00+08:00",
  "generator": { "path": "tools/connector-acceptance/acceptance_runner.py", "sha256": "<runner 自身哈希>" },
  "target": {
    "serviceUrl": "http://127.0.0.1:8096",
    "profile": "prod",
    "imageDigest": "sha256:<容器镜像摘要>",
    "configFile": { "path": "amz-service-spapi/src/main/resources/application-prod.yml", "sha256": "<配置哈希>" }
  },
  "identity": {
    "appId": "amzn1.sp.solution.<...>",
    "sellerIdMasked": "A1B2****",
    "marketplaceId": "ATVPDKIKX0DER",
    "region": "NA"
  },
  "operations": [
    { "name": "orders.fetchOrders", "method": "GET", "path": "/orders/v0/orders",
      "httpStatus": 200, "durationMs": 412,
      "request": { "query": "MarketplaceIds=...&CreatedAfter=...", "headers": { "user-agent": "AmazonERP/1.0.0 (...)" } },
      "response": { "payloadKeys": ["Orders", "NextToken"], "itemCount": 3 } }
  ],
  "errorCodes": [
    { "expected": 401, "operation": "reports.getReport",
      "platformStatus": 401, "platformCode": "Unauthorized", "platformMessage": "<平台原文>",
      "howConstructed": "使用已吊销 refresh token 的店铺", "evidence": "<日志行号/响应片段>" }
  ],
  "rateLimit": [
    { "endpoint": "reports", "header": "0.0167",
      "localWindowAfter": { "maxRequests": 1, "windowMs": 60000 }, "evidence": "<观测点>" }
  ],
  "documentChain": {
    "reportId": "<reportId>", "reportDocumentId": "<reportDocumentId>",
    "downloadStatus": 200, "compressionAlgorithm": "GZIP",
    "decompressedBytes": 1234, "sha256": "<解压后内容哈希>"
  },
  "criteria": {
    "A1": { "pass": true, "evidenceLevel": "E4", "evidence": ["operations", "errorCodes"] },
    "A5": { "pass": false, "evidenceLevel": "E3", "blockers": ["缺少 429 样例"] }
  },
  "conclusion": { "apiReady": false, "reachable": false,
    "displayText": "具备对接能力（未联调）", "blockers": ["A5(E3< E4)"] }
}
```

`displayText` 只能取 `ConnectorEvidencePolicy.Assessment.displayText()` 的三种值之一（「API-Ready（已联调）」/「已接通（联调中）」/「具备对接能力（未联调）」）。

### 3.4 诚实标注：第 42 轮时这条命令**不存在**（第 48 轮已修复）

**历史事实（第 42 轮，保留不改）**：全仓实测（排除 `dist`/`node_modules`/`target`/`out`）：`rg -i acceptance` 仅命中 5 处**文档**引用（本文件、spec §1.9.1(5)、plan Task 11、`src/test/resources/contracts/README.md:73`），**零命中** `tools/` 与任何可执行脚本。→ 因此 §3.1 的两条命令当时必然失败（找不到文件）。这不是笔误，是 P0-52c 的原始形态。

**第 48 轮更新（P0-52c 修复）**：`tools/connector-acceptance/` 已落地——`acceptance_runner.py`（runner 本体）、`run.ps1` / `run.sh`（包装，纯 ASCII）、`fake-service.py`（本地桩，**非证据**）。§3.1 的两条命令现在可执行；先跑自检与预览：

```powershell
pwsh -File tools/connector-acceptance/run.ps1 -Selftest   # 只自检，不连服务、不产出记录
pwsh -File tools/connector-acceptance/run.ps1 -DryRun     # 只打印计划，不建 socket
```

**仍未被证明的**：这两条命令**从未对真实 `amz-service-spapi` 跑过**（§7.1 的全部实测都对着本地桩）。因此「一条命令出**真实联调**报告」仍是待验证承诺；已被验证的是「夹具上机械正确 + 闸门能拦住假证据」。

---

## 4. 执行步骤

### 4.1 启动自检（A2 / A3）

| 步 | 动作 | 期望 | 不通过的含义 |
|---|---|---|---|
| 1 | 不带任何凭证、`SPRING_PROFILES_ACTIVE=prod` 启动 | **拒绝启动**，日志含缺凭证的明确原因 | 启动自检失效（A3 降级） |
| 2 | `SPRING_PROFILES_ACTIVE=prod,mock` 启动 | **拒绝启动**，日志含 profile 冲突原因 | 公共启动守卫失效（可能部分连接器静默返回样例数据） |
| 3 | `SPRING_PROFILES_ACTIVE=mock` 且无平台凭证启动 | 允许启动，但只能作为**显式离线演示**；`GET /spapi/status` 必须显示 `profile=mock`、`mockClientsActive=true` | 离线模式或自描述失真；该运行不得作为 A5 证据 |
| 4 | 真实凭证启动成功后，用**不存在的 shopId** 调 `GET /spapi/inventory/{shopId}` 与 `POST /spapi/sync/orders?shopId=<不存在>` | 明确错误（`no credential for shopId=...`），**不是**空列表/0 | A2 不达标（静默降级） |
| 5 | 记录 `GET /spapi/status` 响应、镜像 digest、配置哈希 | 见 §3.3 `target` | — |

依据：`com.amz.credential.ConnectorStartupCheck`（离线用例 `ConnectorStartupCheckTest`，4 例）与公共 `com.amz.config.ProductionProfileGuard`（`ProductionProfileGuardTest`，3 例）。

### 4.2 每个启用 operation 至少一次成功样例（A1 / A4）

| operation | 触发端点（现有） | 记录字段 |
|---|---|---|
| orders | `POST /spapi/sync/orders?shopId=` | 返回条数、分页是否走完（`NextToken`）、限流未触发 |
| inventory | `POST /spapi/inventory/sync/{shopId}` | 落库 SKU 数 |
| feeds | `POST /spapi/feeds/submit` → `GET /spapi/feeds/status/{shopId}/{feedId}` | `feedId`、`processingStatus`、`resultFeedDocumentId`（P0-30 修复后） |
| reports | `POST /spapi/finance/report/request?shopId=&marketplaceId=&reportType=` → `GET /spapi/finance/report/{reportId}?shopId=` | `reportId`、`processingStatus`、**`reportDocumentId`** |
| reports-download | `GET /spapi/finance/document/{documentId}?shopId=` | 解压后内容、字节数、哈希 |
| finances | `GET /spapi/finance/events?shopId=` | 事件条数、四类事件（INCOME/REFUND/FEE/ADJUSTMENT） |
| fees | `POST /spapi/finance/fees/estimate?...` | 佣金/配送费/杂项 |

**A4 交叉验证（必做）**：用**两个店铺**各跑一次 orders；断言两次请求的 `x-amz-access-token` 不同（token 缓存键 = `clientId:sha256(refreshToken)`），且均未出现跨店串用（P0-49 的回归验证）。

### 4.3 错误码覆盖 401 / 403 / 404 / 429（A1）

| 期望 | 构造方法 | 观测点 |
|---|---|---|
| 401 | 使用**已撤销/失效**的 refresh token 的店铺（或伪造 access_token 的临时店铺） | `FinancialDataController` 的 `Result.failure` 文本含平台 body；`OrdersClient.java:216-219` 的 token 驱逐日志 |
| 403 | 授权 scope **不含**目标 operation 的店铺（例：未勾选 Reports 权限） | 同上 |
| 404 | 不存在的 `reportId` / `feedId` / `orderId`（例：`GET /spapi/finance/report/r-not-exists?shopId=`） | 同上 |
| 429 | 在**沙箱 5 rps / burst 15** 之上故意超速（串行 ≤200ms 间隔即可逼近），或在生产按官方指引构造 | 客户端 429 日志（`OrdersClient.java:287-288`）+ §4.4 的限流观测 |

**必须记录的原始字段**：平台响应的 `errors[].code` 与 `errors[].message` 逐字保留（脱敏后）。
**注意（P0-52a 已修复，第 45/60/61/62 轮）**：finance / orders / inventory / feeds 五组端点的失败响应现在同时保留脱敏诊断文本与机器可读载荷——`Result.message` 仍便于人工排障，`Result.error` 提供 `{code, platformStatus, platformCode, platformMessage, requestId}`。`SpApiCallException` 从首个 `errors[0]` 与响应头提取平台字段，`LocalApiException` 对参数非法、越权、缺凭证、缺 marketplace、未知连接器、文件超限和预签名 URL 无效等本地失败做稳定分类；两者都不会携带完整响应体或请求头。第 62 轮已由 `ControllerFailureContractTest` 锁定 8 个 controller 的 42 个 `Result.failure` 出口全部携带结构化错误。**边界**：真实 401/403/404/429 的字段与请求头尚未联调取证；`requestId` 只在响应头实际返回时存在；E1 源码守卫不证明完整认证链路中的可达性或 HTTP 状态码映射。

### 4.4 限流与 `x-amzn-RateLimit-Limit` 回填（A8）

1. 触发一次 429（§4.3 最后一行）。
2. 记录 `x-amzn-RateLimit-Limit` 原值，以及**回填后的本地窗口**（`endpoint` / `maxRequests` / `window`）。
3. 观测点（P0-52b 第 59 轮已修复）：`GET /spapi/connectors/rate-limits` 返回原始 `headerValue`、`observedRatePerSecond`、`effectiveRatePerSecond`、`burst` 与 `observedAt`；Prometheus 抓取 `/actuator/prometheus` 的 `spapi.ratelimit.limit` Gauge，标签含 `shopId`、`operation`、`variant`、`kind=observed|effective`。
4. 对照官方 usage plan：本地 `SpiRateLimiter` 的 4 条策略为 orders 30/30s、fba-inventory 25/30s、listings 10/30s、reports 5/60s，**未知 endpoint 兜底 30 req/30s**（`SpiRateLimiter.java:68-74`）——兜底值与官方 `feeds`(0.0083 req/s) 等量级相差极大（P0-28），本步需逐 operation 记录「官方 rate/burst vs 本地窗口」差集。

### 4.5 Reports 文档链路闭环（P0-27 / P0-30）

必须证明：`reportDocumentId` 非 null → 预签名 URL 下载 → 解压 → 内容可解析（结算原表为 TSV）。

```
POST /spapi/finance/report/request?shopId=<id>&marketplaceId=<mid>&reportType=GET_V2_SETTLEMENT_REPORT_DATA_FLAT_FILE
  -> reportId
GET  /spapi/finance/report/{reportId}?shopId=<id>     # 轮询至 processingStatus=DONE
  -> reportDocumentId          <-- P0-27 的闭环点（离线已由 ReportsRealClientStubTest 锁死字段名）
GET  /spapi/finance/document/{reportDocumentId}?shopId=<id>
  -> TSV 文本（自动按 compressionAlgorithm 解压）
```

记录：`reportId` / `reportDocumentId` / `compressionAlgorithm` / 解压前后字节数 / 内容 sha256 / 行数。

### 4.6 脱敏规则（C2）

| 字段 | 处理 |
|---|---|
| clientId / clientSecret / refreshToken / accessKey / secretKey / access_token | **完全不写入**（连哈希也不写，避免离线爆破） |
| sellerId | 保留前 4 位 + `****` |
| marketplaceId / region / reportId / feedId | 原样保留（非秘密，是取证必需） |
| 买家 PII（姓名/地址/电话/邮箱） | 一律不入报告；需要时只记「字段存在」 |
| 平台错误 `message` | 保留原文，但先扫一遍是否回显了请求头/token（Amazon 不回显，但仍需脚本断言） |
| 本机绝对路径（`generator.options.outDir` / `configFile` / `attestationFile.path`） | **原样写入**（可审计性取舍）；报告对外分享前必须替换或删除，否则会暴露本机用户名与目录结构 |

### 4.7 operator 证据文件（`--attest`）的最小模板

有三条标准 runner **观测不到**，只能由 operator 提供证据：A3 的两例「拒绝启动」、A4 的两条店铺 token 摘要差异、A8 的 `x-amzn-RateLimit-Limit` 回填窗口。
缺证据时对应标准判 `E0`（**不猜**）。文件必须是**已脱敏**的 JSON，`schemaVersion` 固定为 `connector-acceptance-attestation/1`；
含明文机密（`AKIA…` / JWT / `x-amz-signature` / 刷新令牌）会被 runner **拒绝执行**（退出码 2，不产出记录）。

```json
{
  "schemaVersion": "connector-acceptance-attestation/1",
  "target": { "imageDigest": "sha256:<容器镜像摘要，64 位十六进制>" },
  "identity": { "appId": "amzn1.sp.solution.<...>", "sellerId": "<真实 sellerId；写入记录时会自动打码>", "region": "na" },
  "a3": { "startupRefusals": [
    { "case": "prod-without-credentials", "refused": true, "note": "<复现步骤 / 日志行号>" },
    { "case": "mock-profile",             "refused": true, "note": "<复现步骤 / 日志行号>" }
  ] },
  "a4": { "tokenObservations": [
    { "shopId": 1001, "accessTokenSha256": "<sha256(店铺 A 的 access_token)>" },
    { "shopId": 1002, "accessTokenSha256": "<sha256(店铺 B 的 access_token)>" }
  ] },
  "a8": { "rateLimitObservations": [
    { "endpoint": "GET /orders/v0/orders", "header": "x-amzn-RateLimit-Limit: 0.5",
      "localWindowAfter": { "maxRequests": 30, "windowMs": 1000 }, "evidence": "<观测点>" }
  ] }
}
```

填写要点：

- `a4` 只放 **sha256 摘要**，**不放 token 本体**；两条摘要必须**不同**（同一 token 串两个店会被判为无证据）。
- `a3` 的 `case` 名必须逐字为 `prod-without-credentials` 与 `mock-profile`，且 `refused=true`；否则 A3 停在 E1。
- `target.imageDigest` 缺失不致命，但 A3 需要 `target.binding`——既无镜像摘要又无配置文件哈希时，A3 只能到 E1。
- 该文件自身的 sha256 会写进记录的 `generator.options.attestationFile`（路径 + 哈希），可事后审计「谁在何时提供了什么证据」。
- 模板只描述**结构**；`<...>` 占位符必须换成真实观测值。**直接提交未替换占位符的文件等于伪造证据**，runner 虽然无法识别，但记录会绑定该文件的哈希，审计时无法解释。

---

## 5. 结论登记表（模板）

| 标准 | 证据等级 | 通过 | 证据指针 | 备注 |
|---|---|---|---|---|
| A1 | | | operations / errorCodes | |
| A2 | | | 无凭证调用错误码 | |
| A3 | | | 启动失败三例 | |
| A4 | | | 两店 token 差异 | |
| A5 | | | 本文件全部产出 | 缺任一子项即不通过 |
| A6 | | | `GET /spapi/connectors`（直连）或 `GET /api/connectors`（网关别名） | 网关别名运行期仍待真实服务验证 |
| A7 | | | 无 Outbox/DLQ → 预期不通过 | |
| A8 | | | rateLimit 段 | |

**对外文案**：仅当 A1–A8 全部达到 `requiredLevel` 才可写「API-Ready（已联调）」；A5 ≥ E4 但其余未齐 → 「已接通（联调中）」；否则「具备对接能力（未联调）」。

---

## 6. 回滚与安全

- 端点覆盖（`spapi.base-url-override` / `spapi.lwa-endpoint-override`）**只在非生产生效**；prod 下任一非空 → 构造期抛 `IllegalStateException`，**拒绝启动**（`SpApiEndpointResolver`）。
- 非官方且非白名单主机在**注入 token 之前**被拒（`SpApiRequestFactory`），覆盖生效期间定时任务不调平台、不落库（`OrderSyncScheduler` / `InventorySyncScheduler` 显式跳过）——避免桩数据污染业务表。
- 预签名 S3 请求只带 `user-agent`，**绝不**带 token / `Authorization` / `x-amz-date`（P0-50）。
- 验收结束后：清除环境变量中的凭证、删除本地生成的 JSON 明文副本（如需留存，存到受控对象存储并记录 sha256）。

---

## 7. 今天的离线证据（已完成部分，可复现）

命令（实测，2026-09-24）：

```powershell
$env:JAVA_HOME='C:\Users\Administrator\.cache\codex-tools\jdk-17.0.20.1+1'
& 'C:\Users\Administrator\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd' -B -ntp -pl amz-service/amz-service-spapi -am test
```

实测结果（第 45 轮复跑）：`amz-common` **51/51 PASS**；`amz-service-spapi` **Tests run: 171, Failures: 0, Errors: 0, Skipped: 2**，`BUILD SUCCESS`（2 skip = `SpApiIntegrationTest`，需 `RUN_INTEGRATION_TESTS=true`）。

> **第 48 轮复跑（2026-09-24，追加口径，不改上一行）**：`amz-service-spapi` **Tests run: 177, Failures: 0, Errors: 0, Skipped: 2**（171 → 177 = 本轮新增 `ConnectorSelfDescriptionTest` 6 例），`BUILD SUCCESS`；同轮另跑**全仓** `mvn -B -ntp test`（19 模块）：**Tests run: 632, Failures: 0, Errors: 0, Skipped: 2**，`Reactor Summary` 逐模块 `SUCCESS`。历史口径 626 是 spapi=171 时点值，632 − 626 = 6 与本轮新增例数自洽。

| 用例类 | 例数 | 对应标准 | 等级上限 |
|---|---|---|---|
| `com.amz.auth.LwaTokenExchangeContractTest`（第 63 轮更新） | 12 | A1（认证交换契约 + 类型化失败分类） | E3 |
| `com.amz.auth.LwaTokenManagerTest` | 9 | A1/A4（缓存与驱逐） | E2 |
| `com.amz.auth.SpApiUserAgentTest` | 7 | A1（必填头） | E3 |
| `com.amz.auth.SpApiConditionalSigningTest` | 6 | A1（有条件签名，P0-38） | E3 |
| `com.amz.client.SpApiProtocolStubTest` | 6 | A1（路径/头/查询/分页桩回放） | E2 |
| `com.amz.client.SpApiEndpointOverrideSafetyTest` | 12 | A3/A1（覆盖安全边界，P0-51） | E3 |
| `com.amz.client.SpApiRequiredHeaderContractTest` | 5 | A1（每请求必带合法 UA） | E3 |
| `com.amz.client.ReportsFieldContractTest` | 3 | A1（官方模型字段名，P0-27） | E3 |
| `com.amz.client.ReportsRealClientStubTest` | 3 | A1（Reports 文档链路桩回放） | E2 |
| `com.amz.connector.MarketplaceRegistryTest` | 6 | A1（23 条 marketplace fail-closed） | E3 |
| `com.amz.connector.ConnectorEvidencePolicyTest` | 10 | A5（本文件的判定逻辑） | E3 |
| `com.amz.credential.ConnectorStartupCheckTest` | 4 | A2/A3（启动自检） | E3 |
| `com.amz.connector.ErrorSummaryTest` | 13 | P0-52a/P0-53（错误文本收敛、脱敏、对象路径视图） | E1 |
| `com.amz.client.SpApiGatewayDownloadErrorTest` | 5 | P0-53 读侧（预签名下载 URL 不进异常文本/日志链） | E2 |
| `com.amz.client.FeedsClientUploadUrlLeakTest` | 1 | P0-53 写侧（预签名上传 URL 不进异常文本/日志链） | E2 |
| `com.amz.controller.FinancialDataControllerErrorTextTest` | 3 | P0-52a/P0-53（财务域边界出口） | E1 |
| `com.amz.controller.ControllerErrorTextContractTest` | 5 | P0-52a（固定文案端点边界出口） | E1 |
| `com.amz.result.ApiErrorSerializationTest`（第 60 轮新增） | 2 | P0-52a（成功响应不输出 error；失败响应输出结构化载荷） | E1 |
| `com.amz.connector.SpApiCallExceptionTest`（第 60/61 轮新增） | 7 | P0-52a（`errors[0]`、requestId、脱敏、传输/状态边界、本地错误分类） | E1 |
| `com.amz.client.SpApiGatewayUploadErrorTest`（第 61 轮更新） | 3 | P0-52a/P0-53（预签名上传边界） | E2 |
| `com.amz.controller.ControllerFailureContractTest`（第 62 轮新增） | 1 | P0-52a（42 个 controller 失败出口全量结构化） | E1 |
| `com.amz.credential.ShopCredentialStoreFailClosedTest`（第 63 轮新增） | 5 | A2/A3（生产启动/读写/删故障 fail-closed；非生产受控降级） | E1 |
| `com.amz.connector.ConnectorSelfDescriptionTest`（第 48 轮新增） | 6 | P0-52d（自描述键集合冻结 + 无机密 + 未跑自检时如实回报） | E2 |
| `com.amz.client.SpApiPathContractTest`（第 49 轮新增） | 4 | A1（源码路径 ⊆ 官方模型 `paths`，P0-54） | E3 |

**上限声明**：以上全部 ≤ E3。按 `ConnectorEvidencePolicy.offlineCeiling()`，无凭证阶段的整体等级上限由最弱一环决定，**A5 的离线上限是 E1**——故今天对外的正确表述是「**具备对接能力（未联调）**」。

### 7.1 第 48 轮新增：验收 runner 的桩级实测（P0-52c / P0-52d）

`tools/connector-acceptance/` 落地后，本轮用**本地桩**（`fake-service.py`，自描述恒带 `stub=true`）做了端到端实测。
**这组结果只证明 runner 的机械正确性与闸门有效性，不构成联调证据**（桩回放 ≤ E2）。

| 场景 | 命令要点 | 实测结果 |
|---|---|---|
| runner 自检 | `python tools/connector-acceptance/acceptance_runner.py --selftest` | **全绿**：脱敏 6 + 参数校验 6 + 键集合冻结 4 + 结构与隐私 7 + 判定 4 + 落盘 4 = **38 条断言**，退出码 0；**未连接任何服务** |
| 计划预览 | `--dry-run` | 退出码 0，打印调用计划；**未建 socket、未产出任何文件** |
| 参数/边界（8 例） | 坏 URL / 未知 connector / 未知 operation / 快速轮询 / 缺 marketplace-id … | 全部退出码 2，stderr **无值泄漏** |
| 桩护栏 | 对 `stub=true` 的服务跑真实验收，**不带** `--allow-stub` | 退出码 2，理由「桩夹具不构成联调证据」，**未产出记录**（输出目录不存在） |
| C1 闸门 | 桩以 `--profile mock` 启动 | 退出码 2（`profile=mock 不含 prod`），**未产出记录** |
| 桩端到端 A1–A8 | 带 `--allow-stub`；orders / inventory / feeds / reports / reports-download / finances / fees + 401 / 403 / 404 / 429 + 报表状态轮询（前 2 次 `IN_PROGRESS`） | 退出码 **1**（已执行但有缺项）。`A1 E4`、`A2 E4`、`A3 E1`、`A4 E1`、**`A5` 被桩自描述压到 `E2`**、`A6 E0`（**旧 runner 默认 `/api/connectors` 与服务直连路径不一致，且桩未开启 `--connectors-ok`；属验收工具误判，不是端点未实现**）、`A7 E0`（无观测点，恒不通过）、`A8 E0`；`displayText` = 「具备对接能力（未联调）」，`apiReady=false`、`reachable=false` |
| 桩端到端 + operator attestation | 同上，另加 `--attest`（`a3.startupRefusals` 两例 / `a4.tokenObservations` 两条不同摘要 / `a8.rateLimitObservations` / `target.imageDigest`），桩开启 `--connectors-ok`（同时支持 `/spapi/connectors` 与网关别名 `/api/connectors`） | 退出码 **1**，阻断项只剩 **`A5(E2<E4)` 与 `A7（第 64 轮为 E0；第 65 轮已实现，需真实 429/5xx 回放才可达 E4）`**；`A1/A2/A3/A4/A8 = E4`、`A6 = E3`。即除 A5（需真实部署）与 A7（第 64 轮历史口径；第 65 轮已实现，真实回放待 A5）外**全部自动达标** |
| 产物契约 | 读回 `.json` 与 `.sha256` | 顶层键集合 = 冻结 12 键；`.sha256` 为 `<hex>  <filename>\n`（**两空格**）；JSON 以 LF 结尾且与 `.sha256` 第一段**逐字节一致**；`conclusion.secretScan.verdict = PASS`；诱饵（`x-amz-signature` / `AKIA…` / 会话 token / JWT）**全部被掩且不误报**；`identity.sellerIdMasked` 打码生效（`A1STUBLOCAL0001` → `A1ST****`）；`generator.options` 只记 `authTokenFileProvided=true`，**不含 JWT 与其路径** |

**这组实测回答的问题**：「有 API 之后，除了 A5 真实部署联调与 A7 的真实 429/5xx 回放之外，其余判据能否自动达标？」
→ 在桩上补齐 operator 证据后，**A1–A4、A6、A8 全部达标，只剩 A5 与 A7（A7 已实现，但桩证据按契约封顶 E2）**。含义是：凭证到位当天的制约项是**可枚举**的，不是黑箱；
但**桩不是真实部署**，A5 的真实联调、A3 的「拒绝启动」实机复现、A8 的平台限流头回填仍需在凭证到位当天完成。

### 7.2 第 49 轮新增：官方路径契约与全量复跑（P0-54）

命令（实测，2026-09-24）：

```powershell
$env:JAVA_HOME='C:\Users\Administrator\.cache\codex-tools\jdk-17.0.20.1+1'
& 'C:\Users\Administrator\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd' -B -ntp -pl amz-service/amz-service-spapi -am test
python tools/connector-acceptance/acceptance_runner.py --selftest
pwsh -File tools/connector-acceptance/run.ps1 -Selftest
```

| 项 | 实测结果 |
|---|---|
| `amz-service-spapi` 单模块 | **Tests run: 181, Failures: 0, Errors: 0, Skipped: 2**，`BUILD SUCCESS`（177 → 181 = 本轮新增 `SpApiPathContractTest` **4 例**） |
| 全仓 `mvn -B -ntp test` | 19 模块 `BUILD SUCCESS`，**Tests run: 636, Failures: 0, Errors: 0, Skipped: 2**（632 → 636） |
| runner 自检（Python 与 `.ps1` 两条入口） | **38 条断言全绿**、退出码 0、未连接任何服务（本轮改过 runner 的 Reports 路径，故必须复跑） |

`SpApiPathContractTest` 的 4 个用例：

| 用例 | 断言 |
|---|---|
| `officialSnapshotsArePinned` | 6 份官方模型快照的**字节数 + sha256** 逐份锁定，且断言每份 > 1000 B（14 B 的 `404: Not Found` 无法冒充） |
| `sourcePathsAreDeclaredByOfficialModels` | 正则提取 `src/main/java` 全部 `.java` 里以 6 个路径根开头的字符串字面量，要求**每一条都被官方 `paths` 覆盖**（完全相等，或以 `literal + "/"` 为前缀——客户端用 `PATH + "/" + id` 拼接）；并要求提取到 ≥ 8 条才算扫描有效 |
| `unpublishedReportsVersionMustNotReturn` | `2021-09-01` 不得出现在 `src/main/java` 与 `src/test/java`（守卫类自身除外；另断言扫描到 ≥ 20 个文件，防止排除逻辑退化成空扫） |
| `reportsAndFeedsPathsUseThePublishedVersion` | 直接断言 `ReportsRealClient` 的两条路径字面量为 `2021-06-30`，且官方模型确实声明这四条 Reports/Feeds 路径 |

**证据边界**：本条列全部 ≤ **E3**（官方模型 + 哈希锁）。它证明了「路径对」，**没有**证明
「平台接受请求」——后者仍只有在凭证到位、按 §3 对真实服务跑一次（A5/E4–E5）之后才能宣称。

---

### 7.3 第 50 轮复跑（Task 5 限流改造后）

同一组命令的复跑结果（2026-09-24）：

| 项 | 实测结果 |
|---|---|
| `amz-service-spapi` 单模块 | **Tests run: 187, Failures: 0, Errors: 0, Skipped: 2**，`BUILD SUCCESS`（181 → 187 = 本轮新增 `SpiRateLimiterTest` **6 例**） |
| 全仓 `mvn -B -ntp test` | 19 模块 `BUILD SUCCESS`，**Tests run: 642, Failures: 0, Errors: 0, Skipped: 2**（636 → 642） |
| runner 自检（Python 与 `.ps1` 两条入口） | 仍全绿、退出码 0、未连接任何服务 |

`SpiRateLimiterTest` 的 6 个用例：

| 用例 | 断言 |
|---|---|
| `burstExhaustionWaitsAtOfficialRate` | `fees.getMyFeesEstimates`（0.5 req/s、burst 1）第二次 acquire 必须等 1500–6000 ms（旧实现按 endpoint 兜底直接放行） |
| `burstIsCappedAtOfficialValue` | `reports.createReport`（0.0167 req/s、burst 15）第 16 次必须阻塞；阻塞中中断必须抛 `RateLimitException` 且线程退出 |
| `tighteningOneShopDoesNotAffectAnother` | A 店观测收紧到 0.01 req/s 后，B 店仍是官方 0.5 req/s、burst 仍为 1，且首次 acquire 立即放行 |
| `waitingDoesNotBlockOtherShopsOrOperations` | A 店 `orders.getOrders` 长等待期间，B 店同 operation 与 A 店 `fees.getMyFeesEstimates` 均 < 500 ms 放行（证明等待在锁外） |
| `observedRateRecoveryRestoresOfficialPlan` | 收紧 → 观测值回升即恢复官方速率与 burst；观测值高于官方值时封顶不放大；非法/空/null 响应头被忽略 |
| `officialPlansMatchContractSnapshots` | 解析 6 份快照 Usage Plan 表（字节数 + sha256 双重锁定）与 `officialPlans()` **双向比对**：条目数 33、键集合一致、rate/burst 逐项一致；并反向断言无配额表的 operation 与 `JSON_LISTINGS_FEED` 分档**不得被凭空登记** |

**证据边界**：本条列全部 ≤ **E3**（官方模型 + 哈希锁 + 官方限流数值）。它证明了「本地限流器的
速率/burst 与官方模型一致、按店铺与 operation 隔离、观测收紧可恢复」，**没有**证明平台的
真实限流算法与本实现一致（**假设 1/4**，见 spec §4.6），也**没有**证明平台接受我方请求
（A5 需凭证联调）。

---

### 7.4 第 52 轮复跑（Task 6：连接器能力清单与自检端点）

命令（实测，2026-09-24，项目自有工具链）：

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.cache\codex-tools\jdk-17.0.20.1+1"
& "$env:USERPROFILE\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd" -B -ntp -pl amz-service/amz-service-spapi -am test
& "$env:USERPROFILE\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd" -B -ntp test
```

| 项 | 实测结果 |
|---|---|
| `amz-service-spapi` 单模块 | **Tests run: 203, Failures: 0, Errors: 0, Skipped: 2**，`BUILD SUCCESS`（187 → 203 = 本轮新增 `ConnectorRegistryTest` **11 例** + `ConnectorControllerGuardTest` **5 例**） |
| 全仓 `mvn -B -ntp test` | 19 模块 `BUILD SUCCESS`，**Tests run: 658, Failures: 0, Errors: 0, Skipped: 2**（642 → 658） |
| 交叉验证（第二套工具链） | 新装 JDK `21.0.12.1+1` + Maven `3.9.16` 复跑同为 **203 / 658**、`BUILD SUCCESS`（排除工具链偏差） |
| `SpApiPathContractTest` | 仍 **4/4 PASS**——本轮未放宽任何断言即解决冲突（详见下条） |

本轮解决的一个**真实冲突**（写入能力表时踩到，勿重犯）：

能力表最初把「未实现能力的官方路径」写进 `path` 字段，结果被 `SpApiPathContractTest`（P0-54 护栏）
当成**真实调用点**——该测试扫描 `src/main/java` 里以 6 个官方路径根开头的字符串字面量并断言其存在于官方模型，
实测命中 `products/pricing` 与 `fba/inbound`。修法是**不让未实现项以路径字面量出现在主代码**：
`path` 取占位值 `PATH_NOT_IMPLEMENTED`，真实官方路径写进 `note` 且不带前导斜杠。
**没有**采用「把 `ConnectorRegistry.java` 加进扫描排除清单」这条捷径——那等于让能力表自己豁免自己。

`ConnectorRegistryTest` 的 13 个用例（要点）：

| 用例 | 断言 |
|---|---|
| `implementedOperationsHaveRealCallSites` | 已实现的 25 条 operation 必须在 `src/main/java` 有真实调用点（排除能力表自身，排除集被逐字锁死） |
| `registryMatchesClientCallSitesBidirectionally` | `com/amz/client` 中的 operationId 集合与能力台账 `IMPLEMENTED` 集合完全相等，当前为 25 条；任一侧新增或删除都会失败 |
| `notImplementedOperationsCarryEvidenceNote` | 未实现项必须显式在列且 `note` 非空（防止前端把「无代码」渲染成「未配置」） |
| `a5IsE0WithoutRealIntegration` + `assessmentStaysHonest` | A5 无联调记录 → 声明 E0，`apiReady=false`、`reachable=false`、`displayText=具备对接能力（未联调）` |
| `a7OfflineEvidenceIsE3` | Outbox/DLQ/重放的离线证据最高只能声明 E3，真实 429/5xx 重放仍需 E4 |
| `credentialSourceNeverClaimsUnimplementedSources` | `credentialSource` 只输出 `db` / `none`，永不输出未实现的 `env` / `vault` |
| `mockProfileIsReported` | mock profile 激活时如实上报 `mockActive`，且此时 `apiReady=false`（mock 下的「成功」不算证据） |
| `selfTestResultIsRecorded` | 自检结果可回读、可清除回 `NEVER_RUN`；`lastCallAt` 从未执行时为 null（不伪造时间） |
| `operationsAreImmutable` / `describeAllContainsOnlyRegisteredConnectors` | 能力表不可变；渲染结果不含 `clientSecret` / `refreshToken` / `AKIA` 等任何凭证字段 |

**证据边界**：本条列全部为 **E1（自证）**——断言对象是本仓库源码。它让「支持什么、缺什么、证据到哪一级」
变成一条命令可判定的事实源，但**不证明平台会接受我方请求**；后者仍要等凭证到位、按 §3 对真实服务跑一次（A5 / E4–E5）。

**第 78 轮复测（2026-09-26）**：SP-API 全模块 `mvn -B -ntp -pl amz-service/amz-service-spapi -am test` 为 **362 / 0F / 0E / 2S**；全仓 `mvn -B -ntp test` 为 **19/19 模块 BUILD SUCCESS**，Surefire 新鲜 XML **161 份 / 1055 / 0F / 0E / 2S**。2 个 skip 仍为 `SpApiIntegrationTest`，原因是缺少真实凭证，不是通过。

> 口径提醒（第 53 轮历史记录，不适用于第 78 轮）：当时全仓计数按 `^\[(INFO\|WARNING)\] Tests run:` 统计（spapi 汇总行因有 skip 而是 `[WARNING]` 前缀）；
> 当时 `-pl … -am` 的 reactor 合计是 **254 = amz-common 51 + spapi 203**；第 78 轮 SP-API 单模块已增至 362。

### 7.5 第 53 轮复跑（Task 9：Redis/Redisson 基线；新发现 P0-55/56：Nacos 地址）

命令（实测，2026-09-24，项目自有工具链）：

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.cache\codex-tools\jdk-17.0.20.1+1"
$m = "$env:USERPROFILE\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd"
& $m -B -ntp -pl amz-service/amz-service-order,amz-service/amz-service-product -am test `
     -Dtest=RedissonConfigTest -Dsurefire.failIfNoSpecifiedTests=false   # 注意：不要用 -D.surefire...（会报 Unknown lifecycle phase）
& $m -B -ntp test    # 全仓
```

| 项 | 实测结果 |
|---|---|
| Redisson 基线 | order **4/4**、product **4/4** PASS；两份 `RedissonConfig.java` 已删，`OrderServiceImpl` 的 `RedissonClient` 注入已移除（该字段零使用） |
| Nacos 契约 | `NacosAddressContractTest`（amz-common）**5/5** PASS：17 份 Spring 配置默认值不含非私网 IPv4、部署清单无 `NACOS_SERVER_ADDR`、compose 注入 16 段、k8s 16 份全部引用、`.env.example` 已声明 |
| 全仓回归 | 19 模块 **671 例 / 0F / 0E / 2S**（666 → 671），BUILD SUCCESS |

**部署前必须人工确认（本轮新增，写到验收清单里）**：目标环境的 `NACOS_ADDR` 必须显式注入且指向**自己的** Nacos。
未注入时服务会连 `127.0.0.1:8848` 并快速失败（**不再**连第三方主机），但**不会**因此拒绝启动——
生产 profile 的「关键中间件地址未显式注入即拒绝启动」门禁**尚未实现**（已登记为 Task 8 增补项）。


### 7.6 第 57 轮复跑（P0-57：运行模式 fail-closed，禁止生产静默跑 mock）

命令（实测，2026-09-25，项目自有工具链）：

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.cache\codex-tools\jdk-17.0.20.1+1"
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp"   # 本机沙箱必需
$m = "$env:USERPROFILE\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd"
& $m -B -ntp -pl amz-common test -Dtest=ProfileActivationContractTest -DfailIfNoTests=false
& $m -B -ntp test                                   # 全仓
& $m -B -ntp -pl amz-service/amz-service-spapi -am test
```

| 项 | 实测结果 |
|---|---|
| 新守卫 | `ProfileActivationContractTest`（amz-common）**4/4** PASS |
| 变异验证 | 默认值改回 `mock` / 删一段 compose 注入 → **2 条断言分别报红**（定位到模块名与「15 ≠ 16」） |
| 全仓回归 | 19 模块 **675 例 / 0F / 0E / 2S**（671 → 675，+4），BUILD SUCCESS |
| spapi 单模块 | **203**（2 skip，即 `SpApiIntegrationTest`）；amz-common **60** |

**部署前必须知道的连带语义（本轮新增，写进验收清单）**：

1. `prod` 是有语义的 profile，不是占位符。`SpApiEndpointResolver.PROD_PROFILE = "prod"`：
   `prod` 下两个端点覆盖键非空即**拒绝启动**；`application-prod.yml` 置 `require-credentials: true`，
   缺凭证**拒绝启动**。因此**无凭证时 spapi 拒绝启动是期望行为**，不要当成回归去「修」。
2. 沙箱 / 本地桩联调**不得**使用 `prod` profile（端点覆盖会顶掉进程），改用 `dev` / `sandbox`。
3. 离线演示若要回到样例数据，必须**显式** `SPRING_PROFILES_ACTIVE=mock`——现在不再有「忘记设置就给假数据」这条退路。

### 7.7 第 58 轮复跑（Uploads multipart 上限与连接器边界收口）

命令（实测，2026-09-25，项目自有工具链）：

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.cache\codex-tools\jdk-17.0.20.1+1"
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp"
$m = "$env:USERPROFILE\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd"
& $m -B -ntp -pl 'amz-service/amz-service-spapi' -am `
  '-Dtest=UploadsControllerContractTest,DeploymentManifestContractTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
& $m -B -ntp -pl 'amz-service/amz-service-spapi' -am test
```

| 项 | 实测结果 |
|---|---|
| 定向守卫 | Uploads 契约 **7/7**、部署清单契约 **5/5**，合计 **12 例 / 0F / 0E**，BUILD SUCCESS |
| SP-API 全量 | **258 例 / 0F / 0E / 2S**，Reactor BUILD SUCCESS |
| `amz-common` | **61 例 / 0F / 0E / 0S** |
| 跳过的 2 例 | `SpApiIntegrationTest`，需 `RUN_INTEGRATION_TESTS=true`、真实网络与 `TEST_SHOP_ID` |
| Uploads 内存边界 | `multipart.max-file-size=10MB`、`max-request-size=11MB`；控制器在 `getBytes()` 与上游调用前检查 10MB |
| 仍未证明 | 未做流式上传、真实 S3 PUT、断点续传、重试/幂等、孤儿 destination 清理；因此不构成 E4/E5 联调证据 |

**部署前必须人工确认**：`spring.servlet.multipart.max-file-size` / `max-request-size` 可被环境变量覆盖；
若业务确实需要大于 10MB 的文件，必须同步评估容器堆、并发数、S3 超时与重试，而不是只把上限调大。
### 7.8 第 59 轮复跑（限流观测结构化出口）

命令（实测，2026-09-25，项目自有工具链）：

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.cache\codex-tools\jdk-17.0.20.1+1"
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp"
$m = "$env:USERPROFILE\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd"
& $m -B -ntp -pl 'amz-service/amz-service-spapi' -am `
  '-Dtest=SpiRateLimiterTest,SpiRateLimiterMetricsContextTest,ConnectorControllerRateLimitContractTest,ConnectorControllerGuardTest,DeploymentManifestContractTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
& $m -B -ntp -pl 'amz-service/amz-service-spapi' -am test
```

| 项 | 实测结果 |
|---|---|
| 定向守卫 | 限流器 **8/8**、最小 Spring Boot 指标上下文 **1/1**、连接器限流契约 **2/2**、控制器守卫 **5/5**、部署清单契约 **6/6**，合计 **22 例 / 0F / 0E**，BUILD SUCCESS |
| SP-API 全量 | **264 例 / 0F / 0E / 2S**，Reactor BUILD SUCCESS |
| `amz-common` | **61 例 / 0F / 0E / 0S** |
| 跳过的 2 例 | `SpApiIntegrationTest`，需 `RUN_INTEGRATION_TESTS=true`、真实网络与 `TEST_SHOP_ID` |
| 路由证据 | standalone MockMvc 实测 `GET /spapi/connectors/rate-limits` 返回 200，精确路径优先于 `/{code}`，JSON 含 `operationId` 与原始 `headerValue` |
| 指标证据 | 最小 Spring Boot 上下文实测 `SpiRateLimiter` 作为 `MeterBinder` 自动绑定，Prometheus scrape 文本含 `spapi_ratelimit_limit` |
| 配置修正 | Boot 3 主键 `management.prometheus.metrics.export.enabled=true`；旧键 `management.metrics.export.prometheus.enabled` 被部署契约测试禁止 |
| 仍未证明 | 未发送真实 429、未采集真实响应头、未启动完整应用抓取 `/actuator/prometheus`；因此仍不构成 E4/E5 联调证据 |

### 7.9 第 60/61 轮复跑（结构化错误契约与本地/上游分类）

命令（实测，2026-09-25，项目自有工具链）：

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.cache\codex-tools\jdk-17.0.20.1+1"
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp"
$m = "$env:USERPROFILE\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd"
& $m -B -ntp -pl 'amz-service/amz-service-spapi' -am `
  '-Dtest=ApiErrorSerializationTest,SpApiCallExceptionTest,ControllerErrorTextContractTest,ConnectorControllerGuardTest,ConnectorControllerRateLimitContractTest,SpiRateLimiterTest,SpiRateLimiterMetricsContextTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

| 项 | 实测结果 |
|---|---|
| 结构化错误定向守卫 | `amz-common` **2/2** + SP-API **28/28**，合计 **30 例 / 0F / 0E / 0S**，Reactor `BUILD SUCCESS` |
| 逐类覆盖 | `ApiErrorSerializationTest` 2、`SpApiCallExceptionTest` 7、`ControllerErrorTextContractTest` 5、`ConnectorControllerGuardTest` 5、`ConnectorControllerRateLimitContractTest` 2、`SpiRateLimiterMetricsContextTest` 1、`SpiRateLimiterTest` 8 |
| 已证明 | `Result.error` 的 JSON 字段、`errors[0]` 解析、响应头 requestId 提取、消息/ID 脱敏、传输失败与状态失败边界、Sentinel 包装链提取，以及本地错误不被误标为 `UPSTREAM_ERROR` |
| 未证明 | 未用真实 Amazon 401/403/404/429 响应取证字段名、状态映射或 requestId 覆盖。第 61 轮结束时的 42 个未迁移 `Result.failure` 出口已由 §7.10 全量收口；本节的证据仍为 **E1/E2**，不是 E4/E5 |

### 7.10 第 62 轮复跑（42 个 controller 失败出口全量结构化）

命令（实测，2026-09-25 12:30:38 +08:00，项目自有工具链）：

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.cache\codex-tools\jdk-17.0.20.1+1"
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp"
$m = "$env:USERPROFILE\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd"
& $m -B -ntp -pl 'amz-service/amz-service-spapi' -am `
  '-Dtest=ApiErrorSerializationTest,SpApiCallExceptionTest,ControllerErrorTextContractTest,ControllerFailureContractTest,ConnectorControllerGuardTest,ConnectorControllerRateLimitContractTest,SpiRateLimiterTest,SpiRateLimiterMetricsContextTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

| 项 | 实测结果 |
|---|---|
| 定向守卫 | `amz-common` **2/2** + SP-API **29/29**，合计 **31 例 / 0F / 0E / 0S**，Reactor `BUILD SUCCESS` |
| 逐类覆盖 | `ApiErrorSerializationTest` 2、`SpApiCallExceptionTest` 7、`ControllerErrorTextContractTest` 5、`ControllerFailureContractTest` 1、`ConnectorControllerGuardTest` 5、`ConnectorControllerRateLimitContractTest` 2、`SpiRateLimiterTest` 8、`SpiRateLimiterMetricsContextTest` 1 |
| 已证明 | 8 个 controller 共 **42** 个 `Result.failure(...)` 出口全部包含 `ErrorSummary.localError(...)` 或 `ErrorSummary.toApiError(...)`；新增/删除失败分支会因数量变化或结构缺失令 E1 守卫报红 |
| 新增本地码 | `MARKETPLACE_MISSING`、`FORBIDDEN`、`CONNECTOR_NOT_FOUND`、`FILE_TOO_LARGE`，连同既有 `INVALID_REQUEST`、`PRESIGNED_URL_INVALID`、`CREDENTIAL_MISSING` |
| 未证明 | 未联调真实 Amazon 401/403/404/429；不证明每个分支在完整认证链路中的可达性、HTTP 状态码映射或 requestId 覆盖；因此证据为 **E1**，不是 E4/E5 |

### 7.11 第 63 轮复跑（LWA 失败分类与凭证库 fail-closed）

命令（实测，2026-09-25 12:34-12:38 +08:00，项目自有工具链）：

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.cache\codex-tools\jdk-17.0.20.1+1"
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp"
$m = "$env:USERPROFILE\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd"
& $m -B -ntp -pl 'amz-service/amz-service-spapi' -am `
  '-Dtest=LwaTokenExchangeContractTest,ShopCredentialStoreFailClosedTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
& $m -B -ntp -pl 'amz-service/amz-service-spapi' -am `
  '-Dtest=PlaceholderCoverageContractTest,DeploymentManifestContractTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
& $m -B -ntp -pl 'amz-service/amz-service-spapi' -am test
& $m -B -ntp test
```

| 项 | 实测结果 |
|---|---|
| LWA/凭证定向 | LWA **12/12** + 凭证库 **5/5** = **17 例 / 0F / 0E / 0S**，`BUILD SUCCESS` |
| 部署契约 | `PlaceholderCoverageContractTest` 2/2 + `DeploymentManifestContractTest` 6/6 = **8 例 / 0F / 0E / 0S**，`BUILD SUCCESS` |
| SP-API 模块 | **278 例 / 0F / 0E / 2S**，`BUILD SUCCESS`；跳过项仍为真实网络 `SpApiIntegrationTest` |
| 全仓 | 19 模块全部 `SUCCESS`；Surefire XML 汇总 **765 例 / 0F / 0E / 2S** |
| 已证明 | LWA 4xx/429/5xx/契约破损/传输失败稳定分类与脱敏；生产凭证库启动、读、写、删故障 fail-closed；Compose/K8s/env 模板键覆盖完整 |
| 未证明 | 真实 Amazon LWA 失败响应与 token 刷新、真实 DB 故障下进程探针、K8s 实集群接受性；因此本组证据仍为 E1/E2，不是 E4/E5 |

## 8. 未验证与风险（诚实清单）

1. **runner 已存在，但从未对真实服务跑过**（P0-52c 第 48 轮修复）→ §3 的命令现在可执行，且**只有 C1 全绿才会产出记录**；但迄今所有执行都对着**本地桩**（`stub=true` + `--allow-stub`，A5 封顶 E2），因此「一条命令出**真实联调**报告」**仍是待验证承诺**，不是现状。
2. **结构化错误出口已全量覆盖，但真实平台字段仍未闭环**（P0-52a 第 45/60/61/62 轮）→ 8 个 controller 的 42 个 `Result.failure` 出口均返回 `Result.error={code,platformStatus,platformCode,platformMessage,requestId}`，并区分 `SPAPI_CALL_FAILED`、`CREDENTIAL_MISSING`、`PRESIGNED_URL_INVALID`、`INVALID_REQUEST`、`MARKETPLACE_MISSING`、`FORBIDDEN`、`CONNECTOR_NOT_FOUND`、`FILE_TOO_LARGE`、`SPAPI_UNKNOWN_MARKETPLACE`、`SPAPI_UNSUPPORTED_REGION` 与未知 `UPSTREAM_ERROR`；但真实 401/403/404/429 尚未取证，requestId 只从响应头提取。E1 源码守卫证明“出口结构齐全”，不证明完整认证链路中的可达性或 HTTP 状态码映射。
3. **限流头结构化出口已存在，但平台语义未证明**（P0-52b 第 59 轮修复）→ 可从 `GET /spapi/connectors/rate-limits` 与 `spapi.ratelimit.limit` Gauge 取证；当前只证明出口和本地封顶逻辑，尚未证明真实平台头单位、精度、缺失/恢复语义，也尚未在完整应用中实际抓取 `/actuator/prometheus`。
4. **沙箱覆盖范围未联网复核**：第 22 轮结论为「官方仅说明覆盖 2xx 与 400」；凭证到位当天须以官方文档确认，若沙箱实际不覆盖目标错误码，则 401/403/404/429 必须改到生产（需用户书面确认）。
5. **本沙箱无法建立 socket**（`IOException: Unable to establish loopback connection`，宿主限制）→ 离线阶段的桩回放全部走**进程内 `RecordingHttpTransport`**，真实 `HttpClient`/DNS/TLS 路径**未经任何测试**。
6. **A6 路径已对齐但未做真实运行期验证；A7 已实现但未做真实 429/5xx 回放**：能力清单端点直连路径为 `GET /spapi/connectors`，网关别名 `/api/connectors/**` 已在配置中接入并重写；但尚未对真实 `amz-service-spapi` 与 Nacos/Gateway 运行期链路执行 runner。Outbox/DLQ 重放（A7）已在第 65 轮实现；尚未对真实 Amazon 429/5xx 做重放闭环。即使 A1–A5 通过，也只能标「已接通（联调中）」。
7. **成本与时间未确认**：沙箱注册（企业资质 + 视频核验）、生产授权、Amazon 安全问卷/DPP 时限均为**用户侧投入**，本文件不给出工期承诺。
8. **`SpApiIntegrationTest` 是唯一的真实网络路径**，且默认跳过；凭证到位当天应先跑它（`RUN_INTEGRATION_TESTS=true` + `TEST_SHOP_ID`）作为**冒烟**，再跑本 runbook 的完整取证。
9. **预签名 URL 的泄露面已收窄，但没有归零**（P0-53）→ 两个预签名构造点（读侧 `SpApiGateway.downloadBytes`、写侧 `FeedsClient.uploadDocument`）都不再拼 URL，`URI.create` 失败时**不链 cause**，五个边界出口统一脱敏。**仍未覆盖**：① 未做全仓出站扫描——新增调用方若自行把预签名地址写进日志或响应，仍可绕过（现存断言只锁这两个构造点与五个 controller 出口）；② 未引入日志侧 scrub（appender/日志框架过滤器），因此**非** `URI.create` 的传输异常若自带 URL 文本，仍可能进日志；③ 平台返回的 S3 错误体**有意不回显**（会内嵌规范请求/凭证材料），排障只能靠状态码 + 对象路径。

10. **路径契约扫描有明确边界**（P0-54 已修复，但护栏不是全知）→ `SpApiPathContractTest`
    只扫 `src/main/java` 的**字符串字面量**、只认 6 个路径根，且**不覆盖**运行时拼接出的动态段
    （如 marketplace / 日期 / 分页参数）；将来新增 API 家族或改用配置化路径时必须同步扩表。
    另外它断言的是「与官方模型一致」（E3），**不能**替代 A5 的真实联调。

11. **中间件地址的注入门禁尚未 fail-closed（第 53 轮新增）** → P0-55/56 修复后，Nacos 的默认值已从第三方公网地址
    改为 `127.0.0.1:8848`，Redis 改走 `spring.data.redis.*`；但**未显式注入时服务仍会启动**（只是连不上）。
    凭证到位当天部署前，须人工核对 `NACOS_ADDR` / `REDIS_HOST` 等确实注入；自动门禁（prod profile 拒绝启动）
    尚未实现，不要在验收记录里把它写成「已有」。
12. **配置卫生的守卫是扫描型断言，有固有盲区** → `NacosAddressContractTest` 与两份 `RedissonConfigTest`
    只扫固定路径下的配置文件，且硬编码期望数量（16 / 17）。新增服务、改名或迁移目录时必须同步更新，
    否则断言会**假通过**（扫空）或误报；它们也**不覆盖**运行时从配置中心拉到的值——
    Nacos 上的配置内容不在本仓库扫描范围内。

13. **运行模式的守卫覆盖「落到 mock」与「prod/mock 混用」两个方向**（P0-57 及第 74 轮公共守卫，2026-09-26）
    → 默认值改为 `prod`、部署清单显式注入；`ProfileActivationContractTest`（6 例）锁定配置与依赖/扫描边界，`ProductionProfileGuard`（3 例）拒绝 `prod,mock` 同时激活。
    但**仍未实现**「`prod` 下 `NACOS_ADDR` / `REDIS_HOST` 等关键地址未显式注入即拒绝启动」的门禁：
    未注入时服务仍会启动（只是连不上）。承第 11 条，验收记录里**不要**把它写成「已有」。
    另外 `k8s/configmap.yaml` 的 `AWS_LWA_ENDPOINT` 死键已于第 64 轮清理；
    当前部署键统一为 `SPAPI_LWA_ENDPOINT_OVERRIDE`，避免制造「LWA 端点已配置」的错觉。
14. **默认改 prod 会改变本地开发体验，且这是有意的** → 原先「什么都不设就能跑样例数据」不再成立；
    离线开发 / 演示必须显式 `SPRING_PROFILES_ACTIVE=mock`。若有人反馈「起来就报缺凭证」，
    先确认是不是把演示环境当成了未配置的实例，而不是回退本修复。

15. **LWA 分类与凭证库 fail-closed 仍不是联调证据**（第 63 轮）→ 进程内契约证明异常码、
    脱敏、缓存/持久化顺序和部署键集合；没有真实 LWA 凭证、真实 DB 故障注入或 K8s 实集群验证。
    凭证到位当天仍须执行 A5，并单独验证 400/401/403/429/5xx、token 刷新及生产探针行为。

### 7.12 第 64 轮复跑（A6 路径误判纠正）

命令（实测，2026-09-25 12:47-12:50 +08:00）：

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.cache\codex-tools\jdk-17.0.20.1+1"
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp"
$m = "$env:USERPROFILE\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd"
& $m -B -ntp -pl 'amz-service/amz-service-spapi' -am `
  '-Dtest=DeploymentManifestContractTest,PlaceholderCoverageContractTest,ConnectorControllerGuardTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
python tools/connector-acceptance/acceptance_runner.py --selftest
& $m -B -ntp -pl 'amz-service/amz-service-spapi' -am test
& $m -B -ntp test
```

桩端到端另以 `fake-service.py --connectors-ok` 启动，runner 使用默认 `/spapi/connectors`、`--allow-stub`，仅执行 `finances` 以验证 A6 路径。

| 项 | 实测结果 |
|---|---|
| 根因 | runner 默认 `/api/connectors` 与服务直连映射 `/spapi/connectors` 不一致；旧记录中的 `A6 E0` 是验收工具误判，不是能力清单端点未实现。 |
| 修复 | 网关增加 `/api/connectors/** -> /spapi/connectors/**` 别名；runner 默认改为直连路径；桩支持双路径且默认 404；新增 2 条部署契约守卫。 |
| 定向 | `DeploymentManifestContractTest` **8/8**、`PlaceholderCoverageContractTest` **2/2**、`ConnectorControllerGuardTest` **5/5** = **15 例 / 0F / 0E / 0S** |
| runner 自检 | **38/38 全绿**，退出码 0，未连接任何服务；两个 Python 脚本 `py_compile` 通过 |
| 桩 A6 | runner 退出码 1（其余 A1/A3/A4/A5/A7/A8 仍缺），**A6 = E3 / pass=true**，证据为 `{"connector":"spapi","enabled":true,"evidenceLevel":"E4"}` |
| SP-API 模块 | **280 例 / 0F / 0E / 2S**，`BUILD SUCCESS` |
| 全仓 | 19 模块全部 `SUCCESS`；Surefire XML 汇总 **767 例 / 0F / 0E / 2S** |
| 未证明 | 网关别名未在真实 Nacos + Gateway 运行期验证；runner 仍未对真实 `amz-service-spapi` 进程执行；前端未接线；A7 已在第 65 轮实现，但真实 429/5xx 重放仍未联调。 |
| 证据等级 | 网关/runner 契约断言 **E1**，桩端到端 **E2**；没有真实凭证或生产联调，**不得写成 E4/E5**。 |


### 7.13 第 65 轮复跑（SP-API Outbox / DLQ / 显式重放）

本轮把 A7 从 E0 推进到可执行契约，但**没有把它写成真实 E4**。核心结果：

- 新增 `V2__spapi_call_outbox.sql`、`V3__spapi_outbox_replay_metadata.sql`；
- `SpApiGateway` 在网络请求前创建 Outbox，状态为 `PENDING / SUCCEEDED / FAILED / REPLAYING / REPLAYED / DLQ`；
- 请求体/响应体 AES-256-GCM 加密，错误文本脱敏；
- 429、5xx、传输失败可重试；401/403 与其他不可重试 4xx 进入 DLQ；
- 自动调度只允许 `GET/HEAD`；DLQ 只能人工重放；
- `POST /spapi/connectors/outbox/{id}/replay` 要求 `OPERATOR/ADMIN` 并校验店铺；
- 原子领取 `FAILED/DLQ -> REPLAYING`，多实例不重复重放。

桩端到端报告：`target/connector-acceptance-a7/connector-acceptance-spapi-20260925133301.json`。

```text
A6 = E3 / pass=true
A7 = E2 / pass=false
A7 blocker: 被测进程自声明 stub=true，证据等级封顶 E2
```

报告 SHA-256：`595ae504901ffbb2d701a159b03936afdf61ce9706019dee605a0d8dbdb60ba1`。

**证据边界**：这是 E2 桩回放；真实 429/5xx 重试、传输中断恢复、生产数据库并发领取、告警和人工处置仍未验证。

### 7.14 第 66 轮复跑（外部信任边界 fail-closed + 写操作 RBAC）

新增 `GlobalAuthWebMvcIntegrationTest` 5 例，测试真实 Spring MVC 请求链：

1. 缺少 token → HTTP 401；
2. VIEWER 调 `@RequireRole({"OPERATOR","ADMIN"})` 写端点 → `Result.code=400`；
3. OPERATOR + 同店 → 控制器可达并返回成功；
4. 已认证但空 shops 调 `@ShopScoped` → `Result.code=400`；
5. 跨店铺调 `@ShopScoped` → `Result.code=400`。

变异验证：临时移除测试上下文 `@EnableAspectJAutoProxy` 后，上述第 2/4/5 例按预期失败（`code=200`），恢复后全绿。这证明测试覆盖了 AOP 接线，不是只断言源码或直接调用 Controller。

命令（实测，2026-09-25 13:51–13:52 +08:00）：

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.cache\codex-tools\jdk-17.0.20.1+1"
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp"
$m = "$env:USERPROFILE\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd"
& $m -B -ntp -pl amz-common -Dtest=GlobalAuthWebMvcIntegrationTest test
& $m -B -ntp test
python -m py_compile tools/connector-acceptance/acceptance_runner.py
python -m py_compile tools/connector-acceptance/fake-service.py
python tools/connector-acceptance/acceptance_runner.py --selftest
```

| 项 | 新鲜实测 |
|---|---|
| `GlobalAuthWebMvcIntegrationTest` | **5 / 0F / 0E / 0S**，BUILD SUCCESS |
| 全仓 | 19 模块 `BUILD SUCCESS`；Surefire 汇总 **797 / 0F / 0E / 2S** |
| `amz-common` | **70 / 0F / 0E / 0S** |
| SP-API | **303 / 0F / 0E / 2S**；2 个 skip 为真实网络/凭证 `SpApiIntegrationTest` |
| runner | 两个 Python 脚本 `py_compile` 通过；`--selftest` **55 PASS**，退出码 0 |

**仍未证明**：没有真实 Amazon 凭据、沙箱/生产联调、真实 429/5xx 重放、真实 Nacos/Gateway/DB/Redis 故障注入；大量旧 `isShopAllowed` 调用点仍需按信任域逐个审计。当前最高证据仍受 E2/E3 边界限制。

### 7.15 第 67 轮复跑（首次部署 bootstrap 与 API-Ready 收口）

本轮解决“现在没有真实 API，但以后拿到凭据必须能直接导入并联调”的部署缺口：

1. `bootstrap` 是独立一次性 profile，不与 `prod`/`mock` 同时激活；导入进程不启动 Web/调度。
2. 凭证文件支持单对象或数组，整批先校验，再 AES-256-GCM 加密写入 `amz_shop_credential`；任一条失败则整批拒绝。
3. Compose 使用 `docker-compose.bootstrap.yml`，K8s 使用 `k8s/jobs/amz-service-spapi-credential-bootstrap.yaml`；凭证走只读 Secret/临时文件，导入后删除。
4. `prod` 实例仍要求至少一条结构完整的店铺凭证，缺凭证或凭证库异常时拒绝启动。
5. 示例文件 `docs/examples/spapi-credentials.example.json` 只含占位符；完整步骤见 `first-deploy-bootstrap-runbook.md`。

命令（实测，2026-09-25 14:23–14:25 +08:00）：

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.cache\codex-tools\jdk-17.0.20.1+1"
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp"
$m = "$env:USERPROFILE\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd"
& $m -B -ntp -pl amz-service/amz-service-spapi -am `
  '-Dtest=ControllerFailureContractTest,BootstrapDeploymentContractTest,PlaceholderCoverageContractTest,DeploymentManifestContractTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
& $m -B -ntp -pl amz-service/amz-service-spapi -am test
& $m -B -ntp test
python -m py_compile tools/connector-acceptance/acceptance_runner.py
python -m py_compile tools/connector-acceptance/fake-service.py
python tools/connector-acceptance/acceptance_runner.py --selftest
```

| 项 | 新鲜实测 |
|---|---|
| controller 失败出口 | 8 个 controller、49 个 `Result.failure(...)`，逐语句审查均携带本地或上游结构化错误；`ControllerFailureContractTest` 基线 48 → 49 |
| 定向契约 | **13 / 0F / 0E / 0S**，`BUILD SUCCESS` |
| SP-API | **327 / 0F / 0E / 2S**；2 个 skip 为 `SpApiIntegrationTest` |
| 全仓 | 19 模块 `BUILD SUCCESS`；16 个有测试模块 / 122 个测试类，Surefire **829 / 0F / 0E / 2S** |
| runner | 两个 Python 脚本 `py_compile` 通过；`--selftest` **55 PASS**，退出码 0 |

**证据边界**：本轮证明凭证可安全导入、配置可部署、失败可结构化并 fail-closed；没有真实 LWA/SP-API 请求，不能宣称 Amazon 已授权、角色/订阅已开通、字段契约已匹配或生产链路已联调。凭据到位后仍须执行 A5 及真实 429/5xx 重放。

### 7.16 第 68 轮复跑（Agent 身份边界与 SSE 上下文传播）

本轮把 Agent 的身份来源从可伪造查询参数收口到 JWT 认证上下文，并验证异步 worker 不丢用户/店铺上下文：

1. `GET /ai/chat-stream` 不再接受 `userId`；缺认证身份返回 401。
2. `AgentChatStreamService` 在请求线程捕获 userId/role/shops/shopId 快照，worker 绑定后执行，`finally` 清理 ThreadLocal。
3. 操作类 Agent 工具在 userId 缺失或角色非 OPERATOR/ADMIN 时拒绝，不再因 `role=null` 放行。
4. Agent 记忆接口改用当前认证用户；跨用户读取仅 ADMIN 放行，更新时忽略客户端伪造的主键与 userId。
5. 前端不再把 localStorage 中的 `user_id` 发送给 SSE 端点。

命令（实测，2026-09-25 14:36–14:39 +08:00）：

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.cache\codex-tools\jdk-17.0.20.1+1"
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp"
$m = "$env:USERPROFILE\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd"
& $m -B -ntp -pl amz-service/amz-service-ai -am `
  '-Dtest=ToolPermissionTest,AgentChatStreamServiceTest,AgentSseControllerTest,AgentMemoryControllerTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
& $m -B -ntp -pl amz-service/amz-service-ai -am test
& $m -B -ntp test
Push-Location amz-frontend
npm.cmd run test:run -- src/__tests__/AgentChat.test.ts
Pop-Location
```

| 项 | 新鲜实测 |
|---|---|
| Agent 定向回归 | **16 / 0F / 0E / 0S**，`BUILD SUCCESS` |
| AI 模块 | **98 / 0F / 0E / 0S** |
| 全仓 | 19 模块 `BUILD SUCCESS`；Surefire XML 汇总 **124 个测试类 / 838 / 0F / 0E / 2S** |
| 前端 AgentChat | **10 / 10 PASS**；先以 `user_id=999` 残留验证红灯，再验证 URL 不含 `userId=` |
| 跳过项 | 仍为 `SpApiIntegrationTest#testLwaTokenRefresh` 与 `#testOrdersApiCall`，需真实网络/凭证 |

**仍未证明**：没有真实 LWA/SP-API 请求、真实 429/5xx 重放、真实 Nacos/Gateway/DB/Redis 故障注入；`UserContext.isShopAllowed` 旧调用点和 `ListingCopyService` 等异步外部写链路仍需逐域审计。当前证据最高仍为 E2/E3，不能据本轮宣称生产可用或真实 Amazon 联调完成。

### 7.17 第 69 轮复跑（字段契约、财务漏记与 1688 失败关闭）

本轮不增加真实平台调用，只把“有凭证即可开始联调”所需的响应语义进一步收紧：

1. `FinancialEventParser` 按官方 `financesV0.json` 使用 `CurrencyAmount`，旧 `Amount` 夹具必须被拒绝。
2. Reports `getReport` 对 `processingStatus/reportId/reportType/createdTime` 四个必填字段失败关闭。
3. FinancialEvents 官方共有 34 个 `*EventList`，当前仅实现 3 个；其余 31 个非空时拒绝返回部分总账。
4. `Alibaba1688RealClient.closeOrder` 只在平台明确返回 `result.success=false` 时返回业务失败；缺失/非布尔 `success`、解析异常和传输异常均抛出。
5. `ProcurementServiceImpl.cancelPurchaseOrder` 遇到业务拒绝时不迁移本地状态；远程故障则抛出，允许上层重试和告警。

命令（实测，2026-09-25 17:43–17:47 +08:00）：

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.cache\codex-tools\jdk-17.0.20.1+1"
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp"
$m = "$env:USERPROFILE\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd"
& $m -B -ntp -pl amz-service/amz-service-procurement -am `
  '-Dtest=Alibaba1688CloseOrderSemanticsTest,ProcurementServiceImplTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
& $m -B -ntp -pl amz-service/amz-service-spapi -am test
& $m -B -ntp -pl amz-service/amz-service-multiplatform,amz-service/amz-service-procurement,amz-service/amz-service-finance,amz-service/amz-service-logistics -am test
& $m -B -ntp test
Push-Location amz-frontend
npm.cmd run test:run
npm.cmd run build
Pop-Location
python tools/synthetic-data/snapshot_schema.py
python tools/synthetic-data/snapshot_schema.py --check
```

| 项 | 新鲜实测 |
|---|---|
| 1688 + 采购服务 | **21 / 0F / 0E / 0S**，`BUILD SUCCESS` |
| SP-API | **338 / 0F / 0E / 2S**，`BUILD SUCCESS` |
| 相关模块 | procurement / logistics / finance / multiplatform 全部 `BUILD SUCCESS` |
| 全仓 | 19/19 模块 `BUILD SUCCESS`；Surefire XML **142 份报告 / 935 / 0F / 0E / 2S**（口径已被 7.18 修正：混入陈旧 IT 报告 1 例，本轮全仓无留存日志；以 7.18 的 **141 / 934 / 0F / 7E / 2S** 为准） |
| 前端 | Vitest **17 文件 / 138 / 0F**；Vue 生产构建成功 |
| Schema | `--check` 通过：109 表 / 14 数据库；仍为 `column_conflicts=5`、`duplicates=1`、`drifted=1` |
| 跳过项 | 仍为需要真实网络/凭证的 `SpApiIntegrationTest` 两例 |

**证据边界**：这些结果证明字段名、必填字段、未覆盖事件和远程取消异常路径的本地契约更严格，不能证明真实 Amazon、金蝶或 1688 响应与本地契约一致。真实 LWA/OAuth、卖家授权、region/marketplace、401/403/404/429、预签名上传下载、金蝶 `GL_VOUCHER/FBillNo` 权限与并发查重、1688 签名和沙箱仍未联调；最高证据仍为 **E2/E3**。

### 7.18 第 70 轮复跑：口径修正与环境阻塞识别（2026-09-26）

复跑命令（PowerShell）：

```powershell
$env:JAVA_HOME = 'C:\Users\Administrator\.cache\codex-tools\jdk-17.0.20.1+1'
$mvn = 'C:\Users\Administrator\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd'
& $mvn test -fae
& $mvn -pl amz-service/amz-service-product -am '-Dsurefire.failIfNoSpecifiedTests=false' -Dtest=KeepaRealClientTest test
```

| 项 | 新鲜实测 |
|---|---|
| 定向（Keepa） | 修复后 **3 / 0F / 0E / 0S**，`BUILD SUCCESS` |
| 全仓（`-fae`） | 18/19 模块 `SUCCESS`；`amz-service-ad` `FAILURE`；新鲜 Surefire XML **141 份 / 934 / 0F / 7E / 2S**（本机日志 `.mvn-round70-rerun.log` 命中 `.gitignore` 的 `*.log`、**不入库**，不能作为可交付证据） |
| 非环境失败 | 非 loopback 失败/错误 **0** |
| 跳过项 | `SpApiIntegrationTest` 两例（需真实网络/凭证） |
| 陈旧报告 | `AdMigrationMySqlIT.xml`（1 例，16:28，需 `AD_MYSQL_IT_URL`，`mvn test` 默认不运行）不得计入本轮口径 |
| 前端 | Vitest **17 文件 / 138 / 0F**；`vue-tsc && vite build` 成功 |
| Schema | `snapshot_schema.py --check` 通过：109 表 / 14 数据库；仍为 `column_conflicts=5`、`duplicates=1`、`drifted=1` |

**证据边界**：`AdvertisingApiRealClientContractTest` 的 7 个 error 已用最小 JDK 复现证明为执行环境禁用 selector/loopback 导致（`Pipe.open()` / `Selector.open()`），不代表 Ads v3 契约断言失败；该测试仍需在正常 CI 环境重跑取证。整体证据仍为 **E2/E3**，不是 E4/E5，不能据此宣称生产可用。

### 7.19 第 74 轮复跑（prod/mock 公共启动守卫与绕过契约）

本轮补上“`prod` 与 `mock` 同时激活”这一条公共启动防线。离线演示仍可使用单独的 `mock`，但不得与 `prod` 混用。

命令（实测，2026-09-26 12:27–12:29 +08:00）：

```powershell
$env:JAVA_HOME = 'C:\Users\Administrator\.cache\codex-tools\jdk-17.0.20.1+1'
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp'
$m = 'C:\Users\Administrator\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd'
& $m -B -ntp -pl amz-common test '-Dtest=ProfileActivationContractTest,ProductionProfileGuardTest' -DfailIfNoTests=false
& $m -B -ntp -pl amz-common test
& $m -B -ntp test
```

| 项 | 新鲜实测 |
|---|---|
| 公共守卫 | `ProductionProfileGuardTest` **3 / 0F / 0E / 0S**：`prod,mock` 拒绝，仅 `prod` 允许，仅 `mock` 允许 |
| Profile 契约 | `ProfileActivationContractTest` **6 / 0F / 0E / 0S**：新增“含 mock 客户端的模块必须依赖 `amz-common` 且应用类位于 `com.amz` 基础包”的绕过检查 |
| `amz-common` | **103 / 0F / 0E / 0S**，`BUILD SUCCESS` |
| 全仓 | 19/19 Reactor `BUILD SUCCESS`；按本轮时间过滤 Surefire XML **158 份 / 1035 / 0F / 0E / 2S**；2 个 skip 仍为 `SpApiIntegrationTest` |
| 证据边界 | 本轮是 **E2/E3** 本地单测与静态契约，没有启动真实服务进程或容器；不能替代真实 Amazon/LWA/SP-API、广告、Keepa、17TRACK、金蝶、1688、Temu/TikTok/Shein 联调，也不能据此宣称生产可用 |
### 7.20 第 75 轮复跑（生产中间件密码 fail-closed，P0-58）

本轮把生产环境中间件密码从“只告警”收紧为“错误配置拒绝启动”。该门禁不生成模拟数据，也不改变真实/模拟客户端的 profile 切换；它只阻止服务带着空密码或 `.env.example` 占位密码进入运行期。

命令（实测，2026-09-26 12:36–12:38 +08:00）：

```powershell
$env:JAVA_HOME = 'C:\Users\Administrator\.cache\codex-tools\jdk-17.0.20.1+1'
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp'
$m = 'C:\Users\Administrator\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd'
& $m -B -ntp -pl amz-common -Dtest=DataSourceValidatorTest test
& $m -B -ntp -pl amz-common test
& $m -B -ntp test
```

| 项 | 新鲜实测 |
|---|---|
| TDD RED | 实现前 `DataSourceValidatorTest` 7 例中 4 个占位密码场景失败，确认 `prod` 下占位密码未被阻断 |
| 定向 GREEN | `DataSourceValidatorTest` **7 / 0F / 0E / 0S**，`BUILD SUCCESS` |
| `amz-common` | **110 / 0F / 0E / 0S**，`BUILD SUCCESS` |
| 全仓 | 19/19 Reactor `BUILD SUCCESS`；新鲜 Surefire XML **159 份 / 1042 / 0F / 0E / 2S** |
| 跳过项 | `SpApiIntegrationTest` 两例（需真实网络/凭证） |
| 失败关闭范围 | `prod` 下 DB（含 master/slave）、Redis、RabbitMQ、MongoDB 的已配置空密码与已知占位前缀 |
| 非生产语义 | `local`/`mock` 下空密码或占位密码只告警，便于离线开发；`prod,mock` 仍由 `ProductionProfileGuard` 在更早阶段拒绝 |
| 未配置属性 | 返回 `null` 时跳过，不对服务不使用的中间件误报 |
| 证据边界 | **E2/E3**：单测与静态配置语义；未启动真实 Compose/K8s，未验证 Secret/ConfigMap 注入后的容器退出码与日志，也未取得任何外部 API E4/E5 联调证据 |

### 7.21 第 76 轮复跑（前端连接器状态中心）

本轮新增只读连接器状态页，用于在凭证到位前后区分“代码具备对接能力”“模拟自检”“真实连通证据”三种状态。页面本身不改变后端连接器，也不替代 A5 真实联调。

命令（PowerShell，2026-09-26）：

```powershell
Push-Location amz-frontend
npm.cmd run test:run -- src/__tests__/ConnectorCenter.test.ts
npm.cmd run test:run -- src/__tests__/router.test.ts
npm.cmd run test:run -- src/__tests__/AppSidebar.test.ts
npm.cmd run test:run
npm.cmd run build
Pop-Location
```

| 项 | 新鲜实测 |
|---|---|
| TDD RED | `ConnectorCenter.vue` 尚不存在时测试无法解析；`/connectors` 路由未注册时落到 `NotFound`；“连接器状态”侧边栏入口不存在时找不到文本。 |
| 定向 GREEN | `ConnectorCenter` **4/4**；`router` **7/7**；`AppSidebar` **1/1**。 |
| 前端全量 | Vitest **19 个文件 / 144 例 / 0F**；既有测试中的降级 stderr 不是失败。 |
| 生产构建 | `npm run build` 成功，Vite **156 modules transformed**；连接器页面生成独立懒加载 JS/CSS。 |
| 页面入口 | 登录后访问 `/connectors`；页面展示后端能力清单、profile、真实/模拟客户端、凭证来源和数量、A1–A8 证据、已实现/未实现操作、最近自检。 |
| 自检行为 | 仅对 `spapi` 且当前有 `shopId` 时显示“运行只读自检”；请求为 `POST /api/connectors/spapi/self-test`，请求体 `{ shopId }`。无凭证时显示无凭证/失败，不显示成功。 |
| 防伪行为 | 后端 `displayText` 声称“已接通/API-Ready”但 `reachable=false` 时显示“状态异常：证据等级与联通声明冲突”；模拟模式成功显示“离线自检完成”，并明确不代表真实 API 连通。 |
| 证据边界 | 前端测试使用 API mock；没有真实浏览器 + 网关 + 后端 + DB 的 E2E，也没有真实 Amazon/LWA/SP-API 或其他平台请求。整体仍是 **E2/E3**，不是 E4/E5。 |
| 未证明 | 未验证真实凭证导入后的自检成功、真实权限/订阅/角色、429/5xx、字段契约、生产观测和用户端权限边界。 |
| 提交状态 | 改动仍在工作区，未提交、未推送；禁止 `git add .`，既有 staged 删除项保持不变。 |

### 7.22 第 79 轮复跑（RDT API-Ready 与订单 PII 开关）

本轮补齐 Tokens API / RDT 的离线 API-Ready 底座，并把订单 PII 同步放到显式开关之后。它解决的是“代码是否具备合规接入路径”，不是“拿到凭证后已经可以直接生产”。

| 项 | 新鲜实测 |
|---|---|
| 定向部署契约 | `DeploymentManifestContractTest` 等 **8 / 0F / 0E / 0S** |
| SP-API 全模块 | **390 / 0F / 0E / 2S**，`BUILD SUCCESS` |
| 全仓 | 19/19 Reactor `BUILD SUCCESS`；新鲜 Surefire XML **168 份 / 1096 / 0F / 0E / 2S** |
| RDT/订单 PII 组合定向 | **42 / 0F / 0E / 0S** |
| 跳过项 | `SpApiIntegrationTest` 两例，仍需真实网络/凭证，不能按通过计算 |
| 默认开关 | `SPAPI_RESTRICTED_DATA_ENABLED=false`、`SPAPI_ORDER_PII_SYNC_ENABLED=false` |
| 已证明（离线） | 官方 Tokens 快照/hash；资源数量、method/path/dataElements 校验；RDT 缓存隔离与过期；401/403 精确失效；RDT 失败时业务请求数为 0；Outbox 不保存 RDT；订单同步开关关闭时保持普通 LWA 行为 |
| 未证明（真实） | Amazon 应用审批、卖家授权、RDT/SPDS/角色权限、真实 Tokens/Orders PII 请求、401/403/429 行为、字段契约、沙箱和生产验收 |
| 证据边界 | **E2/E3**；无真实凭证或沙箱请求，A5 仍为 E0，不能写“已接通”或“生产可用” |
| 提交状态 | 改动仍在工作区，未提交、未推送；6 个既有 staged 删除项保持不变 |
### 7.23 第 80 轮复盘（引用键收敛 + 合成数据覆盖率补齐，2026-09-27）

本轮只解决“schema 与合成数据自洽”，**不解决**真实凭证验收：A5 仍为 E0，验证边界仍为 E2/E3。

| 项 | 新鲜实测 |
|---|---|
| 全仓 | `mvn -o test -fae` 19/19 Reactor `BUILD SUCCESS`；新鲜 Surefire XML **191 份 / 1263 / 0F / 0E / 2S** |
| Schema | `snapshot_schema.py --check` 通过：**112 表 / 14 数据库**，`duplicates=0`、`drifted=0`、`column_conflicts=0` |
| 引用键类型 | `id types OK (0 columns reference a parent id with a different DDL type)`；7 处不一致 → 0 |
| 合成数据 | ci 23484 行 / demo 205734 行，`tables_with_rows=112/112`，`unique_key_repairs=0`；`--tier ci` 与 `--tier demo` 均 PASS |
| 行级放大复核 | `--tier demo --orders 2000 --shops 9 --marketplaces 3`：65590 行，引用违规 0，PASS |
| 本轮先修复的回归 | 首先发现 `snapshot_schema.py --check` **FAIL（DDL drift）**：4 个迁移 SQL 的回滚指针 header 在快照重建之后被改过；已重建快照并复验 |

收敛内容（**纠正了上一轮的错误前提**：7 处并非全是类型缺陷）：

- **3 处加宽为 BIGINT**：`amz_order.product_id`、`amz_order.user_id`、`amz_ad_search_term.keyword_id`。
- **4 处保留 VARCHAR 并重命名**（存的是平台/外部订单号，改类型会使真实结算数据无法落库）：`amz_payment_collection`、`amz_settlement_detail`、`amz_shipment_routing` 的 `order_id→amazon_order_id`；`amz_platform_message.order_id→platform_order_no`。
- 同步改了 finance/order/multiplatform/ad 四个模块的 Java 字段名与类型（含测试），已全仓编译与测试通过。

| 未闭环 / 风险 | 说明 |
|---|---|
| `amz_order.uk_amazon_order` | 唯一键只含 `amazon_order_id`，缺 `shop_id`/`marketplace`；跨店/跨站点撞键风险未消除 |
| 反向 SQL | 仅在“零真实数据”前提下成立，未在真实 MySQL 上验证过 |
| Ads `keywordId` String→Long | 编译与单测通过，但无真实 Ads/SP-API 数据验证 Jackson/MyBatis 绑定 |
| `verify.py` 规模固定 | 结构/引用校验默认跑在 `orders=200/shops=1`，**不随 `--tier` 放大**；大数据集只做 manifest 逐文件 hash，需显式传 `--orders/--shops/--marketplaces` 才算行级校验 |
| 提交状态 | 改动仍在工作区，未提交、未推送；`git diff --cached` 为空，无 staged 删除项 |

#### 7.23.1 本轮补漏：门禁假阴性与生成器静默降级

7.23 写完后继续核对，发现那句 `id types OK (0)` 当时**并不完整**：

| 问题 | 证据 |
|---|---|
| 门禁假阴性 | `verify.py` 的 `FK_TARGETS` 缺 `coupon_id`、`platform_account_id`（生成器 14 个引用池，门禁只认 13 个），这两列从未被 id-types 检查 |
| 第 4 处真实类型缻陷 | 补齐后立刻报出 `amz_order.amz_order.coupon_id is INT() but amz_product.amz_coupon.id is BIGINT()` |
| 假引用 | `references FAIL: amz_order.amz_order.coupon_id contains 877000010 which is not in amz_product.amz_coupon.id (12 values)` |
| 根因 | `generate.py` 的 `fit()` 对超出 INT 上界的值执行 `abs(n) % 2000000000 + 1`，把 `900000000877000000` 变成 `877000010`——一个“看起来合理”的假引用 |

修复：

- 新增 `V3__order_coupon_key_type.sql`：`amz_order.coupon_id` INT → BIGINT；Java `Order.couponId` Integer → Long。
- `verify.py` 的 `FK_TARGETS` 补齐 `coupon_id` / `platform_account_id`（引用池覆盖 13/14 → 14/14）。
- `generate.py` 的 `fit()`：对 `REF_COLUMNS` 内的引用列**不再夹取，直接抛 `ValueError`**；非引用列保持原行为。实测影响面只有 `amz_order.coupon_id` 一列（1000 行），其余 14 列被夹取的都是 TINYINT 布尔/小枚举。
- 重新生成 ci/demo 并复验：ci 23484 行、demo 205734 行，`references OK (221 pooled columns, 15 universes, 0 violations)`、`id types OK (0)`；`--orders 4000 --shops 9`（105090 行）同样 0 违规；全仓 `mvn -o test -fae` `BUILD SUCCESS`。

**结论修正**：7.23 里“引用键类型不一致 7 处 → 0”应改为“**8 处 → 0**”——原 7 处（3 处加宽 + 4 处重命名）之外，还有第 4 处真实类型缻陷（coupon_id）是靠修门禁才暴露的。
这也说明：任何“0 违规”的结论都只在门禁覆盖范围内成立，不能当作“整个 schema 没问题”。
### 7.24 第 81 轮复跑（订单身份收敛 V4、订单明细表 V5、门禁规模修复，2026-09-27）

本轮仍然只解决“schema 与合成数据自洽”，**不解决**真实凭证验收：A5 保持 **E0**，验证边界仍为 **E2/E3**。

| 项 | 新鲜实测 |
|---|---|
| Schema | `snapshot_schema.py --check` 通过：**113 表 / 14 数据库**（drift=0，alter_table=22，add_column=14） |
| 合成数据 ci | `verify.py --tier ci --dataset tools\synthetic-data\out\ci`：orders=1000/shops=2/marketplaces=2，113 表 / **25484** 行，结构/引用/标记/确定性/快照均 PASS |
| 合成数据 demo | `verify.py --tier demo --dataset tools\synthetic-data\out\demo`：orders=10000/shops=3/marketplaces=3，113 表 / **225734** 行，PASS |
| 行级规模上限 | `verify.py --tier staging --max-orders=20000`：20000 订单 / 10 店铺 / 5 站点，**487678** 行，退出码 0 |
| 全仓 | `mvn -o test -fae` 19/19 Reactor `BUILD SUCCESS` |

命令：

```powershell
python tools\synthetic-data\verify.py --tier ci --dataset tools\synthetic-data\out\ci
python tools\synthetic-data\verify.py --tier demo --dataset tools\synthetic-data\out\demo
python tools\synthetic-data\snapshot_schema.py --check
mvn -o test -fae
```

本轮改动与结论：

1. **订单身份收敛（关闭 7.23 的未闭环项 `amz_order.uk_amazon_order`）**：新增迁移 `V4__order_shop_scoped_identity.sql`，把 `amz_order` 的单列唯一键 `uk_amazon_order(amazon_order_id)` 换成 `uk_shop_market_order(shop_id, marketplace_id, amazon_order_id)`，并新增索引 `idx_shop_purchase_date`；`OrderServiceImpl.syncAmazonOrder` 的幂等查重同步带上 shop/marketplace（缺 shopId 时**只告警**，绝不退化成全局判重）。这消除了“跨店同号订单被判重复而静默丢单”的结构性缺陷。
   - 残留：`marketplace_id` 仍可空，MySQL 唯一索引中 NULL 不参与去重；彻底解决要按规范 §3.1 把平台订单与本地购物车订单拆表。
   - 未被“真撞值”验证：生成器仍让 `amz_order_id` 全局唯一，因此新唯一键只证明“建得出来”，未证明“撞键时按预期拒绝”。
2. **订单明细表 V5**：新增 `amz_order_item`（订单行，唯一键 `uk_order_item(shop_id, amazon_order_id, amazon_order_item_id)`）。此前 `amz_order` 只有一组单商品列，结构上装不下多商品订单；同库 `amz_profit_report` 已按 `(shop_id, amazon_order_id, sku)` 建模，多平台侧则用 `items_json` 变通、不可聚合。当前状态：**schema + 合成数据已就位；本轮续做已补齐 Java 写入方**（见 7.24.2）：SP-API `getOrderItems` → 保存消息 → `OrderConsumer` 解析 → `amz_order_item` 先查后写，6 条单测 + 1 条调度器契约测试通过。残留：`product_id` 仍为 NULL（ProductClient 无 SKU 反查接口），`amz_order` 的单商品列仍是重复事实源。
3. **门禁规模修复**：`verify.py` 的校验规模改为“显式参数 > `--dataset` manifest > tier”，新增 `--max-orders=20000` 上限。此前默认固定 `orders=200/shops=1`，大数据集只做逐文件 hash、不做行级事实校验，属于**假阴性**。代价：默认 ci 行级规模从 200 单升到 1000 单，单次耗时上升。
   - 新发现：`snapshot_schema.py` / `verify.py` / `generate.py` **未被 `.github/workflows/ci.yml` 调用**，合成数据门禁目前只在本机手动执行；是否接入 CI（以及 CI 时长代价）待决策。
4. **Flyway 基线契约**：`FlywayBaselineContractTest` 期望表数 112 → 113（V5 新增表触发）。这是门禁按预期生效，不是放宽门禁。

#### 7.24.2 本轮续做：V5 写入方落地（订单明细真正入库）

7.24 写 V5 时如实记录了“尚无 Java 写入方”——那确实是当时的状态，但不应留着不补。本会话补齐了完整链路：

| 环节 | 改动 |
|---|---|
| 取数（spapi） | `OrderSyncScheduler.publishSaveMessage` 原先把 `orderItems` 固定写成 `List.of()`（注释写着“由 order 服务后续补全”，实际上没有任何地方补全）；改为把 `getOrderItems` 的真实结果压成 camelCase Map 随保存消息下发 |
| 解析（order） | `OrderConsumer.buildOrderSyncDto` 新增 `orderItems` 解析；金额按字符串解析为 `BigDecimal`，解析失败保留 `null`（禁止静默填 0——0 和“没有”在财务上是两件事） |
| 落库（order） | 新增 `OrderItem` 实体 / `OrderItemMapper`；`OrderServiceImpl.persistOrderItems` 按 `uk_order_item(shop_id, amazon_order_id, amazon_order_item_id)` 先查后写，存在则 update、不存在则 insert，并捕获并发 `DuplicateKeyException` |

四条关键取舍（全部写进测试，防止被后续“优化”掉）：

- **订单已存在（幂等跳过）时仍然补写明细**：首轮同步若因 orderItems 端点限流/失败没拿到明细，重投时直接 `return` 会让明细**永久缺失且没有任何报错**。
- **缺 `shop_id` 时不写明细**：`shop_id` 是 NOT NULL，用 0 或默认值伪造归属会把明细挂到错误店铺，且不可察觉。
- **`product_id` 保持 NULL**：`ProductClient` 只有按 ID 查询，没有 SKU 反查接口；猜测映射会制造错误的商品关联。
- **不用“先删后插”**：删除窗口内明细会短暂消失，且 delete+insert 会刷新 `update_time`、掩盖真实变更时间，不利于对账。

新鲜实测：

| 项 | 结果 |
|---|---|
| `OrderItemPersistenceTest`（新增） | 6/6 通过：新单全量落库、重复同步走 update、幂等跳过时补写、缺 shopId 不写、缺 itemId 跳过、无明细不写占位行 |
| `OrderSyncSchedulerContractTest`（新增 1 条） | 6/6 通过：保存消息必须携带真实明细（不再固定发空数组），校验 sellerSku / itemPrice / quantity |
| 全仓 | `mvn -o test -fae` 19/19 Reactor `BUILD SUCCESS` |

未验证：整条链路只在 Mockito 层验证，**没有任何真实 MySQL 写入或真实 SP-API 响应**（A5 仍为 E0）；`amz_order_item` 的 DDL 仍只由 schema 快照与合成数据门禁覆盖。

#### 7.24.1 流程事故：执行矩阵被误截断，已从会话日志恢复

必须记录，因为它是一条真实的工程风险，不是笔误：

- **现象**：`docs/superpowers/specs/2026-09-26-production-upgrade-execution-matrix.md` 被本会话写空（0 字节），且它是**未跟踪文件**，`git show HEAD:<path>` 报 “exists on disk, but not in HEAD”，**Git 无法恢复**。
- **根因**：补丁脚本使用 `io.open(p, "w", newline="\\n")`。`open(..., "w")` 会**先截断**文件；随后 `newline` 传入的是两字符串 `\n`（反斜杠 + n）而非换行符，`TextIOWrapper` 初始化抛 `ValueError: illegal newline value`——截断已经发生，内容已经丢失。同一脚本里 `old` 串还混入了杂字符导致 `assert count == 1` 未通过，所以“替换失败”和“文件清空”同时发生。
- **恢复**：从 Codex 会话库（`thread_history_1.sqlite` / `logs_2.sqlite`）提取该文档的历史正文与补丁原文到 `C:\Users\Administrator\rec_items\`，以历史正文为基准重放全部已确认改动，重建为 304 行 / 44208 字节 / LF / 无 BOM，并复跑校验：含 113 表、含 V4/V5 条目、REL-12 已闭环、QA-01 为 113/113。
- **代价与教训**：（a）**未跟踪文件没有版本保护**，重要文档必须先 `git add` 进索引再编辑；（b）所有 `assert` 必须在 `open(..., "w")` **之前**完成；（c）禁止 `newline="\\n"`，需要 LF 时用 `newline=""` 再写 `\n`；（d）写文件前先备份，改完立刻 `git add`。

| 未闭环 / 风险 | 说明 |
|---|---|
| V4 残留 | `marketplace_id` 可空 → MySQL 唯一索引 NULL 不去重；彻底解需按规范 §3.1 拆表 |
| V5 残留 | 写入方已就位（见 7.24.2）；`product_id` 仍为 NULL（ProductClient 无 SKU 反查接口），`amz_order` 的单商品列仍是重复事实源、尚未收敛到明细表 |
| 唯一键未真撞值验证 | 生成器 `amazon_order_id` 仍全局唯一，未受控产出跨店同号来证明新唯一键按预期拒绝 |
| 反向 SQL | V4/V5 的回滚语句为静态推演，未在真实 MySQL 执行（E2/E3） |
| Ads `keywordId` String→Long | 仅编译 + 单测通过，无真实绑定证据 |
| A5 | 仍为 E0：没有任何真实凭证、沙箱或生产请求证据 |
| 合成数据门禁未入 CI | 三工具未被 `ci.yml` 调用，是否接入及时长代价待决策 |
| 提交状态 | 本轮改动仍在工作区，未提交、未推送 |

### 7.25 第 83 轮：分层连通性自检（SpApiConnectivityPreflight）

**背景与判断**：用户口径是「暂时没有 API，但要有对接能力——有 API 就能直接用」。到本轮为止，
仓库已有「凭证结构校验」（`ShopCredentialValidator` + `ConnectorStartupCheck`）和
「一次性 getOrders 自检」（`ConnectorController#selfTest`），但两者都**不能**回答凭证到位那天
最关键的问题：*Amazon 到底认不认这套凭证*。更糟的是 selfTest 失败时只给一个 outcomeCode，
「refresh_token 失效」和「应用没被卖家授权」都表现为 403/401，运维无从下手、只能靠猜。
本轮补上**分层定界**能力，把「能不能连上」从业务链路里独立出来。

**新增生产代码**（均在 `amz-service/amz-service-spapi`）：

| 文件 | 作用 |
|---|---|
| `com/amz/preflight/PreflightStageStatus.java` | 封闭三态 PASS / FAIL / SKIP；SKIP 明确表示「没跑」，不等于通过 |
| `com/amz/preflight/PreflightStage.java` | 单阶段结果（耗时、错误码、平台状态码、remediation、脱敏 detail） |
| `com/amz/preflight/PreflightReport.java` | 三段报告：shopId / marketplace / region / **实际出站主机** / endpointOverridden / ready / nextAction |
| `com/amz/preflight/ConnectivityProbe.java` | 只读探测抽象（让分类逻辑可在零网络下被测） |
| `com/amz/preflight/SellersConnectivityProbe.java` | 生产实现：`sellers.getMarketplaceParticipations`（无 PII、无需 RDT、只读无副作用），`@Profile("!mock")` |
| `com/amz/preflight/SpApiConnectivityPreflight.java` | 闸门本体：凭证 → LWA → READ_API，失败即停，全 fail-closed |
| `com/amz/controller/PreflightController.java` | `POST /spapi/preflight/shop/{shopId}?forceTokenRefresh=...`、`POST /spapi/preflight?forceTokenRefresh=...&limit=...`；仅 OPERATOR/ADMIN，逐店铺 `isShopAllowedStrict`，`limit` 硬上限 50 |

**新增测试**：`com/amz/preflight/SpApiConnectivityPreflightTest.java`（10 条）与 `com/amz/controller/PreflightControllerContractTest.java`（5 条）；后者锁定 POST 方法、角色白名单、店铺授权 fail-closed、参数透传和批量 `limit` 边界。

**关键取舍（已入测，不得回退）**：

1. **READ_API 选 marketplaceParticipations 而不是 getOrders**：自检本身不该比业务更难通过——
   它不需要 PII 权限、不进 RDT 链路、无副作用；getOrders 会把查询参数、限流窗口、RDT 混进判定，
   失败时无法定界。两者是**互补**：本类判「能不能连上」，selfTest 判「业务链路通不通」。
2. **LWA 失败即停，不再打平台**：否则只会刷出一串噪音 401，并把鉴权失败误记成限流或平台故障。
3. **SKIP 一律使 ready=false**：`ready` 要求全部阶段为 PASS，杜绝把「没跑」读成「没问题」。
4. **forceTokenRefresh 参数**：默认复用 token 缓存；刚换过 refresh_token 必须传 true，
   否则旧 token 会让自检给出**假绿灯**（这是本轮最容易被误用的一点）。
5. **端点覆盖（base-url override）只报 `endpointOverridden` 标记**：报告不判断是否合法，
    但明确写出「打的是非官方端点时，全绿也只是 E2 证据，不得写成 E4/E5」。
6. **会出网或失效缓存的接口必须使用 POST**：单店与批量自检都会触发出网，`forceTokenRefresh=true` 还会主动失效 LWA token 缓存，不能伪装成安全幂等的 GET；网关统一公开别名 `/api/preflight/** -> /spapi/preflight/**`，前端开发代理同样转发 `/api/preflight`。

**本轮实测**：

| 命令 | 结果 |
|---|---|
| `mvn -o -pl amz-service/amz-service-spapi -am test -Dtest=SpApiConnectivityPreflightTest` | **10/10 PASS** |
| `mvn -o -pl amz-service/amz-service-spapi -am test`（全模块） | **568 run / 0 failures / 2 skipped**，BUILD SUCCESS |
| `ControllerFailureContractTest` | 守卫按预期拦住：`Result.failure` 出口 55 → **57**（新增两条均带 `ErrorSummary.localError`），已同步更新期望值 |

**仍未闭环 / 风险（不得当作已完成）**：

| 项 | 说明 |
|---|---|
| A5 | **仍为 E0**。本轮全部证据是进程内桩（E2），**没有一次真实 Amazon 请求**；`ready=true` 只表示当前进程用当前凭证打通只读链路 |
| 只读 ≠ 全能力 | 全绿**不**证明 Feeds / Reports / Messaging / RDT / 写入类 operation 可用，这些必须单独验收 |
| 路径契约 | `sellers.getMarketplaceParticipations` 的路径只与**本地快照**一致（`SpApiPathContractTest`），不保证快照本身与线上一致 |
| 缓存失效 | `forceTokenRefresh=true` 只在桩上验证 LWA 缓存被驱逐，**未**验证真实 LWA 端点在短时间重复刷新下是否限流 |
| 批量端点 | `POST /spapi/preflight` 未在真实 Spring 上下文运行过（无上下文测试），`CredentialStore.getActiveShopIds()` 的真实行为未验证 |
| 端点覆盖语义 | `endpointOverridden` 只反映配置，不校验覆盖主机是否真的是沙箱 |
### 7.26 第 85 轮：P1-01 统一分页治理（2026-09-27）

**背景与判断**：原实现多处用硬编码 `LIMIT` 截断结果，却仍返回 HTTP 200 和普通数组。
调用方无法区分「数据刚好这么多」与「更多数据被静默丢弃」。财务对账、采购审批、
利润快照和客服邮件都属于漏一行就可能产生错误决策的场景，因此本轮目标不是把 `LIMIT`
数字调大，而是建立**可继续翻页、可机器判定截断**的统一契约。

**统一分页契约**（`amz-common`）：

| 类型/字段 | 语义 |
|---|---|
| `PageRequest` | `size` 默认 50，硬上限 500；超过上限抛 `InvalidParamException`，不静默收敛 |
| 游标 | keyset 游标；载荷加 `v1:` 版本前缀后 Base64URL 编码；非法游标 fail-closed |
| 单列游标 | 按 `id DESC` 翻页时使用；游标即本页最后一条可见行的 id |
| 复合游标 | 按 `(业务时间, id) DESC` 翻页时使用，避免时间并列导致漏行/重复 |
| 探测行 | 查询 `size + 1` 行，仅多出的 1 行用于判定 `hasMore`，不进入返回数据 |
| `Result.data` | 仍是原来的行数组，老客户端零改造 |
| `Result._page` | `{size, returned, hasMore, truncated, nextCursor, total}`；`total` 未做 COUNT 时为 null |
| 截断消息 | `truncated=true` 时 message 不再是「操作成功」，而是明确要求携带 `nextCursor` 继续翻页 |

**原始 P1-01 五处截断路径**：

| 路径 | 原行为 | 本轮处理 |
|---|---|---|
| `FinanceServiceImpl.listVouchers` | 固定 `LIMIT 500` | `id DESC` keyset + 探测行 + `_page`；前端 `Finance.vue` 已支持「加载更多」 |
| `ProcurementServiceImpl.listPurchaseOrders` | 固定 `LIMIT 500` | `id DESC` keyset + 探测行 + `_page`；截断打 WARN |
| `CustomerEmailServiceImpl.processPendingEmails` | 固定 `LIMIT 1000` 后直接当批次完成 | 查询 1001 行，只处理前 1000 行，返回 `batchSize/batchLimit/hasMorePending`；这是调度批处理，不伪装成游标列表 |
| `ProductServiceImpl.searchProducts` | 两条分支均固定 `LIMIT 20` | 合并为单查询 + `id DESC` keyset + 探测行 + `Result.paged`；当前无 Controller 映射，服务契约先统一 |
| `RealtimeProfitServiceImpl.loadHeadhaulForSku` | 按日期倒序固定 `LIMIT 10`，更旧头程成本被静默丢弃，利润被高估 | 改为近 90 天窗口 + 最多扫描 200 条；触顶打 WARN。该路径是内部成本计算，不是列表 API，因此没有伪造成游标分页 |

**顺带纳入的 P1-02 无界列表**：

| 路径 | 处理 |
|---|---|
| `RealtimeProfitServiceImpl.listAllocations` | 原为无界 `selectList`；改为 `(alloc_date, id)` 复合游标 + 探测行 |
| `CustomerEmailServiceImpl.listEmailTasks` | 原为无界 `selectList`；改为 `id DESC` keyset + 探测行 |
| `RealtimeProfitServiceImpl.listSnapshots` | 原固定 2000 条且与 `PageRequest.MAX_SIZE=500` 冲突；改为 `(stat_time, id)` 复合游标 + 探测行 |
| 利润趋势聚合 | 不绕过 500 硬上限；改用 `TREND_FETCH_LIMIT=MAX_TREND_HOURS=168`，响应显式返回 `trendTruncated` |

**关键取舍（不得回退）**：

1. **不做 `LIMIT n OFFSET m`**：凭证、采购单、邮件等表翻页期间持续写入，OFFSET 会导致重复或漏行。
2. **不引入内部绕过分页上限的入口**：若存在 bypass，`MAX_SIZE=500` 就退化成建议值，无法审计。
3. **不把探测行泄漏给调用方**：`nextCursor` 必须由本页最后一条可见行生成。
4. **非法游标不得回退首页**：否则客户端拿到 HTTP 200 的错误页，比明确 400 更危险。
5. **不把 `total=null` 当 0**：未做 COUNT 是性能取舍，翻页应依赖 `hasMore/nextCursor`。
6. **批处理不等于分页**：`processPendingEmails` 的目标是限制单次处理量，因此保留批处理语义并显式报告积压。
7. **头程成本仍有上限风险**：90 天 + 200 条能阻止原先 10 条的静默漏算，但若 90 天内确实超过 200 条，仍可能少计；触顶 WARN 是当前最小诚实方案，后续应改为按 SKU 精确聚合。

**本轮过程修复**：

- 修复上一轮采购模块改坏的 `ProcurementService.listPurchaseOrders` 签名和 Controller 被覆盖的 `Result` import。
- 补齐采购实现缺失的 `PageRequest/PageResult` import。
- 修复商品分页测试与客服分页测试中「把探测行当成本页最后一行」的错误断言。
- 前端 finance 分页由 `vue-tsc` 捕获过 `MouseEvent` 类型问题，已按生产构建门禁修正。

**本轮验证证据**：

```powershell
mvn -o -pl amz-service/amz-service-customer test -Dtest=CustomerEmailServiceImplTest
mvn -o -pl amz-service/amz-service-product test -Dtest=ProductServiceImplSearchPagingTest
mvn -o test -fae

Push-Location amz-frontend
npm run test:run
npm run build
Pop-Location
```

| 项 | 新鲜实测 |
|---|---|
| customer 定向测试 | `CustomerEmailServiceImplTest` **11/11 PASS** |
| product 定向测试 | `ProductServiceImplSearchPagingTest` **4/4 PASS** |
| 全仓后端 | 19/19 Reactor `BUILD SUCCESS`；包含新增 customer/product 测试 |
| 调用方审计 | 对 `listVouchers/listPurchaseOrders/listSnapshots/listAllocations/searchProducts/listEmailTasks/processPendingEmails` 做 Java/TS/Vue 全仓检索，无遗漏旧签名调用；`SearchServiceImpl.searchProducts` 是同名私有方法，与商品服务无关 |
| 前端 | Vitest **19 个文件 / 148 例 / 0F**；`npm run build` 成功，Vite **156 modules transformed** |
| 证据边界 | 均为 E2/E3 本地单测、编译和静态契约；**没有真实 MySQL 大表压测、真实索引执行计划、真实 Amazon/外部 API 联调** |

**仍未闭环 / 风险（不得当作已完成）**：

| 项 | 说明 |
|---|---|
| P1-02 剩余面 | 库存、出库单、调拨单、物流升级、广告、客服其余列表仍有未纳入本轮的无界路径 |
| 数据库验证 | 未在真实 MySQL 执行 10 倍目标数据量；游标条件所需组合索引尚未通过 `EXPLAIN` 验证 |
| 前端消费 | 当前前端没有采购单、客服邮件任务、利润快照/成本分摊列表页；后端已可分页，但没有页面可验收「加载更多」 |
| 商品搜索 | `searchProducts` 仍无 Controller 端点，本轮只统一服务层契约，不能宣称用户已可访问该功能 |
| 头程精度 | 200 条扫描上限仍可能导致极端分摊量下少计成本，当前仅 WARN，未做精确 SQL 聚合 |
| 真实 API | 与 A5 相同：本轮不产生任何真实 Amazon/LWA/SP-API 证据 |
| 提交状态 | 本轮及此前多轮改动仍在工作区，未提交、未推送；未获得用户提交授权前不得执行 `git add`/commit |
### 7.27 第 86 轮：物流/采购列表游标分页与 AI 有界翻页（2026-09-27）

本轮继续围绕“没有真实 Amazon 凭证，但 API 到位后可直接接入”的目标收口。验证边界仍为 **E2/E3**：完成的是本地契约、桩、单测和生产构建验证；**没有真实 SP-API/LWA 凭证，因此 A5 仍不是 E4/E5，不能宣称已接通或已完成生产部署验收**。

**本轮改动：**

1. 物流服务对外列表统一改为服务端游标分页：`listShipments`、`listQuotes`、`listTransfers`、`listAllocations`、`listDiscrepancies` 均使用 `id DESC` keyset、`LIMIT size + 1` 探测行，`nextCursor` 只取本页最后一条可见记录；非法游标 fail-closed，`total=null` 表示未知，不伪装成 0。
2. 物流看板前端按新契约接入：默认页大小 50，支持 `nextCursor` 加载更多、`truncated` 显式提示、`_page` 缺失时显示“完整性未知”，切换店铺/状态筛选时清空旧游标和旧结果，避免把不同筛选条件的数据拼接。
3. AI 模块的 `LogisticsServiceClient` Feign 契约同步为 `(shopId, status, size, cursor)`；`ErpToolExecutor.trackShipment` 不再只查第一页，而是按货件号进行有界翻页查找：单页 200、最多 10 页。
4. AI 翻页遇到下游失败、`_page` 缺失、`hasMore/truncated=true` 但没有 `nextCursor`、游标重复或达到扫描上限时返回明确错误，不把“没查完”误报为“货件不存在”。
5. 采购模块 FBA 货件列表同步改为 `id DESC` keyset 游标分页：Controller 接受 `size`/`cursor` 并返回 `Result.paged(...)`，Service 使用 `LIMIT size + 1` 探测行，`nextCursor` 仅指向本页最后一条可见记录；既有调用与测试同步更新。

**新鲜验证证据：**

```powershell
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp'
mvn -o -pl amz-service/amz-service-logistics '-Dtest=LogisticsServiceImplPagingTest,LogisticsUpgradeServiceImplPagingTest,LogisticsServiceImplTenantTest,LogisticsUpgradeServiceImplTest' test
mvn -o -pl amz-service/amz-service-procurement '-Dtest=FbaShipmentServiceImplPagingTest,FbaShipmentServiceImplTest,ProcurementControllerGuardTest' test
mvn -o -pl amz-service/amz-service-ai test

Push-Location amz-frontend
npm run test:run
npm run build
Pop-Location
```

| 项 | 新鲜实测 |
|---|---|
| 物流定向测试 | 4 个测试类共 **40/40 PASS**，覆盖 keyset 分页、探测行、非法游标、租户边界和升级服务分页 |
| AI 模块全量测试 | **103/103 PASS**，包含多页货件查找、扫描上限、`_page` 缺失 fail-closed、下游失败不误报“未找到” |
| 采购 FBA 定向测试 | 3 个测试类共 **16/16 PASS**，覆盖探测行、游标、无截断、状态+游标+LIMIT 和 Controller 租户边界 |
| 前端全量测试 | **20 个测试文件 / 158 例 / 0F**，包含物流看板截断提示、加载更多、筛选重置和 `_page` 缺失回归 |
| SP-API 全量回归 | **574 run / 0F / 0E / 2S**，BUILD SUCCESS；2 skipped 为需要真实凭证的集成测试 |
| 前端全量回归 | **20 个测试文件 / 163 例 / 0F**，包含连接器中心 6 例 |
| 前端生产构建 | `vue-tsc && vite build` 成功，Vite 转换 **156 modules** |
| 全仓后端回归 | `mvn -o test -fae`：19/19 Reactor `BUILD SUCCESS` |
| 证据边界 | 全部为本地单测、Mock/桩、编译和静态契约；**没有真实 Amazon 凭证、真实沙箱或生产联调、真实 MySQL 大表压测、真实 `EXPLAIN` 索引证据** |

**本轮未闭环风险：**

| 项 | 说明 |
|---|---|
| 真实 API | 没有凭证，无法产生真实 200/401/403/404/429 记录，也无法验证 Amazon 实际分页令牌、限流头和错误字段 |
| 数据库分页 | `id DESC` 游标 SQL 尚未在目标规模真实 MySQL 上验证执行计划和组合索引；`OFFSET` 已从对外列表移除，但批量任务仍有独立上限 |
| 物流轨迹 | `LogisticsServiceImpl.getTrackingTimeline` 仍按单个货件无界读取轨迹，极端货件可能产生大结果；应按 `(event_time, id)` 游标或明确上限治理 |
| 物流报价比较 | `LogisticsUpgradeServiceImpl.compareQuotes` 仍可能全量加载参与比较的数据；这是计算路径而非列表 API，但仍需按数据规模设置边界或聚合 |
| 采购 FBA 明细 | 货件列表已纳入游标分页；`listShipmentItems` 仍按父货件 ID 无界读取明细，需评估大货件上限或分页 |
| 全仓列表 | 仍需继续扫描其他模块的 `selectList`、固定 LIMIT 和导出路径，不能因为物流模块通过就推断全仓分页治理完成 |

### 7.28 第 87 轮：分层自检 POST 契约、统一入口与前端定界（2026-09-27）

**背景与判断**：第 83 轮已实现 CREDENTIAL → LWA_TOKEN → READ_API 分层自检，但原接口使用 GET，且 `forceTokenRefresh=true` 会失效 LWA token 缓存、单店和批量自检都会触发出网。这类操作不是安全幂等的查询，继续暴露为 GET 会误导网关、代理、浏览器预取和调用方缓存。

**本轮改动：**

1. 单店与批量自检统一改为 POST：`POST /spapi/preflight/shop/{shopId}?forceTokenRefresh=...`、`POST /spapi/preflight?forceTokenRefresh=...&limit=...`；仅 OPERATOR/ADMIN，逐店铺 `UserContext.isShopAllowedStrict`，批量 `limit` 上限 50。
2. 网关新增统一公开别名 `/api/preflight/** -> /spapi/preflight/**`，前端开发代理同步转发 `/api/preflight`；`/api/connectors/**` 别名一并保留，避免前端在开发与生产使用两套路径。
3. 连接器中心新增“运行分层连通性自检”和“强制刷新 LWA token”操作，展示 CREDENTIAL / LWA_TOKEN / READ_API 三阶段及 `PASS / FAIL / SKIP`、错误码、平台状态码、耗时、修复建议和 `nextAction`。
4. `SKIP` 明确显示为“SKIP（未执行）”，不能当作通过；非全通过显示“分层自检未全部通过”。`endpointOverridden=true` 且全绿只显示“非官方端点自检通过（仅 E2）”；官方端点全绿也只表示当前只读链路打通，不代表写入、RDT、通知或生产 SLA 已联调。
5. 新增 `PreflightControllerContractTest`，锁定 POST 方法、角色白名单、店铺授权 fail-closed、`forceTokenRefresh` 透传和批量 limit 边界；`DeploymentManifestContractTest` 增加网关别名与 rewrite 规则断言。

**新鲜验证证据：**

```powershell
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp'
mvn -o -pl amz-service/amz-service-spapi `
  -Dtest='PreflightControllerContractTest,ControllerFailureContractTest,DeploymentManifestContractTest' test

Push-Location amz-frontend
npm test -- --run src/__tests__/ConnectorCenter.test.ts
npm run build
Pop-Location
```

| 项 | 新鲜实测 |
|---|---|
| 后端定向契约 | `PreflightControllerContractTest` 5、`ControllerFailureContractTest` 1、`DeploymentManifestContractTest` 9，共 **15/15 PASS / 0F / 0E / 0S** |
| 前端连接器定向 | `ConnectorCenter.test.ts` **6/6 PASS**，覆盖分层结果、SKIP、非官方端点 E2 边界和 mock 提示 |
| SP-API 全量回归 | **574 run / 0F / 0E / 2S**，BUILD SUCCESS；2 skipped 为需要真实凭证的集成测试 |
| 前端全量回归 | **20 个测试文件 / 163 例 / 0F**，包含连接器中心 6 例 |
| 前端生产构建 | `vue-tsc && vite build` 成功，Vite 转换 **156 modules** |
| 证据边界 | 全部为本地契约、Mock/桩和静态配置验证；**没有真实 SP-API/LWA 凭证，没有真实 Amazon 请求，没有 E4/E5** |

**仍未闭环 / 风险（不得当作已完成）：**

| 项 | 说明 |
|---|---|
| API-Ready ≠ 已联调 | 当前仍是 `apiReady=false`、`reachable=false`；拿到凭证后还需完成应用审批、卖家授权、区域端点、角色/RDT/SPDS 权限、限流与字段契约联调 |
| 批量端点 | `POST /spapi/preflight` 尚未在真实 Spring 上下文和真实凭证下运行，`CredentialStore.getActiveShopIds()` 的真实行为未验证 |
| Token 刷新 | `forceTokenRefresh=true` 只在桩上验证缓存驱逐，未验证真实 LWA 端点在短时间重复刷新下的限流与失败语义 |
| 端点覆盖 | `endpointOverridden` 只反映配置，不校验覆盖主机是否真的是沙箱；非官方端点全绿仍只能算 E2 |
| Mock 证据 | mock profile 成功只证明离线结构和分支可执行，不能作为 Amazon 对账、权限或生产可用证据 |