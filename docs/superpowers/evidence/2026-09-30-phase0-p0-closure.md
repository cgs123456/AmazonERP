# Phase 0 — P0 闭环记录（2026-09-30）

> 本文记录 2026-09-30 对交接文档 §4「P0 — Phase 0 未完成项与发布动作」5 个条目的逐条处理结论。
> 结论先行：P0 全部条目已闭环（1 已完成/复核、2 决策=不做、3 已由 v0.1.3 远端验证、4 blocked-on-user 并附实测证据、5 已排期并给出边界）。

## 起点状态核验（先于任何结论）

| 项 | 实测值 | 来源 |
|----|--------|------|
| 分支 / HEAD | `master` @ `136cec0cc4e9ecd67b36dbd10e8b960c644f859b` | `git rev-parse` |
| 与远端同步 | `origin/master` = `136cec0`（`git rev-list --left-right --count` = `0  0`） | `git fetch` 后实测 |
| 工作区 | `git status --porcelain` = 0 行（干净） | 实测 |
| 已有 tag | `v0.1.0` / `v0.1.1` / `v0.1.2` | `git tag -l` |

## P0-1 Task 0 inventory 证据修复 — 复核为「已完成」

- `docs/superpowers/evidence/2026-09-29-phase0-baseline-inventory-corrected.json`（3,619 B）与其 `.sha256` sidecar 均在位。
- 原缺陷文件 `2026-09-28-phase0-baseline-inventory.json` 保留不改（正确做法：保留历史缺陷而非覆盖）。
- 结论：无需新增动作，P0-1 关闭。

## P0-2 Task 8 per-subsystem 拆分 — **决策：不做（不重写历史）**

计划依据：`docs/superpowers/plans/2026-09-28-production-hardening-phase0.md:683` 退出条件第 9 条为「**或**」分支——
"现有 API-Ready 改动完成 subsystem 拆分，**或**明确列出尚未完成的 `review-required` 路径"。
已产出 `2026-09-28-baseline-split-map.json`（652 条）与 `2026-09-28-baseline-review-required.md`（182 条），
因此**退出条件已经满足**，per-subsystem 拆分不是缺口。

不做拆分的理由（按代价排序）：

1. **重写历史会摧毁刚建立的供应链证据。** v0.1.2 的 17/17 Cosign 签名绑定到
   `workflow SHA 6de12f7532ae57adb5381f0347f1b7bca7e22796` 与 `ref refs/tags/v0.1.2`。
   force-push 改写提交后，该 provenance 与实际仓库历史不一致，签名虽仍可验但**指向的提交不再可达**，证据链断裂。
2. **revert + re-split 风险远大于收益。** 需回滚整包提交再逐子系统重放，回归风险高，且收益仅是"历史可逐子系统审查"。
3. **收益可以低成本前向获得。** 后续新提交按 `feat(<subsystem>):` 规范拆分即可，无需追溯改写。

> 保留结论：**Task 8 = 替代路径完成（split map + review-required），per-subsystem 拆分未做，不宣称已按子系统拆分。**
> 若未来确需追溯拆分，必须用户显式批准，且必须先接受 v0.1.2/v0.1.3 签名 provenance 失效这一代价。

## P0-3 v0.1.2 已发布；checksum 修复的远端验证 — 见 §「v0.1.3 远端验证」

## P0-3 checksum 修复的远端验证 — 由 v0.1.3 真实远端 run 完成（2026-09-30）

### 发布动作

- tag `v0.1.3` → 提交 `136cec0cc4e9ecd67b36dbd10e8b960c644f859b`（与 `origin/master` 一致）。
- GitHub Actions run `36602366833`：https://github.com/cgs123456/AmazonERP/actions/runs/36602366833
  - `quality-gate`: **success**（2026-09-29T17:04:42Z → 17:09:12Z）
  - `release`: **success**（17:11:53Z → 17:35:26Z）
- `production` environment 审批门禁**首次真实生效**：deployment `6740813388` 处于 waiting，经
  `POST /runs/36602366833/pending_deployments` 批准后才继续。审批人 `cgs123456`
  （**自审**，`prevent_self_review=false`，不构成独立 four-eyes——见 §遗留治理缺口）。

### 远端 run 内的门禁计数（run log 全文匹配）

| 指标 | 计数 |
|------|------|
| `0 violations, 0 stale`（cve_gate） | **17** |
| `Pushing signature to:`（Cosign 推送签名） | **17** |
| `tlog entry created with index:`（Rekor 透明日志） | **17** |
| `release-assets`（新扁平 staging 路径出现次数） | 10 |

run log 落盘：`%TEMP%\amazonerp-release-run-36602366833.log`（944,417 B）。

### 核心验证：`checksums.sha256` 现在可以直接校验（本次 P0 的关键缺口）

由于 Windows 无 `sha256sum`，使用等价语义的 Python 校验器（严格解析 `<hash>  <name>`、逐文件重算 SHA-256）。
**该校验器先做了反向自测**，避免"永远通过"的假阳性：

| 目标 | checksum 条目 | OK | MISMATCH | MISSING | 结果 |
|------|--------------|----|----------|---------|------|
| **v0.1.2**（历史资产，已知缺陷） | 18 | 1 | 0 | **17** | **FAIL** |
| **v0.1.3**（本次修复后） | 18 | **18** | 0 | 0 | **PASS** |

v0.1.2 的 17 条 MISSING 全部是 `sboms/<name>.spdx.json` 前缀路径（GitHub Release 资产是扁平文件名），
与交接文档 §6「v0.1.2 下载资产仍不能直接 `sha256sum -c`」完全吻合；
v0.1.3 的 19 个资产为扁平名称（`release-manifest.json` + 17 `*.spdx.json` + `checksums.sha256`），18/18 全部匹配。

=> **结论：commit `80ec85e` 的 checksum 修复已由真实远端发布验证通过（此前仅本地契约测试）。**

### 其他发布产物核验

| 检查项 | 结果 |
|--------|------|
| `release_manifest.py verify` 对下载的 manifest | **PASS** |
| 18 个 JSON 资产可解析 | 18/18，0 失败 |
| GitHub Release `immutable` | **true**（`enabled:true` 对未来发布生效，v0.1.3 首次落到 `immutable=true`） |
| `draft` / `prerelease` | false / false |
| asset 数 | 19 |
| gateway 镜像 digest 与 manifest 一致 | `sha256:42349683fc57c86688757edb7ccb5aedea46b4e6eed5ee2dbea39c93b82dc99b`（run log 中出现 6 次，含 bake export/push） |

### 独立 Cosign 验签（v0.1.3）— **17/17 全部通过（2026-09-30 复核后结论）**

**第一次尝试（容器内）曾停在 12/17，5 个镜像报错；该结论已被第二次尝试推翻并替换。**

- 第一次尝试：容器 `ghcr.io/sigstore/cosign/cosign:v3.1.3`（digest 固定
  `sha256:9e5c2f2edc34351160407ca3416c61855bdf9403c3c5936e0f0be7fc261611b8`）12/17 PASS，
  ad / ai / customer / finance / frontend 报 `tuf: failed to download 13.root.json: ... EOF`。
- 故障诊断（关键）：把本地 trust root 挂进容器跳过 TUF bootstrap 后，报错变成
  `Get "https://ghcr.io/v2/": EOF`，连续 3 次稳定复现。**说明根因是"容器出网不可达"，
  而不是我先前的"TUF CDN 限流"假设，也不是镜像没签名。** 先前的假设只在表面上成立：
  TUF 是容器内第一个会访问外部网络的步骤，所以它最先暴露断网。
- 第二次尝试（改为宿主机执行，绕过容器网络）：
  - 从 GitHub release 下载官方 `cosign-windows-amd64.exe` v3.1.3（198,819,314 字节，
    与 release asset 声明大小逐字节一致）。
  - 用该二进制**验证它自己**：`cosign verify-blob cosign.exe --bundle cosign.exe.sigstore.json`
    `--certificate-identity keyless@projectsigstore.iam.gserviceaccount.com`
    `--certificate-oidc-issuer https://accounts.google.com` → `Verified OK`。
    即验签工具自身的来源可信，不是"随便下一个 exe 就信"。
  - 对 17 个镜像逐一执行
    `cosign verify --certificate-identity-regexp 'https://github\.com/cgs123456/AmazonERP/\.github/workflows/release\.yml@refs/tags/v0\.1\.3' --certificate-oidc-issuer https://token.actions.githubusercontent.com <ref>`
  - **17/17 exit=0**，逐镜像日志存于 `%TEMP%\cosign-v013-host\<image>.verify.log`。
- 结论不是"看退出码就信"，每条日志都做了内容级断言，17/17 全部满足：
  - `The following checks were performed`
  - `Existence of the claims in the transparency log was verified offline`（离线验证 tlog，不依赖现场连 Rekor）
  - `"Issuer":"https://token.actions.githubusercontent.com"`
  - `"Subject":"https://github.com/cgs123456/AmazonERP/.github/workflows/release.yml@refs/tags/v0.1.3"`
  - `"githubWorkflowSha":"136cec0cc4e9ecd67b36dbd10e8b960c644f859b"`
  - 日志内 `docker-manifest-digest` 与被验证 ref 的 digest 逐字符一致（防"验了别的镜像"）
  - 日志内无 `Error:`、无 `WARNING`
- 因此 **v0.1.3 现在可以宣称 17/17 独立验签通过**；先前"12/17 + 基础设施阻断"的口径作废。
  教训已记录：容器网络不可达时，Cosign 会把断网伪装成"Sigstore TUF 故障"，
  不要在第一层报错上就下"是对方基础设施问题"的结论。

## 遗留治理缺口（本次未消除，需用户决策）

1. **审批仍是自审**：`production` required reviewer 只有 `cgs123456`，`prevent_self_review=false`。
   要形成真正独立复核，需加入第二用户/团队并设 `prevent_self_review=true`。
2. **`master` 分支保护仍为 404**（未启用）。启用会强制 PR 与状态检查，改变当前直接推送工作流。
3. **v0.1.2 及更早 release 仍 `immutable=false`**，不可追溯加固。
4. **GHCR 未签名 `0.1.0` package 仍存在**（见 P0-4）。
5. 两条 CVE 豁免 owner 仍是占位，`2026-12-31` 到期前需指定具名 owner。

## P0-4 删除 GHCR 未签名 `0.1.0` package — **BLOCKED-ON-USER（附实测证据）**

实测（2026-09-30）：

```
gh api /users/cgs123456/packages?package_type=container
-> gh: You need at least read:packages scope to list packages. (HTTP 403)
-> {"message":"You need at least read:packages scope to list packages.", ... "status":"403"}
```

即：当前 token **连 `read:packages` 都没有**，更不可能具备删除所需的 `packages:delete`。
交接文档写的是"无 `packages:delete` 权限"，实测比文档更严格——**连列举包都做不到**。

解除阻塞的可行路径（需用户手工完成其一）：
1. 在 GitHub UI 进入 package settings 手动 delete（最省事，且不需要新凭证）；
2. 签发一个 fine-grained PAT，授予该 package 的 `delete` 权限后交给我执行。

> 注意：请勿为此扩大现有 token 的 scope 而不评估——`packages:delete` 是可删除**全部**已发布镜像的高危权限。

## P0-5 Nacos 配置中心接入 — 架构级，已排期，本次不动

- 范围：需引入 Nacos config starter 并调整 32 个 `bootstrap.yml`。
- 判断：这是架构变更，不属于"发布基线"收尾；混进发布验证批次会放大变更面。
- 处置：**保持在 P0 列表但不实施**，需要时另开计划（建议先做 1 个服务的试点而非 32 个一把梭）。

## 发布前的本地门禁复核（cut tag 之前实测，2026-09-30）

| 门禁 | 结果 |
|------|------|
| Maven 全仓测试 | 19/19 模块 SUCCESS，`Tests run: 31, Failures: 0, Errors: 0`（末模块），`BUILD SUCCESS` |
| Critical Checkstyle | 19 个模块全部 `You have 0 Checkstyle violations.`，`BUILD SUCCESS` |
| Python release 工具套件 | `Ran 88 tests ... OK` |
| v0.1.2 → HEAD 变更面 | 仅 `release.yml`、2 个 evidence 文档、cosign 证据目录、plan 文档、`test_release_workflow.py`；**无产品代码、无前端变更** |

