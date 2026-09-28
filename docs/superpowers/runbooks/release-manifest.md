# 确定性发布清单 Runbook

## 目的

`release-manifest.json` 把一次发布绑定到以下不可变输入：

- Git commit 与版本号；
- OCI 镜像引用与 registry 返回的 digest；
- 前端纳入发布范围的文件树摘要；
- 各模块 Flyway 迁移清单及逐文件 SHA-256。

该清单是**发布准入证据**，不是“代码已经安全”的证明，也不替代镜像签名、SBOM、漏洞扫描、来源证明或部署审批。`manifestSha256` 也不是数字签名：任何能修改清单的人都能重算它。生产发布还必须使用 Sigstore/cosign 等机制对清单或镜像进行签名并验证。

## 禁止事项

- 示例文件中的全零 digest 仅为占位符，**不得用于任何部署**。
- 不得使用可变的 `latest`、分支名或仅用 image tag 作为部署依据。
- 不得在含未提交改动的 worktree 中生成正式清单。
- 不得只验证镜像 tag；必须核对 registry 中 tag 实际指向的 digest。
- 清单校验失败时不得继续部署，也不得以“重建一次”掩盖源内容漂移。

## 生成正式清单

只在干净 clone 和精确 commit 上执行：

```powershell
git clone https://github.com/cgs123456/AmazonERP.git
Set-Location AmazonERP
git fetch --tags --force
git checkout --detach <40位小写commit>
if (git status --porcelain) { throw "worktree is not clean" }

$commit = (git rev-parse HEAD).Trim()
$tag = "v<semver>"
$imageRef = "ghcr.io/<owner>/amazonerp-spapi:<semver>"
$imageDigest = "<推送到registry后读取的sha256:64位小写十六进制>"

python tools\release\release_manifest.py build `
  --root . `
  --commit $commit `
  --version <semver> `
  --image-ref $imageRef `
  --image-digest $imageDigest `
  --output release-manifest.json

python tools\release\release_manifest.py verify `
  --manifest release-manifest.json `
  --root .
```

`build` 输入要求：

- commit 必须恰好为 40 位小写十六进制；
- version 必须为 SemVer；
- image digest 必须为 `sha256:<64位小写十六进制>`；
- 前端摘要只覆盖 `amz-frontend` 的 `package.json`、`package-lock.json`、`tsconfig*.json`、`vite.config.*`、`index.html` 与 `src/**`；
- migration 摘要覆盖 `amz-service/*/src/main/resources/db/migration/V*.sql`；
- 相同 commit、版本、镜像引用、镜像 digest 和仓库内容必须产生 byte-identical JSON。

## 校验正式清单

必须在干净 clone 中重新执行：

```powershell
python tools\release\release_manifest.py verify `
  --manifest <downloads>\release-manifest.json `
  --root .
```

成功时进程返回 `0`；内容、路径、摘要或任一纳入文件不匹配时返回非 `0`。不要通过编辑清单后重算 `manifestSha256` 来使失败消失；应先确认实际部署来源。

如需独立登记清单文件指纹：

```powershell
Get-FileHash -Algorithm SHA256 release-manifest.json
```

## 发布记录必须回填

- Git commit（40 位小写 SHA-1）；
- 不可变 Git tag；
- CI run URL（最好包含 attesation/artifact URL）；
- 镜像引用；
- registry 返回的镜像 digest；
- `manifestSha256` 与清单文件的 SHA-256；
- 部署时间、部署人、审批人；
- 预发布验证结果与回滚目标 digest。

tag、CI URL、镜像 digest 和清单必须在同一条发布记录中互相可追溯。标签不能替代 digest；清单不能替代签名。

## 部署准入检查

1. `verify` 在干净 clone 返回 `0`。
2. Git commit 等于发布记录中的 commit 和 tag 指向。
3. `image.digest` 等于 registry 中目标 tag 返回的 digest。
4. 镜像签名/来源证明通过组织策略校验。
5. 待部署 artifact 的 SHA-256 与发布记录一致。
6. 数据库迁移已经经过备份、前滚演练和容量/锁表评估。

## 回滚

回滚时按发布记录中的上一版本 commit、镜像 digest 和清单重新执行同一套准入检查，不要使用移动 tag。Flyway 迁移默认前滚；清单只记录迁移集合，不会自动回退数据库。若必须恢复数据库，应按数据库故障恢复流程执行，并保留失败版本清单和审计记录。

## 多服务 OCI 构建元数据

`tools/release/services.json` 是 gateway、15 个 Java 微服务端口与模块路径的静态清单；`docker-bake.hcl` 为它们和 frontend 定义 17 个 target。这里的“可表达”只证明构建配方完整，不证明镜像已经构建、推送、扫描或部署。

解析并检查 Bake 配方：

```powershell
$env:REGISTRY = 'ghcr.io/<owner>'
$env:TAG = '<semver>'
$env:GIT_SHA = (git rev-parse HEAD).Trim()
$env:BUILD_DATE = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
docker buildx bake --file docker-bake.hcl --print
```

正式构建必须使用不可变 `TAG`、40 位 `GIT_SHA` 和镜像 digest，禁止以 `latest` 或分支名部署：

```powershell
docker buildx bake --file docker-bake.hcl --push
docker buildx imagetools inspect "$env:REGISTRY/amazonerp-spapi:$env:TAG" --format '{{json .Manifest.Digest}}'
```

所选 registry 必须允许 `linux/amd64` 与 `linux/arm64` 清单。frontend 镜像使用 `amz-frontend/Dockerfile`，在容器内提供 SPA history fallback，并把 `/api/` 转发到 `amz-gateway:10010`、把 `/ws/` 转发到 `amz-service-message:8888`。

发布记录需要为 17 个 target 分别登记实际 digest；仅让 `docker buildx bake --file docker-bake.hcl --print` 成功不能替代构建、SBOM、漏洞扫描、签名和运行验证。