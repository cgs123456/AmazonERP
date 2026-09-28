# Amazon Advertising API 接入手册（API-Ready）

- 文档日期：2026-09-25
- 适用范围：`amz-service-ad` 的 Amazon Ads API v3 真实链路
- 当前状态：代码已具备凭证驱动、按店铺隔离、LWA 刷新、活动/关键词/报表调用与 fail-closed 行为；**尚未使用真实 Amazon 凭证完成沙箱或生产联调**
- 证据边界：当前工程验证最高为进程内 HTTP 桩 + 官方文档/OpenAPI 契约核对，不等同于 E4 沙箱联调或 E5 生产验收

## 1. 结论与前提

系统满足“凭证到达进程后无需改代码即可调用”的工程前提，但“拿到凭证”仍不等于“立即生产可用”。上线前还必须确认：

1. Amazon Ads 开发者账户、应用和 API 权限已经获批。
2. LWA `client-id`、`client-secret`、`refresh-token` 有效，且属于 Ads API 应用。
3. `profileId` 与目标广告账户、站点、区域一致。
4. ERP 的 `shopId` 与 Amazon `profileId` 的映射经过业务确认，不能猜测。
5. 已使用沙箱或低风险生产账户完成一次真实的创建报表、轮询、下载、落库和回读。
6. 限流、超时、部分失败、权限撤销和密钥轮换已有运行处置。

**不要复用 SP-API refresh token。** Amazon Ads API 与 Selling Partner API 是两套授权体系，凭证和权限范围不同。

## 2. 获取凭证

### 2.1 申请 Ads API 应用

在 Amazon Ads 开发者控制台创建/登记应用，取得：

- Login with Amazon（LWA）`client_id`
- LWA `client_secret`
- 已获批的 Ads API 权限范围

具体申请入口和权限名称以当前 Amazon Ads 开发者控制台及官方文档为准。应用未获批时，即使 LWA 能换到 access token，广告接口仍可能返回 401/403。

### 2.2 通过 OAuth 授权码流程取得 refresh token

使用官方授权流程，将卖家/广告账户授权给上述应用。授权范围通常包含 `advertising::campaign_management`，但应以应用实际获批范围为准。

流程要点：

1. 浏览器打开 Amazon 的授权页，登录目标广告账户并同意授权。
2. 从回调地址取得一次性 `authorization_code`。
3. 用 `grant_type=authorization_code` 调用 LWA token endpoint 换取 `access_token` 和 `refresh_token`。
4. 妥善保存 `refresh_token`；后续由服务端使用 `grant_type=refresh_token` 自动换发短期 access token。
5. 不要把 refresh token 写入代码、Git、聊天记录、工单或普通配置文本。

PowerShell 核对示例（只使用进程环境变量，避免把值写进脚本）：

```powershell
$tokenBody = @{
  grant_type    = 'refresh_token'
  refresh_token = $env:AD_REFRESH_TOKEN
  client_id     = $env:AD_CLIENT_ID
  client_secret = $env:AD_CLIENT_SECRET
}

$token = Invoke-RestMethod `
  -Method Post `
  -Uri $env:AD_TOKEN_ENDPOINT `
  -ContentType 'application/x-www-form-urlencoded' `
  -Body $tokenBody

$profileHeaders = @{
  Authorization = "Bearer $($token.access_token)"
  'Amazon-Advertising-API-ClientId' = $env:AD_CLIENT_ID
}

Invoke-RestMethod `
  -Method Get `
  -Uri "$($env:AD_API_ENDPOINT)/v2/profiles" `
  -Headers $profileHeaders
```

命令历史仍可能记录变量名而非值；生产环境建议改用一次性密钥终端、CI Secret 或运维保险库。

### 2.3 选择 profileId

`GET /v2/profiles` 返回当前授权可见的广告 profile。选择时必须同时核对：

- `profileId`
- 国家/站点
- 账户名称或业务归属
- 货币与时区（如返回）
- 目标 ERP `shopId`

服务端后续请求会发送：

```http
Authorization: Bearer <access_token>
Amazon-Advertising-API-ClientId: <client-id>
Amazon-Advertising-API-Scope: <profile-id>
```

`profileId` 不能跨店铺猜测或复用。若一个 LWA 应用下有多个 profile，ERP 侧仍需为每个 `shopId` 明确配置对应的 `profileId`。

## 3. 区域端点

当前代码默认使用 NA 端点。真实区域必须与 profile 所在区域匹配，否则可能出现 401/403 或无数据。

| 区域 | `AD_API_ENDPOINT` 常用值 | 说明 |
|---|---|---|
| 北美（NA） | `https://advertising-api.amazon.com` | 当前实现默认值 |
| 欧洲（EU） | `https://advertising-api-eu.amazon.com` | 欧洲区域 |
| 远东（FE） | `https://advertising-api-fe.amazon.com` | 日本等远东区域 |

LWA token endpoint 默认：

```text
https://api.amazon.com/auth/o2/token
```

端点和区域归属可能随 Amazon 产品调整。正式上线前必须用当前官方 API host/region 文档与目标 profile 做一次核对，不能仅依赖本仓库默认值。

## 4. 配置凭证

### 4.1 单店铺：环境变量

生产 profile 为 `prod`，至少设置：

```dotenv
SPRING_PROFILES_ACTIVE=prod
AD_API_ENDPOINT=https://advertising-api.amazon.com
AD_TOKEN_ENDPOINT=https://api.amazon.com/auth/o2/token
AD_SHOP_ID=101
AD_CLIENT_ID=<lwa-client-id>
AD_CLIENT_SECRET=<lwa-client-secret>
AD_REFRESH_TOKEN=<ads-api-refresh-token>
AD_PROFILE_ID=<amazon-ads-profile-id>
```

Docker Compose 的 `amz-service-ad` 已声明这些键；真实值应通过 `.env` 或外部 Secret 注入。`.env.example`、`.env.demo.example`、`k8s/configmap.yaml` 和 `k8s/secret.yaml` 只能保留占位符。

Kubernetes 中：

- `AD_API_ENDPOINT`、`AD_TOKEN_ENDPOINT`、`AD_SHOP_ID`、`AD_PROFILE_ID` 属于非密钥配置，可进入 ConfigMap。
- `AD_CLIENT_ID`、`AD_CLIENT_SECRET`、`AD_REFRESH_TOKEN` 必须进入 Secret、Vault、External Secrets 或 KMS，不得进入 Git。
- Secret 轮换后必须滚动重启 `amz-service-ad`，确认旧 token 缓存失效。

### 4.2 多店铺：外部配置

多店铺不应把多个店铺凭证堆进同一个 `.env` 变量。推荐由 Spring Cloud Config、Vault 或挂载的外部 `application-prod.yml` 提供：

```yaml
advertising:
  api-endpoint: https://advertising-api-eu.amazon.com
  token-endpoint: https://api.amazon.com/auth/o2/token
  credentials:
    "101":
      client-id: ${AD_SHOP_101_CLIENT_ID}
      client-secret: ${AD_SHOP_101_CLIENT_SECRET}
      refresh-token: ${AD_SHOP_101_REFRESH_TOKEN}
      profile-id: ${AD_SHOP_101_PROFILE_ID}
    "202":
      client-id: ${AD_SHOP_202_CLIENT_ID}
      client-secret: ${AD_SHOP_202_CLIENT_SECRET}
      refresh-token: ${AD_SHOP_202_REFRESH_TOKEN}
      profile-id: ${AD_SHOP_202_PROFILE_ID}
      endpoint: https://advertising-api.amazon.com
```

规则：

- map key 必须是 ERP 的合法数字 `shopId`。
- 每个 key 必须有完整的 `client-id`、`client-secret`、`refresh-token`、`profile-id`。
- 未配置或配置不完整的 `shopId` 会 fail-closed，不会借用其他店铺凭证。
- 如不同区域店铺混用，必须为对应店铺显式设置 `endpoint`。
- `.env`/Compose 当前只覆盖单店铺链路；多店铺必须走外部配置注入，不能假设 Compose 已支持。

## 5. 启动前检查

1. 确认 `SPRING_PROFILES_ACTIVE` 为 `prod`；只有明确离线演示才使用 `mock`。
2. 确认 `amz_ad` 空库已创建，且 `amz-service-ad` 成功执行 Flyway `V1`、`V2`。
3. 确认 Redis、MySQL、Nacos 和目标网络可达。
4. 确认 `AD_SHOP_ID` 在 ERP 中存在且用户/角色有该店铺权限。
5. 确认 `AD_PROFILE_ID` 与目标账户匹配。
6. 观察启动日志中不存在凭证明文；日志只应出现店铺 ID、配置是否完整和错误分类。

## 6. 手动触发同步

通过网关调用：

```http
POST http://<gateway-host>:10010/ad/reports/sync?shopId=101&days=7
Authorization: Bearer <erp-jwt>
```

说明：

- `shopId` 省略时同步所有“本地已有活动”或“已配置完整凭证”的店铺。
- `days` 默认 7，非法或非正数按 7 处理，上限 30。
- 同一天多次执行是幂等的；`amz_ad_daily_report` 使用 `(shop_id, campaign_id, report_date)` 唯一键做原子 upsert。
- 该接口会先尝试刷新活动元数据，再拉取日报。元数据失败不会阻断日报落库。

响应示例：

```json
{
  "code": 200,
  "data": {
    "shopId": 101,
    "days": 7,
    "attempted": 1,
    "succeeded": 1,
    "failed": 0,
    "skipped": 0,
    "upserted": 12,
    "metadataWarnings": 1
  }
}
```

字段含义：

| 字段 | 含义 |
|---|---|
| `attempted` | 实际进入同步流程的店铺数 |
| `succeeded` | 报表拉取和落库流程完成的店铺数 |
| `failed` | 报表拉取或落库失败、已计为失败的店铺数 |
| `skipped` | 未进入同步的店铺数；例如显式传入 `shopId=null` 的单店入口 |
| `upserted` | 实际插入或更新的日报行数，不等于店铺数 |
| `metadataWarnings` | 报表成功但活动元数据列表/落库出现告警的店铺数 |

`metadataWarnings > 0` 时，广告绩效日报仍可落库，但活动名称、预算、状态、广告类型可能保持旧值或不完整。此时不要把整次同步判定为完全成功。

## 7. 常见故障排查

### 7.1 HTTP 401 / 403

优先检查：

1. `client-id`、`client-secret`、`refresh-token` 是否属于同一个 Ads API 应用。
2. `refresh-token` 是否被撤销、过期或被替换。
3. `profileId` 是否属于当前授权账户。
4. 区域端点是否与 profile 匹配。
5. 应用是否已获得目标 Ads API 权限，广告账户是否完成授权。
6. 是否误把 SP-API 凭证配置到了 Ads API。

代码对第一次 401 会丢弃缓存 access token 并强制刷新一次；第二次仍失败即 fail-closed，不会返回样例数据。

### 7.2 HTTP 429

- 客户端将 429 归类为可重试的 `AD_RATE_LIMITED`。
- 当前客户端没有内置指数退避队列；调用方/调度器必须避免并发轰炸。
- Amazon 的 `Retry-After` 头可能存在也可能缺失，不能把“没有该头”当作未限流。
- 多店铺可能共享区域级限流队列，不能假设每个 refresh token 或 profile 都有独立额度。
- 生产建议：单实例串行、限制 `days`、错峰调度、记录 429 比率，必要时增加持久化重试/退避队列。

### 7.3 报表超时或长期 PENDING/PROCESSING

- 当前默认每 5 秒轮询一次，最多 120 次。
- 超时会抛 `AD_REPORT_TIMEOUT`，可重试。
- 检查日期范围、报表列、账户数据量、区域端点和 Amazon Ads 状态页。
- 不要用无限轮询替代超时；重试要有上限并记录 `reportId`。

### 7.4 报表返回但 `upserted=0`

可能原因：

1. 日期范围内确实没有广告活动数据。
2. 报表已完成但下载体为空数组。
3. 报表字段契约变化，导致解析失败并计为 `failed`。
4. `campaignId`、`date` 等必填字段缺失。
5. 报表行只有广告组粒度，但同步层聚合后没有可落库活动。

先查看服务日志中的 `AD_INVALID_RESPONSE`、`AD_REPORT_FAILED`、`AD_REPORT_TIMEOUT`，再用小日期范围复测。

### 7.5 `metadataWarnings > 0`

这表示日报链路成功，但活动元数据链路有问题：

- `listCampaigns` 失败；或
- 某个活动元数据 upsert 失败。

处置顺序：

1. 检查日志中的店铺 ID 和 campaign ID。
2. 确认活动列表接口权限与报表接口权限是否一致。
3. 检查 `amz_ad_campaign_ext` 的唯一键和数据库写入错误。
4. 修复后重新触发同步；日报可以重复 upsert，元数据也会重新刷新。
5. 在修复前，广告报表中的 `adType` 可能为空或沿用旧值。

### 7.6 部分店铺失败

- `syncAllShopsWithSummary` 会隔离单店铺异常并继续其他店铺。
- 返回 `failed > 0` 时，必须按店铺排查，不能因为 `succeeded > 0` 就认为全部成功。
- 若本地无活动且未配置凭证，店铺不会被枚举；这是“无任务”，不是“成功同步”。

## 8. 安全与轮换

1. 不在代码、YAML、Compose、日志、截图、聊天或 Git 中保存真实 Secret。
2. 使用 Secret Manager、Vault、External Secrets、KMS 或等效系统。
3. 凭证只挂载给 `amz-service-ad`，不要共享给无关服务。
4. 为应用设置最小权限；不要复用其他平台的 client secret。
5. 轮换 refresh token 时先完成新值注入，再滚动重启，确认新 token 换发成功后再撤销旧 token。
6. 若怀疑泄漏，立即撤销 refresh token、轮换 client secret、清理日志和缓存，并检查异常调用。
7. 数据库备份、日志和链路追踪中不得出现 access token、refresh token 或 client secret。

## 9. 验收记录模板

上线前至少记录：

| 项目 | 记录 |
|---|---|
| Amazon Ads 应用/权限审批 | 审批日期、权限范围、负责人 |
| LWA client-id 所属应用 | 不记录 secret 明文 |
| refresh token 轮换日期 | 到期/撤销策略 |
| profileId 与 shopId 映射 | 账户、区域、站点、确认人 |
| 区域端点 | NA/EU/FE 及核对来源 |
| 真实沙箱联调 | 请求时间、reportId、状态、下载/落库结果 |
| 手动同步响应 | 完整 JSON，含 `metadataWarnings` |
| 401/403/429 演练 | 时间、错误分类、处置结果 |
| 密钥轮换演练 | 旧 token 撤销和新 token 生效时间 |
| 生产灰度 | 店铺范围、回滚点、观察窗口 |

## 10. 官方资料与证据边界

- [Amazon Ads API Reporting v3 Get Started](https://advertising.amazon.com/API/docs/en-us/guides/reporting/v3/get-started)
- [Amazon Ads API Reporting v3 Columns](https://advertising.amazon.com/API/docs/en-us/guides/reporting/v3/columns)
- [Campaign reports v3](https://advertising.amazon.com/API/docs/en-us/guides/reporting/v3/report-types/campaign)
- [Create authorization grant](https://advertising.amazon.com/API/docs/en-us/guides/get-started/create-authorization-grant)
- [Retrieve access token](https://advertising.amazon.com/API/docs/en-us/guides/get-started/retrieve-access-token)
- [Retrieve profiles](https://advertising.amazon.com/API/docs/en-us/guides/get-started/retrieve-profiles)
- [Profiles guide](https://advertising.amazon.com/API/docs/en-us/guides/account-management/authorization/profiles)
- [Profiles API reference](https://advertising.amazon.com/API/docs/en-us/reference/2/profiles)
- [Rate limiting](https://advertising.amazon.com/API/docs/en-us/reference/concepts/rate-limiting)
- [API overview](https://advertising.amazon.com/API/docs/en-us/reference/api-overview)

注意：

- 区域端点表来自官方 API host 资料与公开 OpenAPI 镜像的交叉核对；正式配置前仍应以 Amazon 当前控制台和官方文档为准。
- 429/`Retry-After` 的行为以官方限流文档为基线，但社区反馈显示部分创建报表请求可能不返回 `Retry-After`，因此实现不能依赖该头必然存在。
- 多店铺区域级共享限流属于官方限流模型倾向与社区观察，不能当成每个 profile 独立额度的硬保证。
- 本手册描述的是 API-Ready 工程能力，不构成真实 Amazon 账户可用性、配额、数据准确性或生产合规性的证明。
