# Phase 0 交接文档（2026-09-28）

> 目标读者：接手 AmazonERP 生产化升级的下一任工程师 / Agent。
> 结论先行：**Phase 0（可发布基线）已完成并全部本地验证通过，仓库已清理干净；下一步是补齐前端构建验证、推送远端跑通 CI，然后进入 Phase 1。**

## 1. 项目当前状态

| 项 | 值 |
|----|----|
| 仓库路径 | `C:\Users\Administrator\Desktop\AmazonERP` |
| 分支 | `codex/api-ready-connectors` |
| HEAD | `5ca0fdd` + 本次交接提交 |
| 工作区 | `git status --porcelain` = 0（干净） |
| 连接器状态 | **API-Ready（未联调）**——没有真实 Amazon 凭证，不得宣称已接通 SP-API |
| 技术栈 | Java 17 / Spring Boot 3.3.5 / Maven 3.9.11、Vue 3 / Vite / Vitest、MySQL 8 / Flyway 10.20、Python 3.11 标准库（release 工具链） |

## 2. 工作成果

### 2.1 Phase 0：生产化可发布基线（8 个 Task 全部完成）

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

### 2.2 验证证据（全部通过）

详见 `docs/superpowers/evidence/2026-09-28-phase0-verification.md`：

| 检查项 | 结果 | 状态 |
|--------|------|------|
| Maven 全仓测试 | 19/19 SUCCESS | VERIFIED |
| Critical Checkstyle | 0 violations | VERIFIED |
| Python release 工具套件（hygiene/manifest/services） | 全部 PASS | VERIFIED |
| 发布 workflow 契约测试 | 8/8 GREEN | VERIFIED（仅契约，非远端执行） |
| 回滚演练测试 | 7/7 GREEN | VERIFIED（dry-run） |
| Clean Clone 复验 | git clone/checkout/clean-tree/python/maven 通过；npm 未装 | 5/7 VERIFIED，npm 2 项 NOT VERIFIED |

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

### P0 — 收尾 Phase 0 验证缺口

1. **安装 Node.js（含 npm）** → 重跑 `tools/release/verify_clean_clone.ps1` → 让 `npm-ci` / `frontend-build` 两项从 NOT VERIFIED 变为 VERIFIED。
2. **推送远端并跑通 GitHub Actions**（release.yml + governance gates）→ 首次远端成功后，才能把 runbook 中对应 NOT VERIFIED 改为 VERIFIED。
3. （可选）安装 `actionlint` 对 release.yml 做本地静态校验，或在证据文档中明确声明 NOT VERIFIED。
4. 核对 Phase 0 计划文档中的 checkbox 是否需要与实际完成状态同步。

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
| 本机无 npm | 前端构建从未在本机复验过 | P0 第 1 步装 Node 后立即复验 |
| release.yml 未在远端跑过 | workflow 可能在真实 runner 上暴露平台差异（PowerShell/Buildx/Cosign 版本） | P0 第 2 步推送后观察首次 run |
| 无真实 Amazon 凭证 | SP-API 层全部是 mock/契约测试 | Phase 1 需用户主动提供凭证，先 sandbox |
| Java/Tomcat 依赖版本 | 可能存在未披露 CVE | Phase 2 引入 Grype 扫描门禁后闭环 |