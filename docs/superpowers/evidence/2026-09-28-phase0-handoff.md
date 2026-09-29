# Phase 0 交接文档（2026-09-28）

> 目标读者：接手 AmazonERP 生产化升级的下一任工程师 / Agent。
> 结论先行：**Task 0–7（可发布基线）已完成并验证；Task 8（API-Ready 工作区 subsystem 拆分）未做，因此 Phase 0 尚未整体完成。连接器状态仍为 API-Ready（未联调）。当前仓库状态以 §7 为准。**

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

### 2.1 Phase 0：生产化可发布基线（Task 0–7 完成；Task 8 未做）

Phase 0 的目标不是修业务，而是让"提交、构建、镜像、迁移、回滚"可追溯、可复现。产出如下：

| Task | Commit | 成果 |
|------|--------|------|
| 0 冻结基线 | `4dfde26` | 只读基线清单 `docs/superpowers/evidence/2026-09-28-phase0-baseline-inventory.json`（含 HEAD、status SHA-256、仓库外恢复快照路径） |
| 1 仓库卫生门禁 | `37a193b` | `tools/release/repository_hygiene.py`：扫描 tracked 文件中的运行时产物 / 大文件 / 高置信度 Secret，allowlist 精确放行示例占位符 |
| 2 确定性发布清单 | `8c8f60f` | `tools/release/release_manifest.py`：同一输入生成逐字节相同的 JSON（无时间戳、无随机 UUID、无绝对路径、集合有序） |
| 3 OCI 多服务构建元数据 | `a85d541` | 可复现的多服务镜像构建配置（Docker Buildx/Bake），OCI 标签含 commit/digest/前端输入/Flyway 输入 |
| 4 CI 硬门禁 | `fc07f6b` | `.github/workflows/`：Maven 测试 + Critical Checkstyle + Python release 测试全部为强制门禁 |
| 5 签名供应链工作流 | `365ac05` | GHCR 推送 + SBOM（Syft）+ 漏洞扫描（Grype）+ 签名（Cosign）的 release workflow（**远端执行后才能标 VERIFIED**） |
| 6 安全回滚演练 | `a8f6b55` | `tools/release/rollback_drill.py`：dry-run 默认，验证回滚步骤可执行 |
| 7 Clean Clone 复验 | `d2b7619`/`7d933f2`/`f01738c` | 把散落文件补齐进 Git，`tools/release/verify_clean_clone.ps1` 可从干净 clone 一键复验 |

**Task 8 未做**：计划要求的 `docs/superpowers/evidence/2026-09-28-baseline-split-map.json` 不存在，也没有按子系统拆分的 `feat(api-ready):` / `docs(api-ready):` 提交；现有 dirty 文件由整包提交吸收。因此 Phase 0 退出条件第 9 条未满足，不能宣称 Phase 0 整体完成。依据见计划文档的 Status Addendum。

### 2.2 验证证据（截至 2026-09-29）

详见 `docs/superpowers/evidence/2026-09-28-phase0-verification.md`：

| 检查项 | 结果 | 状态 |
|--------|------|------|
| Maven 全仓测试 | 19/19 SUCCESS | VERIFIED |
| Critical Checkstyle | 0 violations | VERIFIED |
| Python release 工具套件（7 modules） | 68 tests PASS | VERIFIED（2026-09-29 复跑） |
| 发布 workflow 契约测试 | 8/8 GREEN | VERIFIED（仅契约，非远端执行） |
| 回滚演练测试 | 7/7 GREEN | VERIFIED（dry-run） |
| 前端测试 + 构建 | 22 files / 175 tests PASS；`npm run build` PASS | VERIFIED（Node v22.22.2 / npm 10.9.7） |
| Clean Clone 复验 | git clone/checkout/clean-tree/python/maven/npm-ci/frontend-build 通过 | 7/7 VERIFIED |

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

1. **Task 8（subsystem 拆分）— 需用户决策**：若确认要做，按计划文档 Task 8 生成 `baseline-split-map.json` 并按子系统补提交；若不做，Phase 0 退出条件第 9 条继续保持未满足，不能宣称 Phase 0 整体完成。
2. **`release.yml` 远端真实执行 — 需用户批准**：推 tag 会触发 GHCR push、17 次 Grype、`cve_gate` 和 Cosign；未执行前该 workflow 仍为 NOT VERIFIED。`ci.yml` 的 9/9 绿不能替代。
3. **删除 GHCR 上未签名的 `0.1.0` package — blocked-on-user**：当前 token 无 `packages:delete` 权限，只能在 GitHub UI 手动删除。
4. **Nacos 配置中心接入 — 架构级，另行排期**：需要引入 Nacos config starter 并调整 32 个 `bootstrap.yml`。

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
| Task 8 未做 | Phase 0 退出条件第 9 条未满足，不能宣称整体完成 | 用户决定是否补 subsystem 拆分；未做则如实保留未完成状态 |
| `release.yml` 未在远端跑过 | 平台差异可能在真实 runner 暴露；供应链产物未验证 | 经用户批准后推 tag 真实执行 |
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
3. **actionlint — 已完成**：actionlint **1.7.12**，`ci.yml` + `release.yml` 均 0 问题。
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

- `release.yml` 远端真实执行（GHCR push + SBOM + 17 次 grype + 门禁 + Cosign 签名）
  — 至今没有 push 过 tag，本地与静态校验均不能替代。
- `cfa6149` 前端瘦身的远端构建/Grype — §13.9 已补本地证据，但 `release.yml` 仍未真实执行。
- Task 8：API-Ready 工作区 subsystem 拆分 — 未做，Phase 0 退出条件第 9 条未满足。
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
