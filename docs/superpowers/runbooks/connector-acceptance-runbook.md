# 连接器验收 runbook（A5 取证：凭证到位当天）

> **状态：未执行。** 本仓库当前**没有任何平台凭证**，也**尚未实现**本文件 §3 的那条命令（全仓 `rg -i acceptance` 只命中文档，见 §3.4）。
> 因此本文件是**执行前契约**：规定凭证到位当天「做什么、产出什么、怎么判定」，并逐条标注**哪些步骤今天就能跑、哪些必须先补代码**（P0-52）。
> **不得**把本文件的存在当作「已接通」或「已验收」的证据（spec §1.9、§1.9.1(1)）。

关联文档：spec `docs/superpowers/specs/2026-09-24-amazon-erp-production-design.md` §1.9 / §1.9.1 / §1.9.2；计划 `docs/superpowers/plans/2026-09-24-connector-api-ready-phase0.md` Task 11 Step 6。

---

## 0. 三条硬约束（先读，违反则整份验收记录作废）

| # | 约束 | 依据 |
|---|---|---|
| C1 | **必须以 `SPRING_PROFILES_ACTIVE=prod` 启动被测服务** | `application.yml:7` 默认 `mock`；mock profile 下 `ReportsMockClient`/`FinancesMockClient`/`FeesMockClient` 会返回**样例数据**，据此产出的「成功样例」是假证据（P0-01 / P0-24） |
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

### 1.3 凭证到位当天**必须先补**的前置能力（P0-52；**a/c/d 已修复**，b 未修复）

本 runbook 的前置要求里 **b 今天仍无法从 API 获得**，必须先补代码；**c/d 已于第 48 轮落地**（见下表与 §3.4 / §7.1）。未补齐时 §3 的「一条命令」不可执行，A5 只能靠人工从日志捞取（脆弱、不可审计）。

| 编号 | 缺口 | 实测证据 | 最小修复 |
|---|---|---|---|
| P0-52a 【**已修复（第 45 轮）**】 | **平台原始错误码在部分端点被吞** | 修复前：`SpapiController.syncOrders`、`InventoryController.sync` 只回 `"sync failed"`，`FeedsController.submit/status` 只回 `"feed submit failed"`/`"feed status failed"`；`FinancialDataController` 虽透出 `e.getMessage()`，却会把预签名 S3 URL 原文带进响应（→ P0-53） | 修复落点：新增 `connector/ErrorSummary.java`（① 沿 `getCause()` 取**最深层根因**——订单/库存的熔断 fallback 把平台错误包在 `degraded (circuit-breaker/exception)` 里，只看最外层会再次丢信息；② 掩掉签名/令牌/密钥/口令；③ 压单行 + 1000 字符上限），9 处边界出口（财务域 5 + 订单/库存/Feeds 4）统一改用它。**仍只是诊断文本，不是结构化错误契约**——`{code, platformStatus, platformCode, platformMessage, requestId}` 响应体仍未实现 |
| P0-52b | **`x-amzn-RateLimit-Limit` 回填值无结构化出口** | 头已被读取并回填本地窗口（`OrdersClient.java:280-285`、`FbaInventoryClient.java:201-205`、`FeedsClient.java:271-276`、`SpApiGateway.java:169-180`），但只在**收紧时**打一条 WARN（`SpiRateLimiter.java:139-143`），无表、无端点、无指标 | 落 `amz_spapi_rate_limit_observation`（shopId/endpoint/header值/回填后 maxRequests/window/时间）或暴露 Micrometer `spapi.ratelimit.limit` Gauge |
| P0-52c 【**已修复（第 48 轮）**】 | **不存在验收 runner** | 修复前：全仓 `rg -i acceptance` 命中 0（仅文档引用，见 §3.4） | 落点 `tools/connector-acceptance/`（与 `tools/synthetic-data/` 同风格：Python + `.ps1`/`.sh` 包装）：`acceptance_runner.py` + `run.ps1` + `run.sh` + 桩 `fake-service.py`。两道闸门：①C1 不满足 → 退出码 2 **且不产出记录**；②桩自描述 `stub=true` 默认拒绝，须显式 `--allow-stub` 且 **A5 封顶 E2** |
| P0-52d 【**已修复（第 48 轮）**】 | **连接器自描述缺失**：`GET /spapi/status` 只回固定串 `"SP-API service running"`，C1 的四条硬约束（prod / 非 mock / 自检已跑 / 凭证 ≥1）在**进程外无法核验** | 修复前 mock profile 下 `ReportsMockClient`/`FinancesMockClient`/`FeesMockClient` 返回离线样例，「成功样例」是假证据 | 落点 `connector/ConnectorSelfDescription.java` + `SpapiController.status()`：返回 `{service, connector, profile, mockClientsActive, startupCheckRan, startupRequireCredentials, loadedCredentialCount}`（启动快照优先；未跑自检时如实写 `startupCheckRan=false`、`loadedCredentialCount=-1`；**不含任何机密**）；`ConnectorSelfDescriptionTest` 6 例锁死键集合。**副作用**：`data` 由字符串变对象，接入方若有外部消费者需同步 |

> 这三项（b/c/d）**不影响**今天就能做的离线取证（§7），但**决定「凭证到位当天能否一条命令出报告」**。计划 DoD 中「`connector-acceptance-runbook.md` 落盘且可执行」自第 48 轮起前后半句均已满足——但「可执行」是在**本地桩**上验证的（§7.1），真实服务仍未跑过。

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
| A6 | E3：能力清单与实现一致 | 依赖 Task 6 的 `GET /api/connectors`（**未实现**，届时本文件需补一节对照） |
| A7 | E4：失败可重放（持久化重试 / DLQ / 原始响应留存） | **未实现**（无 Outbox/Inbox）→ 本项今天必然判不通过，如实标注 |
| A8 | E4：限流与配额真实 | §4.4（需 P0-52b 落地） |

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
| 2 | `SPRING_PROFILES_ACTIVE=mock` 启动（或 prod + mock 开关） | **拒绝启动** | 同上 |
| 3 | 真实凭证启动成功后，用**不存在的 shopId** 调 `GET /spapi/inventory/{shopId}` 与 `POST /spapi/sync/orders?shopId=<不存在>` | 明确错误（`no credential for shopId=...`），**不是**空列表/0 | A2 不达标（静默降级） |
| 4 | 记录 `GET /spapi/status` 响应、镜像 digest、配置哈希 | 见 §3.3 `target` | — |

依据：`com.amz.credential.ConnectorStartupCheck`，离线用例 `ConnectorStartupCheckTest`（4 例）。

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
**注意（P0-52a 已修复，第 45 轮）**：finance / orders / inventory / feeds 五组端点的失败响应现在都带平台细节——根因文本（`status=<code>` + 平台 `errors[].code/message`）经 `ErrorSummary.of(e)` 收敛并脱敏后写入 `Result.message`，报告可直接引用响应体，**不再需要**把 `log-scrape` 当作唯一取证方式（服务日志仍保留完整堆栈，用于逐字复核）。仍未实现的是**结构化错误契约**（`{code, platformStatus, platformCode, requestId}` 字段化响应），当前形态是单行诊断文本；`requestId` 目前也无处可取（见 §8 第 2 条）。

### 4.4 限流与 `x-amzn-RateLimit-Limit` 回填（A8）

1. 触发一次 429（§4.3 最后一行）。
2. 记录 `x-amzn-RateLimit-Limit` 原值，以及**回填后的本地窗口**（`endpoint` / `maxRequests` / `window`）。
3. 观测点（P0-52b 修复前）：`SpiRateLimiter.java:139-143` 的 WARN `updateLimit tightening: endpoint=... rate=...req/s -> max N req per Wms (was M)`；修复后改为结构化记录。
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
| A6 | | | `GET /api/connectors`（未实现） | |
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
| `com.amz.auth.LwaTokenExchangeContractTest` | 11 | A1（认证交换契约） | E3 |
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
| `com.amz.client.SpApiGatewayDownloadErrorTest` | 4 | P0-53 读侧（预签名下载 URL 不进异常文本/日志链） | E2 |
| `com.amz.client.FeedsClientUploadUrlLeakTest` | 1 | P0-53 写侧（预签名上传 URL 不进异常文本/日志链） | E2 |
| `com.amz.controller.FinancialDataControllerErrorTextTest` | 3 | P0-52a/P0-53（财务域边界出口） | E1 |
| `com.amz.controller.ControllerErrorTextContractTest` | 5 | P0-52a（固定文案端点边界出口） | E1 |
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
| 桩端到端 A1–A8 | 带 `--allow-stub`；orders / inventory / feeds / reports / reports-download / finances / fees + 401 / 403 / 404 / 429 + 报表状态轮询（前 2 次 `IN_PROGRESS`） | 退出码 **1**（已执行但有缺项）。`A1 E4`、`A2 E4`、`A3 E1`、`A4 E1`、**`A5` 被桩自描述压到 `E2`**、`A6 E0`（`/api/connectors` 404 → Task 6 未实现）、`A7 E0`（无观测点，恒不通过）、`A8 E0`；`displayText` = 「具备对接能力（未联调）」，`apiReady=false`、`reachable=false` |
| 桩端到端 + operator attestation | 同上，另加 `--attest`（`a3.startupRefusals` 两例 / `a4.tokenObservations` 两条不同摘要 / `a8.rateLimitObservations` / `target.imageDigest`），桩开启 `/api/connectors` | 退出码 **1**，阻断项只剩 **`A5(E2<E4)` 与 `A7(E0<E4)`**；`A1/A2/A3/A4/A8 = E4`、`A6 = E3`。即除 A5（需真实部署）与 A7（Outbox/DLQ 未实现）外**全部自动达标** |
| 产物契约 | 读回 `.json` 与 `.sha256` | 顶层键集合 = 冻结 12 键；`.sha256` 为 `<hex>  <filename>\n`（**两空格**）；JSON 以 LF 结尾且与 `.sha256` 第一段**逐字节一致**；`conclusion.secretScan.verdict = PASS`；诱饵（`x-amz-signature` / `AKIA…` / 会话 token / JWT）**全部被掩且不误报**；`identity.sellerIdMasked` 打码生效（`A1STUBLOCAL0001` → `A1ST****`）；`generator.options` 只记 `authTokenFileProvided=true`，**不含 JWT 与其路径** |

**这组实测回答的问题**：「有 API 之后，除了 A5 真实联调与 A7（未实现）之外，其余判据能否自动达标？」
→ 在桩上补齐 operator 证据后，**A1–A4、A6、A8 全部达标，只剩 A5 与 A7**。含义是：凭证到位当天的制约项是**可枚举**的，不是黑箱；
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

`ConnectorRegistryTest` 的 11 个用例（要点）：

| 用例 | 断言 |
|---|---|
| `implementedOperationsHaveRealCallSites` | 已实现的 11 条 operation 必须在 `src/main/java` 有真实调用点（排除能力表自身，排除集被逐字锁死） |
| `notImplementedOperationsHaveNoCallSite` | 7 条未实现能力的关键词在 `src/main/java` 命中 **0**（否则「未实现」是假声明） |
| `notImplementedOperationsCarryEvidenceNote` | 未实现项必须显式在列且 `note` 非空（防止前端把「无代码」渲染成「未配置」） |
| `a5IsE0WithoutRealIntegration` + `assessmentStaysHonest` | A5 无联调记录 → 声明 E0，`apiReady=false`、`reachable=false`、`displayText=具备对接能力（未联调）` |
| `credentialSourceNeverClaimsUnimplementedSources` | `credentialSource` 只输出 `db` / `none`，永不输出未实现的 `env` / `vault` |
| `mockProfileIsReported` | mock profile 激活时如实上报 `mockActive`，且此时 `apiReady=false`（mock 下的「成功」不算证据） |
| `selfTestResultIsRecorded` | 自检结果可回读、可清除回 `NEVER_RUN`；`lastCallAt` 从未执行时为 null（不伪造时间） |
| `operationsAreImmutable` / `describeAllContainsOnlyRegisteredConnectors` | 能力表不可变；渲染结果不含 `clientSecret` / `refreshToken` / `AKIA` 等任何凭证字段 |

**证据边界**：本条列全部为 **E1（自证）**——断言对象是本仓库源码。它让「支持什么、缺什么、证据到哪一级」
变成一条命令可判定的事实源，但**不证明平台会接受我方请求**；后者仍要等凭证到位、按 §3 对真实服务跑一次（A5 / E4–E5）。

> 口径提醒：全仓计数必须按 `^\[(INFO\|WARNING)\] Tests run:` 统计（spapi 汇总行因有 skip 而是 `[WARNING]` 前缀）；
> `-pl … -am` 的 reactor 合计是 **254 = amz-common 51 + spapi 203**，引用「spapi 单模块」时取 203。

## 8. 未验证与风险（诚实清单）

1. **runner 已存在，但从未对真实服务跑过**（P0-52c 第 48 轮修复）→ §3 的命令现在可执行，且**只有 C1 全绿才会产出记录**；但迄今所有执行都对着**本地桩**（`stub=true` + `--allow-stub`，A5 封顶 E2），因此「一条命令出**真实联调**报告」**仍是待验证承诺**，不是现状。
2. **错误码只有诊断文本、没有结构化契约**（P0-52a 已修复「可诊断性」，结构化仍缺）→ 五组端点的失败响应现在都带平台 `status` 与 `errors[].code/message`（`ErrorSummary` 收敛 + 脱敏），但输出是**单行文本**，不是 `{code, platformStatus, platformCode, platformMessage, requestId}` 字段化响应；下游若要按错误码自动分类，仍需解析文本（脆弱），且平台 `requestId` 目前没有透出通道（排障时只能靠时间窗对齐服务日志）。
3. **限流头无结构化出口**（P0-52b）→ A8 的「回填后本地窗口」目前只能从 WARN 日志抄写。
4. **沙箱覆盖范围未联网复核**：第 22 轮结论为「官方仅说明覆盖 2xx 与 400」；凭证到位当天须以官方文档确认，若沙箱实际不覆盖目标错误码，则 401/403/404/429 必须改到生产（需用户书面确认）。
5. **本沙箱无法建立 socket**（`IOException: Unable to establish loopback connection`，宿主限制）→ 离线阶段的桩回放全部走**进程内 `RecordingHttpTransport`**，真实 `HttpClient`/DNS/TLS 路径**未经任何测试**。
6. **A6/A7 今天必然不通过**：能力清单端点（Task 6）与 Outbox/DLQ 重放（A7）均未实现；即使 A1–A5 通过，也只能标「已接通（联调中）」。
7. **成本与时间未确认**：沙箱注册（企业资质 + 视频核验）、生产授权、Amazon 安全问卷/DPP 时限均为**用户侧投入**，本文件不给出工期承诺。
8. **`SpApiIntegrationTest` 是唯一的真实网络路径**，且默认跳过；凭证到位当天应先跑它（`RUN_INTEGRATION_TESTS=true` + `TEST_SHOP_ID`）作为**冒烟**，再跑本 runbook 的完整取证。
9. **预签名 URL 的泄露面已收窄，但没有归零**（P0-53）→ 两个预签名构造点（读侧 `SpApiGateway.downloadBytes`、写侧 `FeedsClient.uploadDocument`）都不再拼 URL，`URI.create` 失败时**不链 cause**，五个边界出口统一脱敏。**仍未覆盖**：① 未做全仓出站扫描——新增调用方若自行把预签名地址写进日志或响应，仍可绕过（现存断言只锁这两个构造点与五个 controller 出口）；② 未引入日志侧 scrub（appender/日志框架过滤器），因此**非** `URI.create` 的传输异常若自带 URL 文本，仍可能进日志；③ 平台返回的 S3 错误体**有意不回显**（会内嵌规范请求/凭证材料），排障只能靠状态码 + 对象路径。

10. **路径契约扫描有明确边界**（P0-54 已修复，但护栏不是全知）→ `SpApiPathContractTest`
    只扫 `src/main/java` 的**字符串字面量**、只认 6 个路径根，且**不覆盖**运行时拼接出的动态段
    （如 marketplace / 日期 / 分页参数）；将来新增 API 家族或改用配置化路径时必须同步扩表。
    另外它断言的是「与官方模型一致」（E3），**不能**替代 A5 的真实联调。
