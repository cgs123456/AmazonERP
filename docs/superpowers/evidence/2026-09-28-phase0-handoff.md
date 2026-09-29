# Phase 0 交接文档（2026-09-28）

> 目标读者：接手 AmazonERP 生产化升级的下一任工程师 / Agent。
> 结论先行：**Task 1–7（可发布基线）已完成并验证；Task 0 原 inventory 有缺陷，但已由 `2026-09-29-phase0-baseline-inventory-corrected.json` 修正版证据链满足；Task 8 已采用“split map + review-required 清单”替代路径完成，但 per-subsystem 拆分未做。Phase 0 本地退出条件已全部满足；当前 HEAD 的 `release.yml` 仍未在远端验证（旧 `v0.1.0` tag 已有两次远端执行且均失败，见 §7 的远端 release 审计），连接器状态仍为 API-Ready（未联调）。当前仓库状态以 §20 为准。**

## 1. 项目当前状态（2026-09-28 历史快照）

> 本节保留 2026-09-28 交接时快照；分支、HEAD 与风险项已被后续工作取代，当前状态见 §7。

| 项 | 值 |
|----|----|
| 仓库路径 | `C:\Users\Administrator\Desktop\AmazonERP` |
| 分支 | `codex/api-ready-connectors` |
| HEAD | `5ca0fdd` + 本次交接提交 |
| 工作区 | `git status --porcelain` = 0（干净） |
| 连接器状态 | **API-Ready（未联调）**——没有真实 Amazon 凭证，不得宣称已接通 SP-API |
| 技术栈 | Java 17 / Spring Boot 3.3.5 / Maven 3.9.11、Vue 3 / Vite / Vitest、MySQL 8 / Flyway 10.20、Python 3.11 标准库（release 工具链） |

## 2. 工作成果

### 2.1 Phase 0：生产化可发布基线（Task 1–7 完成；Task 0 证据已修正；Task 8 替代路径完成）

Phase 0 的目标不是修业务，而是让"提交、构建、镜像、迁移、回滚"可追溯、可复现。产出如下：

| Task | Commit | 成果 |
|------|--------|------|
| 0 冻结基线 | `4dfde26` | 原只读基线清单有 `statusEntryCount=631`、不可复现 `statusSha256`、sidecar 不匹配三项缺陷；已由 `2026-09-29-phase0-baseline-inventory-corrected.json` + sidecar 修正版证据链替代，原文件保留不改 |
| 1 仓库卫生门禁 | `37a193b` | `tools/release/repository_hygiene.py`：扫描 tracked 文件中的运行时产物 / 大文件 / 高置信度 Secret，allowlist 精确放行示例占位符 |
| 2 确定性发布清单 | `8c8f60f` | `tools/release/release_manifest.py`：同一输入生成逐字节相同的 JSON（无时间戳、无随机 UUID、无绝对路径、集合有序） |
| 3 OCI 多服务构建元数据 | `a85d541` | 可复现的多服务镜像构建配置（Docker Buildx/Bake），OCI 标签含 commit/digest/前端输入/Flyway 输入 |
| 4 CI 硬门禁 | `fc07f6b` | `.github/workflows/`：Maven 测试 + Critical Checkstyle + Python release 测试全部为强制门禁 |
| 5 签名供应链工作流 | `365ac05` | GHCR 推送 + SBOM（Syft）+ 漏洞扫描（Grype）+ 签名（Cosign）的 release workflow（旧 `v0.1.0` 两次远端执行均失败；当前 HEAD revision 远端执行通过后才能标 VERIFIED） |
| 6 安全回滚演练 | `a8f6b55` | `tools/release/rollback_drill.py`：dry-run 默认，验证回滚步骤可执行 |
| 7 Clean Clone 复验 | `d2b7619`/`7d933f2`/`f01738c` | 把散落文件补齐进 Git，`tools/release/verify_clean_clone.ps1` 可从干净 clone 一键复验 |

**Task 8 替代路径已完成（per-subsystem 拆分未做）**：已生成 `docs/superpowers/evidence/2026-09-28-baseline-split-map.json`（652 条）和 `docs/superpowers/evidence/2026-09-28-baseline-review-required.md`（182 条），并在 `2026-09-28-phase0-verification.md` 记录证据；退出条件第 9 条采用“或明确列出 review-required 路径”分支，已满足。计划要求的按子系统 `feat(api-ready):` / `docs(api-ready):` 提交序列仍未完成，现有 dirty 文件由整包提交吸收，因此不能宣称已按子系统拆分。

### 2.2 验证证据（截至 2026-09-29）

详见 `docs/superpowers/evidence/2026-09-28-phase0-verification.md`：

| 检查项 | 结果 | 状态 |
|--------|------|------|
| Maven 全仓测试 | 19/19 SUCCESS | VERIFIED |
| Critical Checkstyle | 0 violations | VERIFIED |
| Python release 工具套件（7 modules） | 88 tests PASS | VERIFIED（2026-09-29 final17 复跑） |
| 发布 workflow 契约测试 | 25/25 GREEN | VERIFIED（仅契约，非远端执行） |
| 回滚演练测试 | 7/7 GREEN | VERIFIED（dry-run） |
| 前端测试 + 构建 | 22 files / 175 tests PASS；`npm run build` PASS | VERIFIED（Node v22.22.2 / npm 10.9.7） |
| Clean Clone 复验（HEAD） | git clone/checkout/clean-tree/python/maven/npm-ci/frontend-build 通过 | 18/18 checks VERIFIED（2026-09-29 final17） |

### 2.3 仓库清理记录

- `git clean` 删除 74 个临时/过时文件（运行日志、round 产物、补丁临时脚本等）。
- 移除误提交的 `tools/release/__pycache__/` 字节码（commit `5ca0fdd`）。
- 本次交接补上通用忽略规则：`.gitignore` 新增 `__pycache__/` 与 `*.pyc`，防止任何工具链再次把字节码带进 Git。

### 2.4 关键产出文件索引

| 用途 | 路径 |
|------|------|
| Phase 0 计划 | `docs/superpowers/plans/2026-09-28-production-hardening-phase0.md` |
| 验证证据 | `docs/superpowers/evidence/2026-09-28-phase0-verification.md` |
| 发布检查清单（runbook） | `docs/superpowers/runbooks/release-candidate-checklist.md` |
| 回滚 runbook | `docs/superpowers/runbooks/release-rollback.md` |
| Clean Clone 复验脚本 | `tools/release/verify_clean_clone.ps1` |
| 仓库卫生扫描 CLI | `tools/release/repository_hygiene.py` |
| 发布清单 CLI | `tools/release/release_manifest.py` |
| 回滚演练 CLI | `tools/release/rollback_drill.py` |
| 基线冻结清单 | `docs/superpowers/evidence/2026-09-28-phase0-baseline-inventory.json`（+.sha256） |

## 3. 快速上手（接手者必读命令）

PowerShell 是唯一 shell。Maven 需要固定前缀：

```powershell
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp'
$mvn = 'C:\Users\Administrator\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd'

# 全仓测试（失败继续跑完，便于一次看清）
& $mvn -B -ntp test -fae

# Critical Checkstyle
& $mvn -B -ntp checkstyle:check '-Dcheckstyle.config.location=checkstyle-critical.xml'

# Python release 工具测试
python -m unittest discover -s tools/release -p 'test_*.py' -v

# Clean Clone 一键复验
tools/release/verify_clean_clone.ps1
```

提交纪律：禁止 `git add .`；每个提交只包含该任务明确列出的路径；提交前 `git show --stat` 核对。

## 4. 后续步骤（按优先级）

### P0 — Phase 0 未完成项与发布动作

1. **Task 0 inventory 证据修复 — 本地已完成，待提交/采纳**：原 inventory 不覆盖；已生成 `2026-09-29-phase0-baseline-inventory-corrected.json` + sidecar，并与 652 条 split map 对齐。退出条件第 1 条已由修正版证据链满足；提交前仍需用户批准。
2. **Task 8 per-subsystem 拆分 — 需用户决策（替代路径已完成）**：`split map + review-required` 已满足退出条件第 9 条“或”分支；若还要按子系统提交序列，需批准历史重写/force-push（不推荐）或 revert + re-split（重）。否则保持“替代完成、per-subsystem 未做”的表述。
3. **当前 HEAD 的 `release.yml` 远端真实执行 — 需用户批准**：推 tag 会触发 GHCR push、17 次 Grype、`cve_gate` 和 Cosign；旧 `v0.1.0` 两次远端执行均失败，当前 revision 仍未验证。`ci.yml` 的 9/9 绿不能替代。
4. **删除 GHCR 上未签名的 `0.1.0` package — blocked-on-user**：当前 token 无 `packages:delete` 权限，只能在 GitHub UI 手动删除。
5. **Nacos 配置中心接入 — 架构级，另行排期**：需要引入 Nacos config starter 并调整 32 个 `bootstrap.yml`。

### P1 — Phase 1 主题（真实对接能力）

1. **SP-API 联调**：需要用户提供开发者账号、刷新令牌、LWA 凭证、Marketplace 授权。拿到后先在 sandbox 验证，再按 runbook 走签名发布。
2. **前端 E2E**：装 npm 后补 Playwright/Vitest E2E，覆盖核心页面（订单、库存、物流、报表）。
3. **数据库迁移审计**：Flyway 全量脚本审查 + 迁移在空库与升级库双路径演练。

### P2 — Phase 2+ 主题

1. 可观测性：Micrometer + Prometheus 指标、OpenTelemetry tracing、结构化日志。
2. 性能压测：关键接口（订单同步、报表聚合）建立基线 P95 延迟与容量模型。
3. CI/CD 深化：预览环境部署、依赖自动更新（Dependabot）、镜像漏洞门禁阈值。

## 5. 关键约束（不得违反）

1. **连接器状态上限是 API-Ready（未联调）**：未获得真实 Amazon 授权与沙箱/生产证据前，任何文档、README、UI 不得宣称"已接通 Amazon"。
2. **CI placeholder digest ≠ 发布产物**：契约测试通过只证明 workflow 结构正确。
3. **workflow 只有远端真实通过后才可标 VERIFIED**，本地 GREEN 不能替代。
4. Phase 0 的确定性清单原则（无时间戳/随机值/绝对路径、有序集合）必须在后续 Phase 继续遵守。
5. 不得使用 `git reset --hard` / 全仓 `git clean` 恢复"干净"；恢复快照与冻结清单是唯一安全边界。

## 6. 风险与已知缺口

| 风险 | 影响 | 缓解 |
|------|------|------|
| Task 0 原 inventory 证据不一致 | 原文件仍是历史缺陷，但已由修正版 inventory 证据链覆盖；不覆盖原文件 | 保留原文件不改；以修正版 inventory + 652 条 split map 为权威 |
| Task 8 per-subsystem 拆分未做 | 无法按子系统审查/回滚；退出条件第 9 条已用“或列出 review-required”分支满足 | 保留 split map + 182 条 review-required；若要拆分需用户批准历史重写或 revert + re-split |
| 当前 HEAD 的 `release.yml` 未在远端验证（旧 `v0.1.0` 两次远端执行均失败） | 平台差异可能在真实 runner 暴露；供应链产物未验证 | 经用户批准后按当前 revision 真实执行 |
| 前端瘦身只有本地证据 | 本地 build/scan 不能替代 release runner 的 bake + Grype | 后续 `release.yml` 远端执行时覆盖 17 镜像 |
| 两条 CVE 豁免 owner 仍是占位 | 2026-12-31 到期前若无人接手会阻断续期 | 指定具名 owner，到期前复核 |
| 无真实 Amazon 凭证 | SP-API 层全部是 mock/契约测试 | Phase 1 需用户主动提供凭证，先 sandbox |
| 前端 `default.conf` 构建期未校验 | 配置错误要到容器启动才暴露 | 后续在写入后加 `nginx -t`（需可解析 upstream） |
| Nacos 未接入 | 配置中心能力缺失 | 架构级另行排期 |

## 7. 2026-09-29 状态更新（截至 PR #6 合并的历史核验快照）

本文档 §1 的状态表（`codex/api-ready-connectors` / HEAD `5ca0fdd`）已被后续工作取代，
**不要按它判断当前仓库位置**：

| 项 | 2026-09-29 核验快照 |
|----|---------------------|
| 默认分支 `origin/master` | `2e1be56`（PR #6 merge commit） |
| 已合并 PR | #1 `8d6100f`、#2 `c055060`、#3 `af1ef0c`、#4 `dae84b4`、#5 `034e4da`、#6 `2e1be56`，全部已合并 |
| 工作区 | 核验时干净；本文更新仅修改本交接文档 |
| 本次文档更新分支（历史） | `codex/phase0-handoff-followup`（从 `2e1be56` 新建；仅修改本交接文档） |

上表是截至 PR #6 合并后的核验快照；后续 `master` 前进属于预期，判断当前仓库位置应重新执行 `git log` / 查看 GitHub，不应把本表当作实时状态。

远端 CI 核验（均为 `ci.yml`，不是 `release.yml`）：master `034e4da` run `36499305682` = 9/9 SUCCESS（含 `docker`）；PR #6 分支 run `36499965363` = 8/8 SUCCESS，`docker` skipped；master `2e1be56` run `36500303341` = 9/9 SUCCESS（含 `docker`）。

### 远端 `release.yml` 审计（2026-09-29 补记）

`gh run list --workflow release.yml --limit 20` 显示旧 tag `v0.1.0` 触发了两次远端 run，均失败：

| Run | Tag / SHA | 结果 | 失败点 |
|---|---|---|---|
| `36392169854` | `v0.1.0` / `30f5e7a` | failure | `Bake images`：推送 `ghcr.io/amazonerp-frontend:0.1.0` 时 GHCR 返回 `400 Bad Request` |
| `36394234844` | `v0.1.0` / `4d644da` | failure | `quality-gate` 成功；`anchore/scan-action@v4` 报 Grype DB 已过期（29 周，最大 5 天）并发现 HIGH/CRITICAL；Cosign、manifest、GitHub Release 均 skipped |

当前 `v0.1.0` 指向 annotated tag `3b6385d`，其 peeled commit 为 `4d644da`；第一次 run 的 head SHA 是 `30f5e7a`，说明 tag 在两次 run 之间发生过更新/移动，但本次审计未证明具体操作方式。

当前 HEAD `95397b6` 的 workflow 已不再使用 `anchore/scan-action@v4`，改为 `anchore/grype:latest` + `tools/release/cve_gate.py` 对 17 个镜像逐一扫描；`git diff v0.1.0..HEAD -- .github/workflows/release.yml` 可复核。正确结论是：**旧 `v0.1.0` 的远端 release 已执行且失败；当前 HEAD 的 release 仍未被远端验证。** 旧失败不能证明当前代码仍有相同问题，也不能当作当前 workflow 已验证。

### P0 三项的进展（本文档写作时的缺口）

1. **装 Node/npm + clean clone 复验 — 已完成**：Node.js v22.22.2 / npm 10.9.7；
   `npm run test:run`（vitest）22 files / 175 tests PASSED；`npm run build` PASS；
   clean clone 复验 7/7 VERIFIED。证据见 `2026-09-28-phase0-verification.md` 的
   Frontend Verification Addendum。
2. **推送远端跑通 GitHub Actions — 已完成**：run `36385434376`（commit `1f8d772`）
   9/9 jobs SUCCESS。首次远端 run `36384609340` 暴露 2 个本地无法发现的真实缺陷
   （hygiene allowlist 因 CRLF 归一化失败；`schema-snapshot.json` 内嵌 50 个绝对路径，
   违反 Phase 0 确定性原则），均在 `1f8d772` 修复。**这条经验要带进后续 Phase：
   本地 GREEN 不等于远端 GREEN，Windows 开发 + Linux runner 的行尾与路径差异是真实风险。**
3. **actionlint — 已完成（2026-09-29 重跑）**：actionlint **1.7.12**（Windows amd64，发布 ZIP SHA-256 `6e7241b51e6817ea6a047693d8e6fed13b31819c9a0dd6c5a726e1592d22f6e9`），`ci.yml` + `release.yml` exit 0、无诊断输出；日志 `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-actionlint-20260929\actionlint-ci-release.log`（空输出 SHA-256 `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`），版本日志 SHA-256 `a3a83b725e75af26224b17f266e79fb92fee203f66bea3e527eeb0ad1e59d6e5`。
   限制：Windows 下无法调用 shellcheck，`run:` 脚本的 shell 语法未被深度校验，
   静态通过仍不等于远端执行成功。

### P2-3 镜像漏洞门禁 — 已落地（不是未来项）

`tools/release/cve_gate.py` + `cve-waivers.json`：cutoff 仍为 high，未豁免的 HIGH/CRITICAL
一律阻断；豁免必须具名、有 owner、有到期日，过期本身即失败；release.yml 对 17 个镜像逐一过门禁。
详见 `2026-09-28-container-cve-remediation.md` §13。

### ⚠ 引用数字时的口径警告

`2026-09-28-container-cve-remediation.md` §5 / §12 初版写的「15 个业务服务 447 -> 0」
与「全仓残留 19」**是错的**（那个 0 只扫了抽出的应用依赖集，不含 SkyWalking agent 与基础镜像
OS 包）。真实全镜像口径：**16 个 Java 镜像 x 15 = 240 + frontend 1 = 241**（frontend
瘦身前为 244）。该文档已加更正与 §13；**接手者不要引用旧的 19 / 447 -> 0。**

### 仍 NOT VERIFIED / 未做

- 当前 HEAD 的 `release.yml` 远端真实执行（GHCR push + SBOM + 17 次 grype + 门禁 + Cosign 签名）
  — 旧 `v0.1.0` tag 已触发两次远端 run 且均失败；当前 revision 仍未验证，本地与静态校验均不能替代。详见上节远端 release 审计。
- `cfa6149` 前端瘦身的远端构建/Grype — §13.9 已补本地证据，但 `release.yml` 仍未真实执行。
- Task 0：原 inventory 的 `statusEntryCount=631`、不可复现 `statusSha256`、sidecar 不匹配保留为历史缺陷；修正版 `2026-09-29-phase0-baseline-inventory-corrected.json` + sidecar 已自检通过，并与 652 条 `baseline-split-map.json` 的 head/status/manifest 一致。退出条件第 1 条按修正版判定满足；原 inventory 不再作为权威。
- Task 8：per-subsystem 拆分未做；替代路径（split map + 182 条 review-required）已完成，Phase 0 退出条件第 9 条“或”分支已满足。
- 删除 GHCR 上未签名的 `0.1.0` 镜像/package — 当前 token **无 `packages:delete`** 权限，只能手动。
- Nacos 配置中心接入（32 个 `bootstrap.yml`）— 架构级变更，未排期。
### master `docker` job 结果（2026-09-29 补记）

run `36450964636`（PR #2 合并后的 master push，`c055060`）**9/9 jobs 全绿**，`docker` job
id `109026928707` 于 2026-09-28T16:40:30Z 成功（10m38s）。

**别把它当成前端瘦身的验证**：ci.yml 的 docker job 只有 `docker build -t amazon-erp:latest .`，
构建根目录的 Java Dockerfile，不跑 bake 的 17 个 target，也不构建 `amz-frontend/Dockerfile`
（日志无 `apk del` / `nginx -t`）。因此 `cfa6149` 的前端瘦身**仍无远端构建验证**，
且 ci 的 docker job 不跑 grype，它绿不代表 CVE 门禁已验证。详见
`2026-09-28-container-cve-remediation.md` §13.7 / §13.8。

### 前端瘦身本地证据与 §13.9 更正（2026-09-29 补记）

PR #4/#5 已把以下本地实测写入 `2026-09-28-container-cve-remediation.md` §13.9：

- `amazonerp-frontend:base`：10 total / 4 HIGH；`slim-af1ef0c`：5 total / 1 HIGH（仅 zlib `CVE-2026-85091`，Grype 无 fix）。消失 3 条，无新增。
- 同一 `cve_gate --cutoff high --image amazonerp-frontend`：slim = 1 waived / 0 violations（exit 0）；base = 1 waived / 3 violations（exit 1）。
- `docker build -f amz-frontend/Dockerfile` exit 0；`apk del nginx-module-image-filter libgd tiff && nginx -t` 级联 purge 28 包，`nginx -t` 成功；slim 镜像已无 image_filter/tiff/libgd/libexpat/fontconfig，zlib 保留。
- §13.9 ⑥ 的更正：`load_module` 是 main context only；写进 `conf.d` 会被 nginx 拒绝。构建期 `nginx -t` 校验的 `nginx.conf` 正是唯一可写 `load_module` 的位置，因此 Dockerfile 注释的保护成立。真正较小缺口是 `default.conf` 在构建期未校验。
- 边界：以上全部是本机构建/扫描，不能替代远端 `release.yml`。


## 8. 2026-09-29 Checkpoint 3：Task 8 替代路径 + Task 0 证据缺陷更正

本轮只新增证据与状态更正，未改写历史、未 force-push、未 revert、未改代码行为。

### 8.1 新增产物

| 产物 | 说明 | SHA-256 |
|------|------|---------|
| `2026-09-28-baseline-split-map.json` | 652 条冻结路径的 `path/status/group/sha256/classification` | `b71985b668a078726ffb71f04bc3f35688f375860c71e7200775a70c91bf515e` |
| `2026-09-28-baseline-review-required.md` | 182 条 `review-required` 清单（111 非临时 + 71 临时） | `932cf176be07550c809db9c6c0d2370b0d2bee514898c74fc870453fa323bbb6` |
| `2026-09-28-hygiene-triage-addendum.json` | 67 条快照 finding 的逐条定性 | `f7c9c45dcb4d22f1f5f9718808bf1e0e06a5b6601ef57aa7b3ac4fd50be081a0` |

### 8.2 Task 8 结论（替代完成，不是 per-subsystem 拆分）

- Step 1（split map）完成；Step 6 的证据更新以替代路径完成；退出条件 #9 采用“**或**明确列出
  `review-required` 路径”分支，已满足。
- Step 3 的 per-subsystem 提交拆分**明确未做**：无 `feat(api-ready):` / `docs(api-ready):` 提交，
  dirty 文件由 `fc07f6b` / `d2b7619` / `7d933f2` 等整包提交吸收。计划文档 Task 8 的 Step 3/4/5
  仍保持未勾选。
- 冻结路径归宿：580 条在 `HEAD` 树中；1 条（`amz-service-spapi/.../db/schema.sql`）先加后删；
  71 条 `temporary` 从未进入后续历史。临时产物未污染 Git 历史。

### 8.3 Task 0 证据缺陷与修正（重要，接手者必须知道）

- 原 `phase0-baseline-inventory.json` 的 `statusEntryCount=631` 是错的（631 等于 plan 提交
  `17099b8` 的 insertions）；权威值是恢复快照的 **652**（325 tracked + 327 untracked）。
- 原 `statusSha256=17a449171c5aff...` 无法由快照任何文件复现；原 sidecar 记录的
  `7b460be7...` 与实际 inventory JSON 哈希
  `a928fe32bab9c8dbb4384f6a263ce0d6129252fc6d07d43c2e0e8565f6579dc4` 不符，自 `4dfde26`
  起一直错误。
- **不覆盖原 inventory**；新增修正版
  `2026-09-29-phase0-baseline-inventory-corrected.json` + `.sha256`。修正版记录 recovery head
  `3c8f21e`、652 条、raw status file hash `3b829fec...`、LF-normalised status output hash
  `08eb4587...`、manifest hash `bbb2e3a7...`，sidecar 与实际 JSON 哈希一致。
- 修正版与 652 条 split map 的 head、status hash、manifest hash、entry count 逐项一致；原
  inventory 仅保留为历史缺陷记录，计划退出条件第 1 条改由修正版证据链满足。

### 8.4 Hygiene triage 结论

- 恢复快照（`--include-untracked`）共 67 条 findings，全部非真实 Secret：
  26 条 diff 文本噪声、15 条临时脚本、23 条 allowlist 占位/测试夹具、1 条 EOF 换行噪声、
  2 条 AWS 官方示例/模型夹具。
- 快照根扫描时 untracked 路径带 `untracked/` 前缀，导致 25 条 allowlist 记录看似未放行；
  这是测量伪影。以 `untracked/` 为根复扫后剩 21 条（15 临时脚本 + 5 round 产物 + 1 EOF 噪声）。
- 当前发布树 `repository_hygiene.py --root .` = 0 findings，exit 0。

### 8.5 仍未做 / 未批准

- per-subsystem 拆分提交（历史重写 / force-push / revert + re-split）：**未获批准，未执行**。
- 当前 HEAD 的 `release.yml` 远端真实执行（tag 触发）：仍未验证；旧 `v0.1.0` 两次远端执行均失败（见 §7 远端 release 审计）。

## 9. 2026-09-29 Checkpoint 4：交接口径修正与本地复验

本轮只修改证据/交接文本，未 commit、未 push、未改写历史、未 force-push、未 revert。
已把交接文档中仍写“Task 8 未做 / 退出条件 #9 未满足”的过期口径统一为：
**Task 1–7 完成；Task 0 原证据未满足，已由 Checkpoint 5 修正版满足；Task 8 替代路径完成但 per-subsystem 拆分未做。**

### 9.1 本地复验（2026-09-29）

| 检查 | 命令 | 结果 |
|------|------|------|
| tracked 发布树 hygiene | `python tools/release/repository_hygiene.py --root .` | 0 findings，exit 0 |
| Python release 工具套件 | `python -m unittest discover -s tools/release -p 'test_*.py'` | 68 tests OK，exit 0 |
| Maven 全仓测试 | `mvn -B -ntp test -fae` | 19/19 SUCCESS，BUILD SUCCESS |
| whitespace diff | `git diff --check` | exit 0 |
| untracked-inclusive hygiene | `python tools/release/repository_hygiene.py --root . --include-untracked` | 48 findings，exit 1；均为工作区根目录运行日志，不是 tracked 发布树缺陷 |

`--include-untracked` 的 48 条结果只作本机测量记录；未删除用户日志来制造 GREEN。

### 9.2 证据哈希

- `docs/superpowers/evidence/2026-09-28-phase0-verification.md` 在 Checkpoint 4 时的 SHA-256：
  `4349ebc48449c0854d019ffd72761d352a77ae87312598b232cad797832f7ae1`
- `2026-09-28-baseline-split-map.json`、`2026-09-28-baseline-review-required.md`、
  `2026-09-28-hygiene-triage-addendum.json` 的 sidecar 均与实际文件哈希一致。
- `2026-09-28-phase0-baseline-inventory.json` 与其 sidecar 仍不一致
  （actual `a928fe32...` vs sidecar `7b460be7...`）；继续保留原 inventory，不覆盖。

### 9.3 仍待用户决策 / 批准

- Task 0 inventory 修正版证据链已生成并通过本地自检；是否纳入提交仍需用户批准。
- Task 8 是否追加真正的 per-subsystem 提交拆分；若要做，需要批准历史重写/force-push 或
  revert + re-split。
- 当前 HEAD 的 `release.yml` 远端真实执行仍需要用户批准推 tag；在此之前保持 `NOT VERIFIED`（旧 `v0.1.0` 两次失败不等同于当前 revision 已验证）。

## 10. 2026-09-29 Checkpoint 5：Task 0 修正版证据链

本轮新增并本地验证：

- `docs/superpowers/evidence/2026-09-29-phase0-baseline-inventory-corrected.json`
  SHA-256：`d47724c6c9098ee12e58e711ca3417853d29111b53e445358aeebab3d2ee0922`
- `docs/superpowers/evidence/2026-09-29-phase0-baseline-inventory-corrected.json.sha256`
  指向上述哈希，sidecar 与实际文件 MATCH。
- 修正版记录 652 条状态、recovery head `3c8f21e`、raw status file hash
  `3b829fec6aefd249bdf56faf69ac9ebae57ffb906519e77696b24d87410b7145`、
  LF-normalised status output hash
  `08eb4587f47614f52019a89454cc5069928a2fad6e14a7a0357f22f79ed9aed3`、
  manifest hash `bbb2e3a7eb2cfb178c3b4cda7b041c1e0efe0051e665e12d076998d9ae36713f`。
- 修正版与 `2026-09-28-baseline-split-map.json` 的 entry count、head、status hash、manifest
  hash 全部一致；恢复快照 10 个文件的字节数与 SHA-256 也逐项一致。
- 原 `2026-09-28-phase0-baseline-inventory.json` 及原 sidecar 保持不变，仅作为历史缺陷记录。

结论：Phase 0 退出条件第 1 条已由修正版证据链满足；本地退出条件全部满足。仍 **NOT VERIFIED**：
当前 HEAD 的 `release.yml` 远端真实执行（旧 `v0.1.0` 两次远端 run 均失败，见 §7 远端 release 审计）。per-subsystem 拆分仍未做，按退出条件第 9 条“或”分支以 182 条
`review-required` 清单满足。

## 11. 2026-09-29 Checkpoint 6：未提交候选工作树干净副本复验

现有 2026-09-28 clean clone 证据只覆盖已提交 `HEAD`，不能证明当前 12 个未提交路径。
本轮已在临时干净副本中复现并验证候选工作树：

- 源 HEAD：`95397b66be945656f965abe8587fe3de0129114e`
- `working-tree-final.patch` 仅覆盖 4 个 modified tracked 文件，不含 8 个 untracked 候选文件；
  其 SHA-256、12 路径候选复合摘要、逐文件 SHA-256/bytes 均记录在外部最终证据
  `current-worktree-evidence-final.json`。为避免自引用，本文不写死该文件或候选复合摘要的哈希；
  可用 `Get-FileHash` 独立复核。
- 复验通过：`git diff --check`、tracked hygiene 0 findings、副本内
  `--include-untracked` 0 findings、Python release tests 68/68、Maven 19/19
  modules SUCCESS、前端 typecheck/tests/build PASS（22 files / 175 tests）、release
  manifest 两次字节一致、Docker Bake 17 targets、恢复快照 10/10 文件字节数与 SHA-256 MATCH。
- release manifest 已按当前 HEAD `95397b66be945656f965abe8587fe3de0129114e` 重建并通过
  `verify`：文件 SHA-256 `1b3fbafb7399d88ad3f487b5489d5b725fe1b15321eaec6a77493d2bdbf66573`；
  内部 `manifestSha256` `38d23f970dfc738d3fd080993418b6c2db3541404e045588f9e30a71c9b1f4a7`；
  `source.commit` 为当前 HEAD；`migrations.treeSha256`
  `832b6563496e2eec059da496bf202ad74892c2ff87c4093226cff7fb53260444`，count 49；
  `frontend.treeSha256` `be9e681335058cdf2bebe06885db25d9686a50b8cc61716722ab06224bc3fa99`，
  fileCount 75。该清单使用 placeholder image digest，且候选未提交，因此只是本地确定性证据，
  不是可部署 release artifact；它也不覆盖 12 路径中的 docs/tooling 改动。
- 证据文本已写入 `docs/superpowers/evidence/2026-09-28-phase0-verification.md` 的 Checkpoint 6；
  最终文档哈希、候选补丁哈希和 12 路径复合摘要以外部 final evidence JSON 为准，不在本文内自引用。
- 注意：候选副本是按候选补丁物化后的 dirty tree，不是已提交修订的 clean checkout；
  当前 HEAD 的 `release.yml` 远端真实执行仍为 **NOT VERIFIED**（旧 `v0.1.0` 两次远端 run 均失败）。`npm audit` 另有 2 个 moderate
  的 dev-only `vitest` / `@vitest/mocker` 问题，未在本轮升级。
- 本轮仍未 commit、未 push、未 tag、未改写历史。是否显式 `git add` 这 12 个路径并提交/
  推送，仍需用户明确批准。

## 12. 2026-09-29 Checkpoint 7：最终候选副本完整复验

为消除 Checkpoint 6 中 Maven、前端和 Docker Bake 结果来自较早候选副本的歧义，已在
最终候选副本中重新执行完整验证。源 HEAD 仍为
`95397b66be945656f965abe8587fe3de0129114e`，最终副本为
`C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-final8-20260929\clone`。

- `npm ci --no-audit --no-fund`：266 packages，exit 0；干净 clone 不含被忽略的
  `node_modules`，因此前端检查必须先安装依赖。
- Maven：首次 `final3` Maven 复验未带本文 §3 已记录的 JDK AF_UNIX 前缀，触发本机
  `Unable to establish loopback connection / SocketException: Invalid argument: connect`；失败模块为
  `amz-service-ai`（1 failure）和 `amz-service-ad`（7 errors）。用不依赖仓库代码的
  `LoopbackProbe` 独立复现后，设置 `TEMP/TMP=C:\Temp\amz-uds` 并传入
  `-Djdk.net.unixdomain.tmpdir=C:\Temp\amz-uds` 重跑，19/19 reactor modules `SUCCESS`，
  `BUILD SUCCESS`，exit 0。首次失败日志保留为 `maven-final3-initial-failure.log`，环境规避后的 `final8` 当前候选全量通过日志为
  `maven-final8.log`，较早的恢复日志为 `maven-final3.log`；定向复现日志为 `maven-final3-targeted-uds.log`。
- 前端：`vue-tsc --noEmit` exit 0；Vitest 22 files / 175 tests PASS；`vite build` exit 0。
- `docker buildx bake -f docker-bake.hcl --print`：exit 0，17 targets = gateway + 15 services
  + frontend。
- 日志分别保存在最终副本根目录的 `frontend-npm-ci-final.log`、`maven-final8.log`、`maven-final3.log`、
  `maven-final3-initial-failure.log`、`maven-final3-targeted-uds.log`、
  `loopback-probe-uds-final.log`、`typecheck-final8.log`、`vitest-final8.log`、
  `vite-build-final8.log`、`docker-bake-final8.log`。
- 其中 Maven、前端三项和 Docker Bake 为在写入本轮文档字节后执行的 `final8` 复验；`npm ci` 来自同一最终副本，
  锁文件未变。Maven 的首次失败是 JVM/Windows AF_UNIX 环境问题，不是仓库代码改动；本轮当前候选的全量复验为 `final8`。`final4` 和 `final7` 仅保留为较早轮次日志，不作为当前候选证据。
  规避只改变进程环境变量和 JVM 属性，未改仓库源码。

该副本仍是按候选补丁物化后的 dirty tree，不是已提交修订的 clean checkout；本次复验不能
替代远端 `release.yml`。外部 `current-worktree-evidence-final8.json` 已在本轮文档编辑后按
同一 canonical 规则重建，逐路径 SHA-256、候选复合摘要和 tracked-diff-only patch 哈希以该
外部文件为准。仍未 commit、未 push、未 tag、未改写历史；提交/推送/发布仍需用户明确批准。


## 13. 2026-09-29 Checkpoint 8：final9 14 路径候选复验

本节取代 Checkpoint 6/7 的“12 路径 / final8”作为历史口径；Checkpoint 8 现由 Checkpoint 9 的 final11/17 路径取代，仅保留为历史证据。

- 当前候选为 **14 个路径**：**6 个 modified tracked + 8 个 untracked**。
- 当前 tracked-diff-only patch 为 `working-tree-final9.patch`，覆盖 6 个 modified tracked 文件，不含 8 个 untracked 文件。
- final9 候选副本：`C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-final9-20260929\clone`。
- final9 外部机器可读证据：`C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-current-20260929-01\current-worktree-evidence-final9.json`；独立验证日志为同目录 `independent-final9-verification.log`。
- Python release 工具套件该检查点当时为 **78 tests OK**；Checkpoint 4/6/7 中的 68 tests 是更早的历史数值。
- final9 本地复验使用当前 PATH 工具链：Maven `3.9.16`、Temurin JDK `21.0.12.1`；旧文档中的 Maven 3.9.11 固定路径只作历史记录。
- 本地复验范围仍为 dirty candidate copy，不是已提交 clean checkout；当前 HEAD 的 `release.yml` 远端真实执行仍为 **NOT VERIFIED**。actionlint 1.7.12 静态通过不能替代远端执行。
- 本轮仍未 commit、未 push、未 tag、未改写历史；是否提交、推送或发布仍需用户明确批准。

> **已知限制（2026-09-29 final11）：** `release_manifest.py` 当前为 schema v1，只记录 gateway 单镜像的 `image.ref` / `image.digest`；`release.yml` 也只把 gateway digest 传入 manifest。SBOM、Grype 扫描和 Cosign 签名各自覆盖 Docker Bake 的 17 个镜像，但 release manifest 不覆盖这 17 个 digest。若未来需要以 manifest 作为多镜像发布真值，必须升级 schema 并同步 rollback drill/tests；本轮按 limitation 记录，不宣称已实现。

## 14. 2026-09-29 Checkpoint 9：final11 17 路径候选复验

本节记录历史 final11 快照，已由 §15 的 final12 口径取代；Checkpoint 6–8 保留为更早历史证据。

- 当前候选为 **17 个路径**：**9 个 modified tracked + 8 个 untracked**。
- 当前 tracked-diff-only patch 为 `working-tree-final11.patch`，覆盖 9 个 modified tracked 文件，不含 8 个 untracked 文件。
- final11 候选副本：`C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-final11-20260929\clone`。
- final11 外部机器可读证据：`C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-current-20260929-01\current-worktree-evidence-final11.json`；独立验证日志为同目录 `independent-final11-verification.log`。
- 候选级本地复验：Python release 工具套件 **86 tests OK**（其中 `test_release_workflow.py` **24/24**）；Maven **19/19 reactor modules SUCCESS**；前端 **22 files / 175 tests PASS**，typecheck/tests/build exit 0；Docker Bake **17 targets**；`npm audit` 为 **2 moderate / 0 high / 0 critical**（dev-only vitest 链，未在本轮升级）。
- Clean Clone 复验（HEAD）为 **18/18 checks VERIFIED**，required failures 0、not verified 0；actionlint **1.7.12** 对 `ci.yml` + `release.yml` exit 0，但仍是 static-only，不替代远端执行。
- Release manifest 本地两次生成 byte-identical、`verify` PASS；按 §2.2 的限制，schema v1 只覆盖 gateway 单镜像 digest，不覆盖全部 17 镜像。
- 发布 workflow 加固已纳入候选：SBOM、Grype+CVE gate、Cosign 三处 17 镜像循环统一引用 workflow `RELEASE_IMAGES`，契约测试同时核对清单顺序与集合和 `docker-bake.hcl` 一致；quality-gate 现显式执行前端 typecheck、单元测试和生产 build。
- 本地复验范围仍为 dirty candidate copy，不是已提交 clean checkout；当前 HEAD 的 `release.yml` 远端真实执行仍为 **NOT VERIFIED**。旧 `v0.1.0` 两次远端 run 均失败，不能据此宣称当前 revision 已通过。
- 本轮仍未 commit、未 push、未 tag、未改写历史；是否提交、推送或发布仍需用户明确批准。

## 15. 2026-09-29 Checkpoint 10：final12 17 路径候选复验

本节取代 §14 的 final11 口径，作为当前候选状态；Checkpoint 6–9 保留为历史证据。

- 当前候选仍为 **17 个路径**：**9 个 modified tracked + 8 个 untracked**。
- 当前 tracked-diff-only patch 为 `working-tree-final12.patch`，使用
  `git diff --binary --full-index` 生成。首次 final12 补丁使用短 `index` 头，独立
  full-index 重算只在 1781 行中的 `index ...` 头行出现差异；旧文件保留为
  `working-tree-final12.short-index.patch`，不再作为当前证据。
- final12 候选副本：`C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-final12-20260929\clone`。
- final12 外部机器可读证据：`C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-current-20260929-01\current-worktree-evidence-final12.json`；独立验证日志为同目录 `independent-final12-verification.log`。
- 重建断言 **24/24 True**；独立复验 **48/48 checks True**、**24/24 assertions True**，最终
  `ALL_CHECKS=True`，且 full-index 补丁可在 clone 中反向应用。
- 候选级本地复验：Python release 工具套件 **86 tests OK**（其中
  `test_release_workflow.py` **24/24**）；Maven **19/19 reactor modules SUCCESS**；
  前端 **22 files / 175 tests PASS**，typecheck/build exit 0；Docker Bake **17 targets**；
  actionlint **1.7.12** 对 `ci.yml` + `release.yml` exit 0；release manifest 两次生成
  byte-identical 且 `verify` PASS；`npm audit` 为 **2 moderate / 0 high / 0 critical**
  （dev-only vitest 链，未在本轮升级）。
- Clean Clone 复验（HEAD，非 dirty candidate）为 **18/18 checks VERIFIED**，required
  failures 0、not verified 0。
- **未解决的物化限制：** `.gitattributes` 未把 `*.py` 固定为 `eol=lf`。在 Windows
  `core.autocrlf=true` 的新 clone 中，`tools/release/test_release_workflow.py` 与
  `tools/release/test_verify_clean_clone.py` 会检出为 CRLF，而 final12 候选为 LF；
  观测字节数分别为 11414 vs 11653、2688 vs 2765。故本轮 `BYTE_IDENTICAL True` 是通过
  精确复制 17 个候选路径实现的，不能表述为“fresh clone + apply patch 已逐字节复现”。
  测试与构建的语义证据仍有效。后续硬修复可新增 `*.py text eol=lf`（会成为第 18 个路径，
  必须整轮重验），或在检出前强制 `core.autocrlf=false` 并写入物化文档。在二者之一完成
  并复验前，不得宣称严格字节级 fresh-clone 复现。
- 本地复验范围仍为 dirty candidate copy，不是已提交 clean checkout；当前 HEAD 的
  `release.yml` 远端真实执行仍为 **NOT VERIFIED**。旧 `v0.1.0` 两次远端 run 均失败，
  不能据此宣称当前 revision 已通过。
- 本轮仍未 commit、未 push、未 tag、未改写历史；是否提交、推送或发布仍需用户明确批准。

## 16. 2026-09-29 Checkpoint 11：final13 17 路径候选复验

本节取代 §15 的 final12 口径，作为当前候选状态；§13–§15 保留为历史证据。

- 当前候选仍为 **17 个路径**：**9 个 modified tracked + 8 个 untracked**。
- 当前 tracked-diff-only patch 为 `working-tree-final13.patch`，使用
  `git diff --binary --full-index` 生成，不含 8 个 untracked 候选文件。
- final13 候选副本：`C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-final13-20260929\clone`。
- final13 外部机器可读证据：
  `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-current-20260929-01\current-worktree-evidence-final13.json`；
  独立验证日志为同目录 `independent-final13-verification.log`。
- 物化探针先在 final13 前发现主工作区 `docker-bake.hcl` 为 CRLF、clone 为 LF，导致
  `BYTE_IDENTICAL=False`；仅将该文件规范为 LF-only 后，17 条路径的主工作区与
  fresh-clone 物化树逐字节一致，探针结果为 `BYTE_IDENTICAL=True`。本轮未新增
  `.gitattributes` 路径。
- fresh-clone 使用 `git clone --config core.autocrlf=false --config core.eol=lf`，然后
  `git apply --check`、`git apply`，并复制 8 个 untracked 文件；严格字节复现依赖该
  显式配置，因为 `.gitattributes` 仍未把 `*.py` / `*.hcl` 全局固定为 LF。
- 重建断言 **24/24 True**；独立复验 **48/48 checks True**、**24/24 assertions True**，
  最终 `ALL_CHECKS=True`，且 full-index 补丁可在 clone 中反向应用。
- 候选级本地复验：Python release 工具套件 **87 tests OK**（其中
  `test_release_workflow.py` **24/24**）；Maven **19/19 reactor modules SUCCESS**；
  前端 **22 files / 175 tests PASS**，typecheck/build exit 0；Docker Bake **17 targets**；
  actionlint **1.7.12** 对 `ci.yml` + `release.yml` exit 0；release manifest 两次生成
  byte-identical 且 `verify` PASS；`npm audit` 为 **2 moderate / 0 high / 0 critical**
  （dev-only vitest 链，未在本轮升级）。
- Clean Clone 复验（HEAD，非 dirty candidate）为 **18/18 checks VERIFIED**，required
  failures 0、not verified 0。
- 本地复验范围仍为 dirty candidate copy，不是已提交 clean checkout；当前 HEAD 的
  `release.yml` 远端真实执行仍为 **NOT VERIFIED**。旧 `v0.1.0` 两次远端 run 均失败，
  不能据此宣称当前 revision 已通过。
- 本轮仍未 commit、未 push、未 tag、未改写历史；是否提交、推送或发布仍需用户明确批准。

## 17. 2026-09-29 Checkpoint 12：final14 17 路径候选复验

本节取代 §16 的 final13 口径，作为当时候选状态；已由 §18 的 final15 取代，§13–§16 保留为更早历史证据。

- 当时候选仍为 **17 个路径**：**9 个 modified tracked + 8 个 untracked**。
- 当时 tracked-diff-only patch 为 `working-tree-final14.patch`，使用
  `git diff --binary --full-index` 生成，不含 8 个 untracked 候选文件。
- final14 候选副本：
  `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-final14-20260929\clone`。
- final14 外部机器可读证据：
  `C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-current-20260929-01\current-worktree-evidence-final14.json`；
  独立验证日志为同目录 `independent-final14-verification.log`。
- fresh-clone 使用 `git clone --config core.autocrlf=false --config core.eol=lf`，然后
  `git apply --check`、`git apply`，并复制 8 个 untracked 文件；17 条路径与主工作区
  **17/17 byte-identical**。该轮未新增 `.gitattributes` 路径，严格字节复现仍依赖该显式配置。
- 重建断言 **24/24 True**；独立复验 **48/48 checks True**、**24/24 computed assertions True**，
  最终 `ALL_CHECKS=True`，且 full-index 补丁可在 clone 中反向应用。
- 候选级本地复验：Python release 工具套件 **88 tests OK**（其中
  `test_release_workflow.py` **25/25**）；Maven **19/19 reactor modules SUCCESS**；
  前端 **22 files / 175 tests PASS**，typecheck/build exit 0；Docker Bake **17 targets**；
  actionlint **1.7.12** 对 `ci.yml` + `release.yml` exit 0；release manifest 两次生成
  byte-identical 且 `verify` PASS；`npm audit` 为 **2 moderate / 0 high / 0 critical**
  （dev-only vitest 链，未在本轮升级）。
- `release.yml` 已加入 `environment: production`，并由新增 workflow contract test 验证
  release job 必须引用 production environment。2026-09-29 远端 GitHub API 已证实
  `environments = {"total_count":0,"environments":[]}`，不存在 production environment 或
  required reviewers；`branches/master/protection` 返回 404（无分支保护），默认
  workflow 权限为 `read`。因此该 YAML 目前只是契约，不提供发布审批门禁，属于
  repository-settings 缺口，而不是本地代码缺陷。
- Clean Clone 复验（HEAD，非 dirty candidate）为 **18/18 checks VERIFIED**，required
  failures 0、not verified 0。
- 本地复验范围仍为 dirty candidate copy，不是已提交 clean checkout；当前 HEAD 的
  `release.yml` 远端真实执行仍为 **NOT VERIFIED**。旧 `v0.1.0` 两次远端 run 均失败，
  不能据此宣称当前 revision 已通过。
- 本轮仍未 commit、未 push、未 tag、未改写历史；是否提交、推送或发布仍需用户明确批准。

## 18. 2026-09-29 Checkpoint 13：final15 17 路径候选复验（历史）

本节取代 §17 的 final14 口径，作为当时候选状态；已由 §19 的 final16 取代，§13–§17 保留为历史证据。

- 当前候选仍为 **17 个路径**：**9 个 modified tracked + 8 个 untracked**。
- 当前 tracked-diff-only patch 为 `working-tree-final15.patch`，使用 `git diff --binary --full-index` 生成，不含 8 个 untracked 候选文件。
- final15 候选副本：`C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-final15-20260929\clone`。
- final15 外部机器可读证据：`C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-current-20260929-01\current-worktree-evidence-final15.json`；独立验证日志为同目录 `independent-final15-verification.log`。
- 本轮相对 final14 更新三份文档中的最终 checkpoint/状态文本：计划文件把 final12 标为历史并新增 final15 状态；交接文档新增 §18；验证文档新增 Checkpoint 13。没有产品代码、构建配置或测试逻辑变更。候选仍为同样的 17 个路径；final14 的 patch/evidence 哈希不得复用。
- fresh-clone 使用 `git clone --config core.autocrlf=false --config core.eol=lf`，然后 `git apply --check`、`git apply`，并复制 8 个 untracked 文件；17 条路径与主工作区 **17/17 byte-identical**。本轮未新增 `.gitattributes` 路径，严格字节复现仍依赖该显式配置。
- 重建断言 **24/24 True**；独立复验 **48/48 checks True**、**24/24 computed assertions True**，最终 `ALL_CHECKS=True`，且 full-index 补丁可在 clone 中反向应用。
- 候选级本地复验：Python release 工具套件 **88 tests OK**（其中 `test_release_workflow.py` **25/25**）；Maven **19/19 reactor modules SUCCESS**；前端 **22 files / 175 tests PASS**，typecheck/build exit 0；Docker Bake **17 targets**；actionlint **1.7.12** 对 `ci.yml` + `release.yml` exit 0；release manifest 两次生成 byte-identical 且 `verify` PASS；`npm audit` 为 **2 moderate / 0 high / 0 critical**（dev-only vitest 链，未在本轮升级）。
- `release.yml` 的 `environment: production` 仍只是工作流定义和契约测试；2026-09-29 远端 GitHub API 已证实 `environments = []`、无 required reviewers、`master` 无分支保护，且默认 workflow 权限为 `read`，发布审批门禁尚未配置。
- Clean Clone 复验（HEAD，非 dirty candidate）为 **18/18 checks VERIFIED**，required failures 0、not verified 0。
- 当前 HEAD 的 `release.yml` 远端真实执行仍为 **NOT VERIFIED**。旧 `v0.1.0` 两次远端 run 均失败，不能据此宣称当前 revision 已通过。
- 本轮仍未 commit、未 push、未 tag、未改写历史；是否提交、推送或发布仍需用户明确批准。

## 19. 2026-09-29 Checkpoint 14：final16 17 路径候选复验（历史）

本节取代 §18 的 final15 口径，作为当时候选状态；已由 §20 的 final17 取代，§13–§18 保留为历史证据。

- 当前候选仍为 **17 个路径**：**9 个 modified tracked + 8 个 untracked**。
- 当前 tracked-diff-only patch 为 `working-tree-final16.patch`，使用 `git diff --binary --full-index` 生成，不含 8 个 untracked 候选文件。
- final16 候选副本：`C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-final16-20260929\clone`。
- final16 外部机器可读证据：`C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-current-20260929-01\current-worktree-evidence-final16.json`；独立验证日志为同目录 `independent-final16-verification.log`。
- 本轮相对 final14 更新三份文档：计划文件将 final12 标为历史并新增 Final15/Final16 状态；交接文档新增 §18/§19；验证文档新增 Checkpoint 13/14。没有产品代码、构建配置或测试逻辑变更。final14 和 final15 的 patch/evidence 哈希均不得复用。
- fresh-clone 使用 `git clone --config core.autocrlf=false --config core.eol=lf`，然后 `git apply --check`、`git apply`，并复制 8 个 untracked 文件；17 条路径与主工作区 **17/17 byte-identical**。本轮未新增 `.gitattributes` 路径，严格字节复现仍依赖该显式配置。
- 重建断言 **24/24 True**；独立复验 **48/48 checks True**、**24/24 computed assertions True**，最终 `ALL_CHECKS=True`，且 full-index 补丁可在 clone 中反向应用。
- 候选级本地复验：Python release 工具套件 **88 tests OK**（其中 `test_release_workflow.py` **25/25**）；Maven **19/19 reactor modules SUCCESS**；前端 **22 files / 175 tests PASS**，typecheck/build exit 0；Docker Bake **17 targets**；actionlint **1.7.12** 对 `ci.yml` + `release.yml` exit 0；release manifest 两次生成 byte-identical 且 `verify` PASS；`npm audit` 为 **2 moderate / 0 high / 0 critical**（dev-only vitest 链，未在本轮升级）。
- `release.yml` 的 `environment: production` 仍只是工作流定义和契约测试；2026-09-29 远端 GitHub API 已证实 `environments = []`、无 required reviewers、`master` 无分支保护，且默认 workflow 权限为 `read`，发布审批门禁尚未配置。
- Clean Clone 复验（HEAD，非 dirty candidate）为 **18/18 checks VERIFIED**，required failures 0、not verified 0。
- 当前 HEAD 的 `release.yml` 远端真实执行仍为 **NOT VERIFIED**。旧 `v0.1.0` 两次远端 run 均失败，不能据此宣称当前 revision 已通过。
- 本轮仍未 commit、未 push、未 tag、未改写历史；是否提交、推送或发布仍需用户明确批准。

## 20. 2026-09-29 Checkpoint 15：final17 18 路径候选复验（历史）

本节取代 §19 的 final16 口径，作为当时候选状态；已由 §21 的 final18 取代，§13–§19 保留为历史证据。

- 当前候选为 **18 个路径**：**10 个 modified tracked + 8 个 untracked**。相对 final16，新增 `Dockerfile` 的 POM 拷贝修复；该文件已规范为 LF-only。
- `Dockerfile` 原 `COPY amz-service/*/pom.xml ./amz-service/` 会把 15 个子模块 POM 扁平复制到同一目录，覆盖 `amz-service/pom.xml`，使 `dependency:go-offline` 缓存层失真；现改为逐模块显式目录拷贝。该修复不修复旧远端 `Bake images` 的 GHCR `400 Bad Request`。
- 2026-09-29 远端 GitHub API 已证实 `environments = {"total_count":0,"environments":[]}`，不存在 production environment 或 required reviewers；`branches/master/protection` 返回 404，默认 workflow 权限为 `read`。`environment: production` 目前只是 YAML 契约，发布审批门禁尚未配置。
- 当前 tracked-diff-only patch 为 `working-tree-final17.patch`，使用 `git diff --binary --full-index` 生成，不含 8 个 untracked 候选文件。
- final17 候选副本：`C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-final17-20260929\clone`。
- final17 外部机器可读证据：`C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-current-20260929-01\current-worktree-evidence-final17.json`；独立验证日志为同目录 `independent-final17-verification.log`。
- fresh-clone 使用 `git clone --config core.autocrlf=false --config core.eol=lf`，然后 `git apply --check`、`git apply`，并复制 8 个 untracked 文件；18 条路径与主工作区 **18/18 byte-identical**。`.gitattributes` 仍未把 `*.py`、`*.hcl` 或 `Dockerfile` 固定为 LF，严格字节复现仍依赖该显式配置。
- 重建断言 **24/24 True**；独立复验 **48/48 checks True**、**24/24 computed assertions True**，最终 `ALL_CHECKS=True`，且 full-index 补丁可在 clone 中反向应用。
- 候选级本地复验：Python release 工具套件 **88 tests OK**（其中 `test_release_workflow.py` **25/25**）；Maven **19/19 reactor modules SUCCESS**；前端 **22 files / 175 tests PASS**，typecheck/build exit 0；Docker Bake **17 targets**；actionlint **1.7.12** 对 `ci.yml` + `release.yml` exit 0；release manifest 两次生成 byte-identical 且 `verify` PASS；`npm audit` 为 **2 moderate / 0 high / 0 critical**（dev-only vitest 链，未在本轮升级）。
- Clean Clone 复验（HEAD，非 dirty candidate）为 **18/18 checks VERIFIED**，required failures 0、not verified 0。
- final16 的 patch/evidence 哈希因 Dockerfile 变更已作废，不得复用。当前 HEAD 的 `release.yml` 远端真实执行仍为 **NOT VERIFIED**。旧 `v0.1.0` 两次远端 run 均失败，不能据此宣称当前 revision 已通过。
- 本轮仍未 commit、未 push、未 tag、未改写历史；是否提交、推送或发布仍需用户明确批准。

## 21. 2026-09-29 Checkpoint 16：final18 18 路径文档修正与 builder 复验

本节取代 §20 的 final17 口径，作为当前候选状态；§13–§20 保留为历史证据。

- 当前候选仍为 **18 个路径**：**10 个 modified tracked + 8 个 untracked**。相对 final17，仅修正计划文档中重复的 `Final16 status addendum` 标题，并将保留的 Final16/Final17 段标为历史；没有产品代码、构建配置或测试逻辑变更。
- 当前 tracked-diff-only patch 为 `working-tree-final18.patch`，使用 `git diff --binary --full-index` 生成，不含 8 个 untracked 候选文件。
- final18 候选副本：`C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-final18-20260929-01\clone`。
- final18 外部机器可读证据：`C:\Users\Administrator\AppData\Local\Temp\amazonerp-phase0-current-20260929-01\current-worktree-evidence-final18.json`；独立验证日志为同目录 `independent-final18-verification.log`。
- fresh-clone 使用 `git clone --config core.autocrlf=false --config core.eol=lf`，然后 `git apply --check`、`git apply`，并复制 8 个 untracked 文件；18 条路径与主工作区 **18/18 byte-identical**。`.gitattributes` 仍未把 `*.py`、`*.hcl` 或 `Dockerfile` 固定为 LF，严格字节复现仍依赖该显式配置。
- final18 builder 命令 `docker build --target builder --build-arg MODULE=amz-service/amz-service-spapi -t amazonerp-phase0-builder-test:final18 .` 已在 materialised final18 clone 中执行，exit 0。final18 日志记录 `dependency:go-offline` 层的实际缓存/执行状态；由于 Dockerfile 与全部 POM 输入与 final17 **byte-identical**，实际执行该层并记录非阻塞 Maven Central SSL handshake 失败的权威证据仍是 final17 builder log，随后 final17 的 `mvn -B -q clean package -DskipTests -pl amz-service/amz-service-spapi -am` 成功，builder 镜像完成导出。final18 继承该默认 SPAPI builder 路径的构建配置验证，不等同于 runtime 镜像、全部 17 个 service build args 或远端 release workflow 验证。
- 重建断言 **24/24 True**；独立复验 **48/48 checks True**、**24/24 computed assertions True**，最终 `ALL_CHECKS=True`，且 full-index 补丁可在 clone 中反向应用。
- 候选级本地复验：Python release 工具套件 **88 tests OK**（其中 `test_release_workflow.py` **25/25**）；Maven **19/19 reactor modules SUCCESS**；前端 **22 files / 175 tests PASS**，typecheck/build exit 0；Docker Bake **17 targets**；actionlint **1.7.12** 对 `ci.yml` + `release.yml` exit 0；release manifest 两次生成 byte-identical 且 `verify` PASS；`npm audit` 为 **2 moderate / 0 high / 0 critical**（dev-only vitest 链，未在本轮升级）。
- Clean Clone 复验（HEAD，非 dirty candidate）为 **18/18 checks VERIFIED**，required failures 0、not verified 0。
- final17 的 patch/evidence 哈希因文档修正已作废，不得复用。当前 HEAD 的 `release.yml` 远端真实执行仍为 **NOT VERIFIED**。旧 `v0.1.0` 两次远端 run 均失败，不能据此宣称当前 revision 已通过。
- 本轮仍未 commit、未 push、未 tag、未改写历史；是否提交、推送或发布仍需用户明确批准。
