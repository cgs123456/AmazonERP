# SP-API 首次部署凭证导入 Runbook

## 1. 适用范围与证据边界

本 Runbook 只解决一个部署问题：**空库首次部署时，把已有的 SP-API 店铺凭证加密导入 `amz_shop_credential`，然后让 `prod` 实例通过启动自检。**

它证明的是：

- 凭证能从部署 Secret/只读文件进入进程；
- 整批结构先校验、再加密落库；
- 导入进程不启动 Web、不启动调度；
- 导入失败不自动重启，部署流水线能阻断；
- 普通 `prod` 实例启动时会逐条校验凭证结构。

它不证明：

- LWA `clientId` / `clientSecret` / `refreshToken` 真实有效；
- SP-API 应用授权、角色权限、订阅和 Marketplace 已正确开通；
- 官方端点、字段契约和 usage plan 已完成真实沙箱或生产联调；
- 业务数据、错误码、限流和重放行为已经过 E4/E5 验证。

没有真实凭证时，只能验证部署清单、配置装配、结构校验和失败路径，**不得伪造联调成功结论**。

## 2. 前置条件

- 目标环境已有 MySQL 8，且 `amz_spapi` 空库可创建或已创建。
- 目标环境已准备 `.env`（Compose）或 `amz-erp-config` / `amz-erp-secret`（K8s）。
- `AMZ_CRYPTO_KEY` 是同一部署环境长期稳定的 32 字节 Base64 密钥。
- 目标 `shopId` 是业务侧已确定的店铺主键；当前凭证表不建立跨库外键，但下游业务必须使用同一 `shopId`。
- 已取得：
  - LWA `clientId`
  - LWA `clientSecret`
  - LWA `refreshToken`
  - `marketplaceId` 或合法 `region`
  - 建议同时提供 `sellerId`
- 已确认 SP-API 应用已获得对应业务角色/权限，并且 Marketplace 已订阅。凭证格式正确不等于权限已开通。
- 生产环境不得把明文凭证写入 Git、镜像、ConfigMap、普通日志或工单。

## 3. 凭证文件格式

示例文件：[`docs/examples/spapi-credentials.example.json`](../../examples/spapi-credentials.example.json)。

支持单个 JSON 对象或 JSON 数组。数组用于一次导入多个店铺，但必须满足：

- `shopId` 为正整数；
- 同一批次不得重复 `shopId`；
- `clientId`、`clientSecret`、`refreshToken` 必填且为非空字符串；
- `marketplaceId` 必须是已登记 Marketplace ID，且与 `region` 一致；如果只给 `region`，必须使用 `NA`、`EU`、`FE` 之一；
- `accessKey` / `secretKey` 是可选项，但必须同时提供或同时省略；
- 不允许未知字段，拼写错误会整批拒绝；
- 任一条结构不合法时，整批拒绝，不允许部分写入。

当前主链路优先使用 LWA access token。除非你的历史集成明确依赖 AWS SigV4，否则不要为了“字段看起来完整”而填写无效 `accessKey` / `secretKey`；占位符会被当作非空字符串。

## 4. 安全要求

1. 明文 JSON 只存在于受控临时目录，建议权限 `0400`。
2. 生产优先使用 K8s Secret 或 Compose secrets/只读 bind mount。
3. Secret/临时文件导入成功后立即删除；如需保留轮换副本，应放入企业密钥管理系统，不得留在部署机。
4. 日志只允许出现成功条数和 `shopId`，不得打印 `clientSecret`、`refreshToken`、`accessKey`、`secretKey` 或完整请求头。
5. `bootstrap` 不得与 `prod` 或 `mock` 同时激活；启动自检会拒绝该组合。
6. 导入失败时保留 Job/Pod 现场和退出码，修复输入后重新执行；不要通过自动重试掩盖结构错误。
7. `AMZ_CRYPTO_KEY` 轮换前必须先制定重加密方案。直接更换密钥会导致既有密文无法解密。

## 5. Compose 首次部署路径

以下命令在仓库根目录执行。PowerShell 示例：

### 5.1 准备配置

```powershell
Copy-Item .env.example .env
# 编辑 .env：至少替换 DB_PASSWORD、REDIS_PASSWORD、RABBITMQ_PASSWORD、JWT_SECRET_KEY、AMZ_CRYPTO_KEY
```

准备凭证文件时，不要把真实文件放到仓库内可提交路径。若必须在本地临时使用 `secrets/`，该目录已在 `.gitignore` 中忽略，但仍应在导入后删除。

```powershell
$credentialFile = (Resolve-Path 'C:\secure-temp\spapi-credentials.json').Path
$env:SPAPI_BOOTSTRAP_CREDENTIAL_FILE_HOST = $credentialFile
```

### 5.2 构建镜像

```powershell
docker compose build amz-service-spapi
```

### 5.3 执行一次性导入

```powershell
docker compose -f docker-compose.yml -f docker-compose.bootstrap.yml `
  --profile bootstrap run --rm amz-service-spapi-bootstrap
```

该命令会：

- 等待 `mysql` healthcheck 通过；
- 以 `bootstrap` profile 启动同一个 spapi 镜像；
- 运行 Flyway，创建/升级 `amz_spapi` 表结构；
- 读取只读挂载的 `/run/secrets/spapi-credentials.json`；
- 全量校验后加密写入；
- 输出成功条数并退出，不暴露端口，不启动调度。

成功时退出码为 `0`，日志应只出现导入条数，不出现字段值。失败时退出码非 `0`，Compose 因 `restart: "no"` 不会自动重试。

### 5.4 导入后清理

```powershell
Remove-Item -LiteralPath $env:SPAPI_BOOTSTRAP_CREDENTIAL_FILE_HOST -Force
Remove-Item Env:SPAPI_BOOTSTRAP_CREDENTIAL_FILE_HOST
docker compose rm -f amz-service-spapi-bootstrap
```

### 5.5 启动生产实例

```powershell
docker compose up -d amz-service-spapi
docker compose logs --tail=100 amz-service-spapi
```

`prod` 启动自检会从 DB 逐条读取凭证并调用结构校验器。任一条缺少 LWA 必需字段或路由字段时，实例拒绝启动，日志只报告 `shopId` 和字段名。

任何服务启动前还需注意 `AMZ_CRYPTO_KEY`：`docker-compose.yml` 对 16 个可部署服务都硬性要求它
（`${AMZ_CRYPTO_KEY:?...}`），并以环境变量名 `CRYPTO_KEY` 注入——`CryptoUtil` 查的属性是
`crypto.key`，只有这个名字会经宽松绑定落上去（`AMZ_CRYPTO_KEY` 只对 spapi/user 有效，
因为它们在自己的 `application.yml` 里显式映射过）。没设该变量时 `docker compose up` 会当场报缺变量，
而不是让 Pod 起来后 CrashLoopBackOff；逐服务是否供给由 `CryptoKeyProvisioningContractTest` 守。

## 6. Kubernetes 首次部署路径

以下命令假设命名空间、ConfigMap、Secret、MySQL StatefulSet 和镜像仓库已经按仓库清单准备。

### 6.1 建空库

```powershell
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/configmap.yaml
kubectl apply -f k8s/secret.yaml
kubectl apply -f k8s/infra/mysql-statefulset.yaml
kubectl apply -f k8s/infra/mysql-init-job.yaml
kubectl -n amz-erp wait --for=condition=complete job/amz-mysql-init --timeout=300s
```

确认 `amz_spapi` schema 存在后再继续。表结构由 bootstrap 容器内的 Flyway 创建，不由 init Job 建表。

### 6.2 准备并创建一次性 Secret

```powershell
kubectl -n amz-erp create secret generic amz-spapi-credential-bootstrap `
  --from-file=credentials.json=C:\secure-temp\spapi-credentials.json
```

Secret 只应保存一份短期副本。不要把凭证文件提交到 Git，也不要把它写进 ConfigMap。

### 6.3 执行 Job

```powershell
kubectl apply -f k8s/jobs/amz-service-spapi-credential-bootstrap.yaml
kubectl -n amz-erp wait --for=condition=complete job/amz-service-spapi-credential-bootstrap --timeout=600s
kubectl -n amz-erp logs job/amz-service-spapi-credential-bootstrap
```

Job 固定 `backoffLimit: 0`、`restartPolicy: Never`、`activeDeadlineSeconds: 600`。失败时查看 `kubectl describe job` 和 Pod 事件，修复输入后删除旧 Job 再执行；不要依赖自动重试。

### 6.4 校验导入结果

只查条数和必要元数据，不要查询或打印密文字段：

```powershell
kubectl -n amz-erp exec statefulset/mysql -- sh -c `
  'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -Nse "SELECT COUNT(*), MIN(shop_id), MAX(shop_id) FROM amz_spapi.amz_shop_credential"'
```

预期：条数等于输入 JSON 中的店铺数，且没有重复 `shopId`。

### 6.5 清理 Secret 和本地临时文件

```powershell
kubectl -n amz-erp delete secret amz-spapi-credential-bootstrap
Remove-Item -LiteralPath 'C:\secure-temp\spapi-credentials.json' -Force
kubectl -n amz-erp delete job amz-service-spapi-credential-bootstrap
```

删除 Secret/Job 不会删除已经加密落库的凭证。

### 6.6 启动生产 Deployment

```powershell
kubectl apply -f k8s/services/amz-service-spapi.yaml
kubectl -n amz-erp rollout status deployment/amz-service-spapi --timeout=300s
```

若启动失败，先检查 `ConnectorStartupCheck` 日志中的 `shopId` 和字段名，再检查 Secret/ConfigMap 注入是否完整。不要在未修复结构错误时把 `SPAPI_REQUIRE_CREDENTIALS` 改成 `false` 绕过自检。

## 7. 失败分类与处置

| 现象 | 可能原因 | 处置 |
|---|---|---|
| 找不到凭证文件 | `SPAPI_BOOTSTRAP_CREDENTIAL_FILE` 未注入或挂载路径错误 | 检查 Compose volume / K8s Secret volume 与容器内路径 |
| JSON 解析失败 | 文件为空、BOM/编码异常、非法 JSON | 用 `ConvertFrom-Json` 或 `jq` 校验后重新执行 |
| 未知字段 | 字段拼写错误或使用了未支持字段 | 按示例白名单修正，整批重跑 |
| 缺少 `clientSecret` / `refreshToken` | LWA 凭证不完整 | 回到 Amazon Seller Central/开发者后台重新获取 |
| `marketplaceId` / `region` 不一致 | 路由字段填错 | 按已登记 Marketplace 表修正 |
| `accessKey` 与 `secretKey` 只填一个 | 可选字段必须成对 | 补全或同时删除两项 |
| 数据库连接失败 | MySQL 未就绪、账号权限不足、schema 不存在 | 先修复 DB，再执行导入；不要改用 mock |
| prod 启动失败但导入成功 | 凭证结构仍不合法或 DB 中旧记录损坏 | 检查 `shopId` 和字段名；重新导入该店铺 |
| 导入成功但 API 仍失败 | 授权、角色、订阅、Marketplace 或字段契约未完成 | 进入真实沙箱/生产联调，不属于本 Runbook 能证明的范围 |

## 8. 轮换与回滚

- 轮换同一店铺：准备同 `shopId` 的新凭证 JSON，重新执行一次性导入。`putAll` 会覆盖该店铺，不会生成重复记录。
- 删除店铺凭证：通过受控运维流程删除 DB 行并清理缓存；不要在导入文件中用空字符串表达“删除”。
- 回滚：保留旧 Secret 仅用于受控短期回滚；回滚后必须再次执行 bootstrap，因为 prod 实例不会在运行期自动重新导入。
- 加密密钥轮换：先停止写入，导出并解密后使用新密钥重加密，再执行 bootstrap；不要把“直接换 `AMZ_CRYPTO_KEY`”当作轮换方案。

## 9. 验收记录模板

```text
环境：
执行时间（Asia/Shanghai）：
部署方式：Compose / K8s
凭证来源：企业密钥管理系统 / 临时只读文件
输入店铺数：
导入退出码：
导入日志摘要（只记条数，不记字段值）：
DB 校验：count=___，minShopId=___，maxShopId=___
prod rollout 状态：
失败项与处置：
证据文件 SHA-256：
未证明项：
```

## 10. 相关事实源

- 示例凭证：[`docs/examples/spapi-credentials.example.json`](../../examples/spapi-credentials.example.json)
- 连接器验收：[`connector-acceptance-runbook.md`](connector-acceptance-runbook.md)
- API-Ready 实施计划：[`../plans/2026-09-24-connector-api-ready-phase0.md`](../plans/2026-09-24-connector-api-ready-phase0.md)
- 生产设计事实源：[`../specs/2026-09-24-amazon-erp-production-design.md`](../specs/2026-09-24-amazon-erp-production-design.md)