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

### 1.3 凭证到位当天**必须先补**的三项能力（P0-52，未修复）

本 runbook 的三项要求今天**无法从 API 获得**，必须先补代码；否则 §3 的「一条命令」不可执行，A5 只能靠人工从日志捞取（脆弱、不可审计）。

| 编号 | 缺口 | 实测证据 | 最小修复 |
|---|---|---|---|
| P0-52a | **平台原始错误码在部分端点被吞** | 已透出 `e.getMessage()`（含平台 body）的端点：`FinancialDataController.java:63/78/93/109/131`；**吞成通用文案**的端点：`SpapiController.java:110`（`"sync failed"`）、`InventoryController.java:80`（`"sync failed"`）、`FeedsController.java:56`（`"feed submit failed"`）、`FeedsController.java:82`（`"feed status failed"`） | 统一错误响应体：`{code, message, platformStatus, platformCode, platformMessage, requestId}`；`code` 取 `SpApiEndpointNotAllowedException.code()` 同风格稳定串 |
| P0-52b | **`x-amzn-RateLimit-Limit` 回填值无结构化出口** | 头已被读取并回填本地窗口（`OrdersClient.java:280-285`、`FbaInventoryClient.java:201-205`、`FeedsClient.java:271-276`、`SpApiGateway.java:169-180`），但只在**收紧时**打一条 WARN（`SpiRateLimiter.java:139-143`），无表、无端点、无指标 | 落 `amz_spapi_rate_limit_observation`（shopId/endpoint/header值/回填后 maxRequests/window/时间）或暴露 Micrometer `spapi.ratelimit.limit` Gauge |
| P0-52c | **不存在验收 runner** | 全仓 `rg -i acceptance` 命中 0（仅文档引用，见 §3.4） | 新建 `tools/connector-acceptance/`（与 `tools/synthetic-data/` 同风格：Python + `.ps1`/`.sh` 包装） |

> 这三项**不影响**今天就能做的离线取证（§7），但**决定「凭证到位当天能否一条命令出报告」**。计划 DoD 中「`connector-acceptance-runbook.md` 落盘且可执行」当前**只满足前半句**。

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

### 3.4 诚实标注：这条命令今天**不存在**

全仓实测（排除 `dist`/`node_modules`/`target`/`out`）：`rg -i acceptance` 仅命中 5 处**文档**引用（本文件、spec §1.9.1(5)、plan Task 11、`src/test/resources/contracts/README.md:73`），**零命中** `tools/` 与任何可执行脚本。
→ 因此 §3.1 的两条命令当前必然失败（找不到文件）。这不是笔误，是 P0-52c 的原始形态。

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
**注意**：`GET /spapi/finance/*` 已把 `e.getMessage()` 透出（`FinancialDataController.java:63/78/93`），而 orders/inventory/feeds 四个端点**会吞掉**平台细节（P0-52a）——在 P0-52a 修复前，这四类只能人工从服务日志捞取，且必须在报告里标注取证方式为 `log-scrape`（弱证据）。

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

实测结果：`amz-common` **51/51 PASS**；`amz-service-spapi` **Tests run: 145, Failures: 0, Errors: 0, Skipped: 2**，`BUILD SUCCESS`（2 skip = `SpApiIntegrationTest`，需 `RUN_INTEGRATION_TESTS=true`）。

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

**上限声明**：以上全部 ≤ E3。按 `ConnectorEvidencePolicy.offlineCeiling()`，无凭证阶段的整体等级上限由最弱一环决定，**A5 的离线上限是 E1**——故今天对外的正确表述是「**具备对接能力（未联调）**」。

---

## 8. 未验证与风险（诚实清单）

1. **runner 不存在**（P0-52c）→ 本文件 §3 的命令今天不可执行；「一条命令出报告」是**待实现承诺**，不是现状。
2. **错误码取证通道不完整**（P0-52a）→ orders/inventory/feeds 四个端点吞掉平台细节，这四类的 A1 错误分类证据在修复前只能来自日志（弱证据，且依赖日志留存策略）。
3. **限流头无结构化出口**（P0-52b）→ A8 的「回填后本地窗口」目前只能从 WARN 日志抄写。
4. **沙箱覆盖范围未联网复核**：第 22 轮结论为「官方仅说明覆盖 2xx 与 400」；凭证到位当天须以官方文档确认，若沙箱实际不覆盖目标错误码，则 401/403/404/429 必须改到生产（需用户书面确认）。
5. **本沙箱无法建立 socket**（`IOException: Unable to establish loopback connection`，宿主限制）→ 离线阶段的桩回放全部走**进程内 `RecordingHttpTransport`**，真实 `HttpClient`/DNS/TLS 路径**未经任何测试**。
6. **A6/A7 今天必然不通过**：能力清单端点（Task 6）与 Outbox/DLQ 重放（A7）均未实现；即使 A1–A5 通过，也只能标「已接通（联调中）」。
7. **成本与时间未确认**：沙箱注册（企业资质 + 视频核验）、生产授权、Amazon 安全问卷/DPP 时限均为**用户侧投入**，本文件不给出工期承诺。
8. **`SpApiIntegrationTest` 是唯一的真实网络路径**，且默认跳过；凭证到位当天应先跑它（`RUN_INTEGRATION_TESTS=true` + `TEST_SHOP_ID`）作为**冒烟**，再跑本 runbook 的完整取证。