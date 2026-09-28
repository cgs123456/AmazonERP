# AmazonERP 生产化 Phase 0 可发布基线实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不清理、不重置现有工作区改动的前提下，把 AmazonERP 从“本地可构建的 API-Ready 工程”提升为“提交、构建、镜像、迁移和回滚可追溯的可发布基线”。

**Architecture:** 先用只读恢复快照和可执行仓库卫生门禁保护现有改动，再新增纯 Python 标准库工具生成确定性发布清单与回滚演练结果；随后把 OCI 多服务构建、CI 硬门禁、GHCR/SBOM/漏洞扫描/签名接入发布流程，最后在干净 clone 中复验。Phase 0 不修业务逻辑、不调用 Amazon、不宣称真实联调或生产上线完成。

**Tech Stack:** Java 17、Spring Boot 3.3.5、Maven 3.9.11、Vue 3、Vite、Vitest、Python 3.11 标准库、Docker Buildx/Bake、GitHub Actions、Flyway 10.20、MySQL 8、Syft、Grype、Cosign。

**Spec:** `docs/superpowers/specs/2026-09-28-full-project-review.md` §11 Phase 0、§12.1、§17；`docs/superpowers/specs/2026-09-26-production-upgrade-execution-matrix.md` §1、§3 Wave 0、REL-01/REL-02/REL-03/REL-05/REL-06/REL-10。

## Global Constraints

- 当前基线：分支 `codex/api-ready-connectors`、HEAD `3c8f21ed21c2cc77cbf08d1f12ddd4256265ac25`；工作区高度 dirty 是事实，不得用 `git reset --hard`、`git clean`、批量删除恢复“干净”。
- 恢复快照必须保留在仓库外；若快照缺失或 manifest 不匹配，停止后续清理/拆分。
- 所有本计划新增或修改的文件必须用独立临时 Git index 提交，避免把既有 23 个 staged 文件或 307 个 unstaged 文件混入 Phase 0 提交。
- PowerShell 是唯一 shell；禁止 `bash`/WSL。Maven 命令使用 `C:\Users\Administrator\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd`，并设 `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp`。
- 本机没有 `npm`/`npx`；前端验证使用 `node.exe node_modules\vue-tsc\bin\vue-tsc.js --noEmit`、`node.exe node_modules\vitest\vitest.mjs run`、`node.exe node_modules\vite\bin\vite.js build`。
- 发布清单不得含当前时间、随机 UUID、绝对路径或未排序集合；同一 commit、镜像 digest、前端输入和 Flyway 输入必须得到逐字节相同的 JSON。
- 仓库卫生扫描默认只扫描 Git tracked 文件；本地冻结模式用 `--include-untracked`。允许示例占位符，但禁止真实私钥、云访问密钥、GitHub token、非占位密码和高置信度 Secret。
- `.github/workflows/release.yml` 的远端执行、GHCR push、SBOM、扫描、签名和 rollback drill 在未实际运行前只能标记 `NOT VERIFIED`。
- 未获得真实 Amazon 授权和沙箱/生产证据前，连接器状态最高仍为 API-Ready（未联调）；Phase 0 完成不等于生产就绪。
- 每个 Task 必须先写失败测试、验证 RED、写最小实现、验证 GREEN，再提交；提交只包含该 Task 的路径。

---

### Task 0: 冻结现有 API-Ready 基线

**Files:**
- Create: `/docs/superpowers/evidence/2026-09-28-phase0-baseline-inventory.json`
- Create: `/docs/superpowers/evidence/2026-09-28-phase0-baseline-inventory.sha256`
- Read: repository status、已有恢复快照 `/AmazonERP-recovery-20260928-092159`

**Interfaces:**
- Consumes: 现有恢复快照和 Git 状态。
- Produces: 只读基线清单，含 HEAD、分支、status 输出 SHA-256、恢复快照路径、manifest SHA-256、生成命令；后续任务以此为“不得丢失”的冻结边界。

- [ ] **Step 1: 验证恢复快照存在且 manifest 哈希一致**

```powershell
$snapshot = 'C:\Users\Administrator\Desktop\AmazonERP-recovery-20260928-092159'
$manifest = Join-Path $snapshot 'manifest.tsv'
$expected = 'BBB2E3A7EB2CFB178C3B4CDA7B041C1E0EFE0051E665E12D076998D9AE36713F'
$actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $manifest).Hash
if ($actual -ne $expected) { throw "Recovery manifest drift: $actual" }
```

- [ ] **Step 2: 生成确定性只读状态清单**

```powershell
$status = git status --porcelain=v2 --branch
$statusHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes(($status -join "`n") + "`n")))
$inventory = [ordered]@{
  schemaVersion = 1
  branch = (git branch --show-current)
  head = (git rev-parse HEAD)
  statusEntryCount = ($status | Measure-Object).Count
  statusSha256 = $statusHash
  recoverySnapshot = 'C:\Users\Administrator\Desktop\AmazonERP-recovery-20260928-092159'
  recoveryManifestSha256 = 'BBB2E3A7EB2CFB178C3B4CDA7B041C1E0EFE0051E665E12D076998D9AE36713F'
}
$json = ($inventory | ConvertTo-Json -Depth 5) + "`n"
[IO.File]::WriteAllText((Join-Path (Get-Location) 'docs/superpowers/evidence/2026-09-28-phase0-baseline-inventory.json'), $json, [Text.UTF8Encoding]::new($false))
```

- [ ] **Step 3: 记录证据文件哈希**

```powershell
$file = 'docs/superpowers/evidence/2026-09-28-phase0-baseline-inventory.json'
$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash.ToLowerInvariant()
"$hash  $file`n" | Set-Content -NoNewline -Encoding utf8NoBOM 'docs/superpowers/evidence/2026-09-28-phase0-baseline-inventory.sha256'
```

- [ ] **Step 4: 用临时 index 提交 Task 0**

```powershell
$idx = Join-Path $env:TEMP ('amz-index-' + [guid]::NewGuid())
$env:GIT_INDEX_FILE = $idx
git read-tree HEAD
git add -- docs/superpowers/evidence/2026-09-28-phase0-baseline-inventory.json docs/superpowers/evidence/2026-09-28-phase0-baseline-inventory.sha256
git commit -m "chore(release): freeze phase 0 baseline inventory"
Remove-Item Env:GIT_INDEX_FILE
Remove-Item -LiteralPath $idx
```

---

### Task 1: 仓库卫生扫描器

**Files:**
- Create: `tools/release/__init__.py`
- Create: `tools/release/repository_hygiene.py`
- Create: `tools/release/test_repository_hygiene.py`
- Create: `tools/release/hygiene-allowlist.json`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: repository root, Git tracked/untracked file list, allowlist JSON.
- Produces:
  - `Finding(rule: str, path: str, detail: str)`
  - `scan_repository(root: Path, include_untracked: bool, allowlist: HygieneAllowlist) -> list[Finding]`
  - CLI `python tools/release/repository_hygiene.py --root . [--include-untracked] [--json PATH]`，exit `0` 无阻断项，`1` 有阻断项，`2` 参数/配置错误。

- [ ] **Step 1: 写失败测试**

在 `tools/release/test_repository_hygiene.py` 写 7 个测试：根目录 `_run.log`、`round33-result.json`、`.patch_tmp.py`、12 MiB 文件被标记；GitHub token 被标记；`${DB_PASSWORD}` 和 `example-secret` 不标记；allowlist 中精确 path+rule 被允许；同文件新出现未允许 Secret 仍标记。

```python
def test_flags_root_round_artifact_and_secret_placeholder_is_allowed(self):
    root = self.make_repo(files={
        "_run.log": "boot\n",
        "round33-result.json": "{}\n",
        ".patch_tmp.py": "pass\n",
        "safe.env": "DB_PASSWORD=${DB_PASSWORD}\n",
        "unsafe.env": "GITHUB_TOKEN=" + "gh" + "p_" + "abcdefghijklmnopqrstuvwxyzABCDEFGHIJ" + "\n",
    })
    findings = scan_repository(root, include_untracked=True, allowlist=HygieneAllowlist.empty())
    codes = {(f.rule, f.path) for f in findings}
    self.assertIn(("root-runtime-artifact", "_run.log"), codes)
    self.assertIn(("root-round-artifact", "round33-result.json"), codes)
    self.assertIn(("temporary-script", ".patch_tmp.py"), codes)
    self.assertIn(("github-token", "unsafe.env"), codes)
    self.assertNotIn(("secret-like-assignment", "safe.env"), codes)
```

- [ ] **Step 2: 运行确认 RED**

Run: `python -m unittest tools.release.test_repository_hygiene -v`  
Expected: `ImportError` 或 `ModuleNotFoundError`，对应 `repository_hygiene` 尚不存在。

- [ ] **Step 3: 最小实现扫描器**

实现规则：

```python
FORBIDDEN_ROOT_PATTERNS = (
    (re.compile(r"^_[^/]*\.log$", re.I), "root-runtime-artifact"),
    (re.compile(r"^mvn-[^/]*\.log$", re.I), "root-maven-log"),
    (re.compile(r"^round\d+[^/]*\.(json|log)$", re.I), "root-round-artifact"),
)
FORBIDDEN_ANY_PATH_PATTERNS = (
    (re.compile(r"(^|/)\.patch[^/]*\.py$", re.I), "temporary-script"),
    (re.compile(r"(^|/)[^/]+\.(tmp|bak|orig|rej)$", re.I), "temporary-file"),
)
SECRET_PATTERNS = (
    (re.compile(r"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b"), "aws-access-key"),
    (re.compile(r"\bgh[pousr]_[A-Za-z0-9_]{20,}\b"), "github-token"),
    (re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"), "private-key"),
)
PLACEHOLDER_MARKERS = ("${", "{{", "<", "example", "placeholder", "change-me", "replace", "dummy", "mock", "test")
LARGE_FILE_BYTES = 10 * 1024 * 1024
```

扫描文件内容时按 UTF-8 解码；二进制只做大小规则；JSON allowlist 以 `path + rule + sha256` 精确放行。`--json` 输出排序后的 findings、计数和规则版本。`.gitignore` 增加根目录临时产物模式 `_*.log`、`mvn-*.log`、`round*-*.json`、`*.tmp`、`*.bak`、`*.orig`、`*.rej`，但不得忽略 `docs/`、`tools/release/` 或已受版本控制文件。

- [ ] **Step 4: 运行确认 GREEN**

Run: `python -m unittest tools.release.test_repository_hygiene -v`  
Expected: 7 tests PASS。

- [ ] **Step 5: 对当前仓库执行本地冻结扫描，不自动删除**

Run: `python tools/release/repository_hygiene.py --root . --include-untracked --json docs/superpowers/evidence/2026-09-28-hygiene-baseline.json`  
Expected: 非零 exit 可接受；报告必须完整列出临时脚本、根日志、大文件和潜在 Secret。该报告用于后续人工拆分，不直接授权删除；如发现真实 Secret，必须轮换，不能只删文件。

- [ ] **Step 6: 用临时 index 提交 Task 1**

```powershell
$idx = Join-Path $env:TEMP ('amz-index-' + [guid]::NewGuid())
$env:GIT_INDEX_FILE = $idx
git read-tree HEAD
git add -- tools/release/__init__.py tools/release/repository_hygiene.py tools/release/test_repository_hygiene.py tools/release/hygiene-allowlist.json .gitignore docs/superpowers/evidence/2026-09-28-hygiene-baseline.json
git commit -m "chore(release): add repository hygiene gate"
Remove-Item Env:GIT_INDEX_FILE
Remove-Item -LiteralPath $idx
```

---

### Task 2: 确定性发布清单

**Files:**
- Create: `tools/release/release_manifest.py`
- Create: `tools/release/test_release_manifest.py`
- Create: `tools/release/testdata/release_manifest/fixture.json`
- Create: `docs/superpowers/runbooks/release-manifest.md`

**Interfaces:**
- Consumes: explicit `--commit`, `--version`, `--image-ref`, `--image-digest`, repository frontend files, `src/main/resources/db/migration/V*.sql`。
- Produces:
  - `build_manifest(root: Path, commit: str, version: str, image_ref: str, image_digest: str) -> dict`
  - `canonical_json(data: dict) -> bytes`
  - CLI `python tools/release/release_manifest.py build --root . --commit <40hex> --version <semver> --image-ref <ref> --image-digest sha256:<64hex> --output <path>`
  - CLI `python tools/release/release_manifest.py verify --manifest <path> --root .`
  - `manifestSha256` 用于发布记录和部署准入。

- [ ] **Step 1: 写失败测试**

`test_release_manifest.py` 必须覆盖：相同输入生成 byte-identical JSON；commit 不是 40 位小写 hex 时报错；digest 不是 `sha256:<64hex>` 时报错；前端 tree digest 随 `amz-frontend/src` 内容变化；不随 `node_modules`、`dist`、mtime 变化；49 个当前 Flyway 文件全部进入迁移清单；每个迁移项含 `module`、`path`、`sha256`；manifest 不含绝对路径、当前时间和随机字段。

```python
def test_manifest_is_byte_identical_for_same_input(self):
    first = canonical_json(build_manifest(self.root, "a" * 40, "1.0.0", "ghcr.io/acme/amz:1.0.0", "sha256:" + "b" * 64))
    second = canonical_json(build_manifest(self.root, "a" * 40, "1.0.0", "ghcr.io/acme/amz:1.0.0", "sha256:" + "b" * 64))
    self.assertEqual(first, second)
```

- [ ] **Step 2: 运行确认 RED**

Run: `python -m unittest tools.release.test_release_manifest -v`  
Expected: `ModuleNotFoundError: tools.release.release_manifest`。

- [ ] **Step 3: 实现确定性 manifest**

JSON 顶层固定为：

```json
{
  "schemaVersion": 1,
  "source": {"commit": "...", "version": "..."},
  "image": {"ref": "...", "digest": "sha256:..."},
  "frontend": {"treeSha256": "...", "fileCount": 0},
  "migrations": {"treeSha256": "...", "count": 0, "items": []},
  "manifestSha256": "..."
}
```

`canonical_json` 使用 `json.dumps(..., sort_keys=True, separators=(",", ":"), ensure_ascii=False) + "\n"`；frontend digest 只纳入 `package.json`、`package-lock.json`、`tsconfig*.json`、`vite.config.*`、`index.html` 和 `src/**` 的文件，路径统一 `/` 并按 bytes 排序；每个条目哈希 `relative_path + NUL + content_sha256` 后再次 SHA-256。migration digest 同样排除 `target/`，路径按字典序排序，先计算完整 manifest（`manifestSha256` 临时设为空字符串），再把规范 JSON 去掉该字段后的 SHA-256 写入 `manifestSha256`，重新序列化一次。

- [ ] **Step 4: 运行确认 GREEN**

Run: `python -m unittest tools.release.test_release_manifest -v`  
Expected: 全部 PASS；当前仓库迁移数必须为 49，若不是则报告实际计数并停止，不修改数字迎合测试。

- [ ] **Step 5: 生成示例并写 runbook**

Run: `python tools/release/release_manifest.py build --root . --commit 3c8f21ed21c2cc77cbf08d1f12ddd4256265ac25 --version 0.1.0 --image-ref ghcr.io/example/amazonerp-spapi:0.1.0 --image-digest sha256:0000000000000000000000000000000000000000000000000000000000000000 --output docs/examples/release-manifest.example.json`  
Runbook 必须说明：发布清单是证据，不替代镜像签名；placeholder digest 示例不得用于部署；verify 必须从干净 clone 执行；提交、tag、CI run URL、镜像 digest 必须回填到发布记录。

- [ ] **Step 6: 用临时 index 提交 Task 2**

```powershell
$idx = Join-Path $env:TEMP ('amz-index-' + [guid]::NewGuid())
$env:GIT_INDEX_FILE = $idx
git read-tree HEAD
git add -- tools/release/release_manifest.py tools/release/test_release_manifest.py tools/release/testdata/release_manifest/fixture.json docs/superpowers/runbooks/release-manifest.md docs/examples/release-manifest.example.json
git commit -m "chore(release): add deterministic release manifest"
Remove-Item Env:GIT_INDEX_FILE
Remove-Item -LiteralPath $idx
```

---

### Task 3: OCI 多服务构建元数据

**Files:**
- Create: `tools/release/services.json`
- Create: `tools/release/test_services_manifest.py`
- Create: `docker-bake.hcl`
- Modify: `Dockerfile`
- Modify: `docs/superpowers/runbooks/release-manifest.md`

**Interfaces:**
- Consumes: 15 个 `amz-service/*` 子模块、`amz-gateway`、各模块端口、Dockerfile build args。
- Produces: `services.json` schema v1，字段 `name`、`module`、`port`、`healthPath`、`imageSuffix`；Bake targets `gateway` 和每个 service，共用 `docker-bake.hcl` variables；Dockerfile 接收 `VERSION`、`VCS_REF`、`BUILD_DATE` 并写入 OCI labels。

- [ ] **Step 1: 写失败测试**

`test_services_manifest.py` 必须断言：17 个构建目标（1 gateway + 15 service + frontend）；每个 `module` 在文件系统中存在；每个 `port` 在 1024..65535；name/suffix 唯一；每个服务的 `application*.yml` 默认端口与 `services.json` 一致；`Dockerfile` 含 `org.opencontainers.image.revision`、`.version`、`.created`、`.source` labels；`docker-bake.hcl` 的 target 集与 JSON 完全一致；frontend target 使用 `amz-frontend/Dockerfile`（若不存在则在 Task 内新增）。

- [ ] **Step 2: 运行确认 RED**

Run: `python -m unittest tools.release.test_services_manifest -v`  
Expected: FAIL，`services.json` 和 `docker-bake.hcl` 不存在。

- [ ] **Step 3: 填写准确的 service metadata**

从每个模块的 `application.yml` 按事实读取端口；无法证明默认端口时先将测试设为 RED，修正配置或 metadata，不猜测。`services.json` 使用稳定排序：

```json
{
  "schemaVersion": 1,
  "services": [
    {"name": "gateway", "module": "amz-gateway", "port": 10010, "healthPath": "/actuator/health", "imageSuffix": "gateway"},
    {"name": "spapi", "module": "amz-service/amz-service-spapi", "port": 8096, "healthPath": "/actuator/health", "imageSuffix": "spapi"}
  ]
}
```

- [ ] **Step 4: 实现 Bake targets 和 OCI labels**

`docker-bake.hcl` 定义 `variable "REGISTRY"`、`variable "TAG"`、`variable "GIT_SHA"`、`variable "BUILD_DATE"`；target `common` 设置 `context = "."`、`dockerfile = "Dockerfile"`、`platforms = ["linux/amd64", "linux/arm64"]`、labels；每服务 target 继承 common 并传 `MODULE`/`PORT`。Dockerfile 在最终 stage 添加：

```dockerfile
ARG VERSION=dev
ARG VCS_REF=unknown
ARG BUILD_DATE=unknown
LABEL org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.source="https://github.com/cgs123456/AmazonERP"
```

多架构参数化只代表构建配方可表达；未实际 build/push 前不得声称镜像已发布。

- [ ] **Step 5: 运行确认 GREEN**

Run: `python -m unittest tools.release.test_services_manifest -v`  
若本机 Docker/Buildx 可用：Run: `docker buildx bake --print`，Expected: 所有 target 可解析。若工具不可用，记录 `NOT VERIFIED`，不得伪造成功。

- [ ] **Step 6: 用临时 index 提交 Task 3**

```powershell
$idx = Join-Path $env:TEMP ('amz-index-' + [guid]::NewGuid())
$env:GIT_INDEX_FILE = $idx
git read-tree HEAD
git add -- tools/release/services.json tools/release/test_services_manifest.py docker-bake.hcl Dockerfile docs/superpowers/runbooks/release-manifest.md amz-frontend/Dockerfile
git commit -m "build(release): add reproducible multi-service OCI metadata"
Remove-Item Env:GIT_INDEX_FILE
Remove-Item -LiteralPath $idx
```

---

### Task 4: CI 硬门禁与关键 Checkstyle

**Files:**
- Create: `checkstyle-critical.xml`
- Create: `amz-service/amz-service-spapi/src/test/java/com/amz/deploy/ReleaseGovernanceContractTest.java`
- Modify: `.github/workflows/ci.yml`
- Modify: `docs/superpowers/runbooks/release-manifest.md`

**Interfaces:**
- Consumes: Task 1/2/3 的 CLI、services manifest、CI workflow。
- Produces: CI jobs `hygiene`、`release-manifest`、严格 critical Checkstyle；`test` 在 clean checkout 中执行全仓 `mvn -B test -fae`；禁止任何承载必需门禁的 job/step 使用 `continue-on-error: true`；发布门禁覆盖所有新增工具。

- [ ] **Step 1: 写失败契约测试**

`ReleaseGovernanceContractTest` 读取 CI YAML 和工具文件，断言：CI 对 push/PR 执行 `repository_hygiene.py`；执行 `test_repository_hygiene.py`、`test_release_manifest.py`、`test_services_manifest.py`；`test` job 不设置 `continue-on-error`；`checkstyle` job 使用 `checkstyle-critical.xml` 且不设置 `continue-on-error`；`release-manifest` 生成结果并上传 artifact；每个必需 job 都出现在 `docker` 的 `needs` 中（除不可用的 release-only job）。

- [ ] **Step 2: 运行确认 RED**

Run:

```powershell
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp'
& 'C:\Users\Administrator\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd' -B -ntp -pl amz-service/amz-service-spapi -am test '-Dtest=ReleaseGovernanceContractTest'
```

Expected: compile/test FAIL，CI 尚未满足契约。

- [ ] **Step 3: 建立可满足的 critical Checkstyle**

`checkstyle-critical.xml` 只放当前代码可稳定满足的规则：`FileTabCharacter`、`RegexpSingleline` 禁止尾随空白、`NewlineAtEndOfFile`、`AvoidStarImport`（按当前实际违规清零或保留明确 baseline，不允许规则存在却继续违规）、`OneTopLevelClass`、`OuterTypeFilename`。先运行：

```powershell
& $mvn -B -ntp checkstyle:check '-Dcheckstyle.config.location=checkstyle-critical.xml'
```

若现有代码有违规，按规则逐项修最小代码或删掉并未真正执行的规则，不得把失败规则留在“强制门禁”里。完整 `google_checks.xml` 可继续作为 informational job，但必须改名并明确 `continue-on-error: true`，不能被描述为通过。

- [ ] **Step 4: 改造 CI 工作流**

在新 job 中执行：

```yaml
- name: Repository hygiene
  run: python tools/release/repository_hygiene.py --root .
- name: Release tool tests
  run: |
    python -m unittest tools.release.test_repository_hygiene tools.release.test_release_manifest tools.release.test_services_manifest -v
- name: Build deterministic manifest
  env:
    RELEASE_COMMIT: ${{ github.sha }}
    RELEASE_IMAGE_REF: ghcr.io/${{ github.repository }}:${{ github.sha }}
    RELEASE_IMAGE_DIGEST: sha256:0000000000000000000000000000000000000000000000000000000000000000
  run: python tools/release/release_manifest.py build --root . --commit "$RELEASE_COMMIT" --version "0.0.0-ci" --image-ref "$RELEASE_IMAGE_REF" --image-digest "$RELEASE_IMAGE_DIGEST" --output release-manifest.json
```

CI 中的 placeholder digest 只能证明工具可运行；不得作为发布产物。增加 `clean-tree` step：在 build/test 完成后执行 `git diff --exit-code` 与 `git ls-files --others --exclude-standard`，确保测试不写 tracked 工作区。若 synthetic-data 生成的 `out/` 被忽略，不将其误判为源码污染。

- [ ] **Step 5: 运行本地 CI 等价验证**

Run:

```powershell
python -m unittest tools.release.test_repository_hygiene tools.release.test_release_manifest tools.release.test_services_manifest -v
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp'
& 'C:\Users\Administrator\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd' -B -ntp test -fae
```

Expected: Python 全部 PASS；Maven 18/18 modules SUCCESS。若当前脏工作区导致 hygiene 失败，记录真实 findings，不改扫描器掩盖问题。

- [ ] **Step 6: 用临时 index 提交 Task 4**

```powershell
$idx = Join-Path $env:TEMP ('amz-index-' + [guid]::NewGuid())
$env:GIT_INDEX_FILE = $idx
git read-tree HEAD
git add -- checkstyle-critical.xml amz-service/amz-service-spapi/src/test/java/com/amz/deploy/ReleaseGovernanceContractTest.java .github/workflows/ci.yml docs/superpowers/runbooks/release-manifest.md
git commit -m "ci(release): enforce phase 0 governance gates"
Remove-Item Env:GIT_INDEX_FILE
Remove-Item -LiteralPath $idx
```

---

### Task 5: GHCR、SBOM、漏洞扫描与签名发布工作流

**Files:**
- Create: `.github/workflows/release.yml`
- Create: `tools/release/test_release_workflow.py`
- Create: `docs/superpowers/runbooks/release-candidate-checklist.md`

**Interfaces:**
- Consumes: protected tag `v*.*.*`、Task 3 的 `docker-bake.hcl`、Task 2 manifest CLI、GitHub OIDC。
- Produces: 以 tag 触发的发布工作流，输出不可变 GHCR image digests、Syft SPDX JSON、Grype JSON/SARIF、Cosign signature、release manifest artifact；发布前执行全仓测试、hygiene、critical Checkstyle。

- [ ] **Step 1: 写失败测试**

`test_release_workflow.py` 解析 YAML 文本并断言：触发器只有 `workflow_dispatch` 和 `push.tags`；permissions 为 `contents: write`、`packages: write`、`id-token: write`；使用 `docker/setup-buildx-action`、`docker/login-action`、`docker/bake-action`、`anchore/sbom-action`、`anchore/scan-action`、`sigstore/cosign-installer`；禁止 `latest` 作为唯一 tag；扫描 `HIGH`/`CRITICAL` 且 `fail-build: true`；manifest 使用 bake 输出的 digest，而不是重新 build；release 资产含 manifest、SBOM、scan report、checksums；不存在明文 secret 或 `|| true`。

- [ ] **Step 2: 运行确认 RED**

Run: `python -m unittest tools.release.test_release_workflow -v`  
Expected: FAIL，`.github/workflows/release.yml` 不存在。

- [ ] **Step 3: 实现发布工作流**

流程固定为：checkout full history → setup Java/Node/Python → hygiene/tests → `mvn test -fae` → frontend checks/build → Buildx → Bake push → 从 `bake-metadata.json` 提取 digest → Syft per image → Grype fail on High/Critical → Cosign keyless sign digest → release manifest → `sha256sum` → upload artifacts。标签必须是语义化 tag，镜像 tag 至少包含不可变 commit SHA；禁止用 `latest` 取代 digest。GitHub OIDC 权限只授予发布 job。

- [ ] **Step 4: 运行确认 GREEN（本地静态）**

Run: `python -m unittest tools.release.test_release_workflow -v`  
若本机有 `actionlint`：Run: `actionlint .github/workflows/release.yml`；若无该工具，记录 `actionlint NOT VERIFIED`，不得安装后把本地静态结果称为 GitHub Actions 成功。

- [ ] **Step 5: 写发布候选 runbook**

Runbook 逐项列出：tag 规则、分支保护、必需环境审批、GHCR 权限、OIDC subject、故障时如何删除错误 tag（不动已发布 digest）、SBOM/扫描/签名的验证命令、release manifest 回填、失败后如何停止发布。明确 GitHub workflow 只有在远端实际通过后才可标记 VERIFIED。

- [ ] **Step 6: 用临时 index 提交 Task 5**

```powershell
$idx = Join-Path $env:TEMP ('amz-index-' + [guid]::NewGuid())
$env:GIT_INDEX_FILE = $idx
git read-tree HEAD
git add -- .github/workflows/release.yml tools/release/test_release_workflow.py docs/superpowers/runbooks/release-candidate-checklist.md
git commit -m "ci(release): add signed supply-chain workflow"
Remove-Item Env:GIT_INDEX_FILE
Remove-Item -LiteralPath $idx
```

---

### Task 6: 回滚演练脚本与 Runbook

**Files:**
- Create: `tools/release/rollback_drill.py`
- Create: `tools/release/test_rollback_drill.py`
- Create: `docs/superpowers/runbooks/release-rollback.md`
- Modify: `docs/superpowers/runbooks/release-candidate-checklist.md`

**Interfaces:**
- Consumes: release manifest、Kubernetes namespace、deployment names、previous/current digest、Flyway 当前版本、dry-run flag。
- Produces:
  - `RollbackPlan(current_manifest: dict, previous_manifest: dict, namespace: str) -> dict`
  - CLI `python tools/release/rollback_drill.py plan --current <json> --previous <json> --namespace <ns> --output <path>`
  - CLI `python tools/release/rollback_drill.py execute --plan <json> [--apply]`
  - 默认 dry-run；`--apply` 才调用 `kubectl`。若镜像 digest 或 Flyway 版本不兼容，exit `2` 并拒绝生成可执行步骤。

- [ ] **Step 1: 写失败测试**

覆盖：相同 commit/digest 变异或缺失字段被拒绝；上一 manifest 迁移数少、checksum 不同或版本回退时拒绝自动 DB rollback；plan 只含 `kubectl set image`/`rollout status` 命令；默认 dry-run 不调用 `subprocess.run`；`--apply` 调用顺序可捕获并可断言；Kubernetes context 为空时拒绝执行。

- [ ] **Step 2: 运行确认 RED**

Run: `python -m unittest tools.release.test_rollback_drill -v`  
Expected: FAIL，模块不存在。

- [ ] **Step 3: 实现无副作用优先的 rollback plan**

Planner 比较 manifest：`source.commit`、`image.ref`、`image.digest`、`migrations.treeSha256`、`migrations.count`。若 migration 有差异，plan 设置 `databaseAction = "MANUAL_REVIEW_REQUIRED"` 并禁止 `--apply`；应用回滚仅允许 image digest 回退。命令必须使用 list 参数，禁止 shell 字符串拼接。执行器记录 JSON lines 到 stdout，但不得打印 Secret 或环境变量。

- [ ] **Step 4: 运行确认 GREEN**

Run: `python -m unittest tools.release.test_rollback_drill -v`  
Expected: 全部 PASS；dry-run 用例证明没有 `kubectl` 调用。

- [ ] **Step 5: 写回滚 runbook**

明确：Phase 0 只验证应用镜像回滚；Flyway 没有自动 down migration，数据库回滚必须按每个迁移的 runbook 人工评审。若当前迁移不向后兼容，先停写、备份/PITR、走数据修复方案，禁止把 `flyway undo` 当默认操作。

- [ ] **Step 6: 用临时 index 提交 Task 6**

```powershell
$idx = Join-Path $env:TEMP ('amz-index-' + [guid]::NewGuid())
$env:GIT_INDEX_FILE = $idx
git read-tree HEAD
git add -- tools/release/rollback_drill.py tools/release/test_rollback_drill.py docs/superpowers/runbooks/release-rollback.md docs/superpowers/runbooks/release-candidate-checklist.md
git commit -m "ops(release): add dry-run rollback drill"
Remove-Item Env:GIT_INDEX_FILE
Remove-Item -LiteralPath $idx
```

---

### Task 7: 干净 Clone 复验与 Phase 0 证据

**Files:**
- Create: `docs/superpowers/evidence/2026-09-28-phase0-verification.md`
- Create: `tools/release/verify_clean_clone.ps1`
- Create: `tools/release/test_verify_clean_clone.py`

**Interfaces:**
- Consumes: 已提交 Phase 0 内容、Maven/Node 本地路径、Git remote。
- Produces: 可重复执行的 clean clone 验证脚本和证据文档；每项记录命令、exit code、关键输出、commit、日期和 `VERIFIED/NOT VERIFIED`。

- [ ] **Step 1: 写脚本测试**

测试脚本从临时目录 clone local repo，断言：clone 后 `git status --porcelain` 为空；Python release tests 通过；hygiene scan 通过；Maven reactor 18/18；frontend typecheck/tests/build 通过；脚本不修改原仓库；失败时保留工作目录并打印路径；环境缺少 Node/Maven 时返回 `NOT VERIFIED` 而不是伪造 PASS。

- [ ] **Step 2: 运行确认 RED**

Run: `python -m unittest tools.release.test_verify_clean_clone -v`  
Expected: FAIL，脚本不存在。

- [ ] **Step 3: 实现 clean clone 脚本**

```powershell
param(
  [string]$Repo = (Resolve-Path .).Path,
  [string]$Ref = 'HEAD',
  [string]$WorkRoot = (Join-Path $env:TEMP 'amazonerp-phase0-verify')
)
$ErrorActionPreference = 'Stop'
# 1. git clone --no-hardlinks --local <Repo> <guard-clone>
# 2. verify git status --porcelain is empty
# 3. run python release unit tests + hygiene
# 4. run Maven test -fae
# 5. run node frontend checks
# 6. collect exit codes and write evidence JSON
```

脚本不得运行 `git clean`；工作目录已经由 mktemp 生成，清理仅允许删除该脚本创建的 temp root，并在删除前验证 resolved path 位于 `$env:TEMP` 下。

- [ ] **Step 4: 运行确认 GREEN**

Run:

```powershell
python -m unittest tools.release.test_verify_clean_clone -v
powershell -NoProfile -ExecutionPolicy Bypass -File tools/release/verify_clean_clone.ps1
```

Expected: 本机可行的检查全部 PASS；Docker、GHCR、GitHub Actions、Cosign、Syft、Grype 和 Kubernetes 缺失时明确写入 `NOT VERIFIED`。

- [ ] **Step 5: 写入证据文档**

文档必须分三栏：`检查项`、`本地证据`、`状态`。至少记录：recovery snapshot、repository hygiene、release tool tests、Maven reactor、frontend typecheck/tests/build、manifest deterministic check、clean clone status。不得把“本地单元测试通过”写成“发布工作流通过”。

- [ ] **Step 6: 用临时 index 提交 Task 7**

```powershell
$idx = Join-Path $env:TEMP ('amz-index-' + [guid]::NewGuid())
$env:GIT_INDEX_FILE = $idx
git read-tree HEAD
git add -- docs/superpowers/evidence/2026-09-28-phase0-verification.md tools/release/verify_clean_clone.ps1 tools/release/test_verify_clean_clone.py
git commit -m "test(release): verify phase 0 from a clean clone"
Remove-Item Env:GIT_INDEX_FILE
Remove-Item -LiteralPath $idx
```

---

### Task 8: 拆分现有 API-Ready 工作区基线

**Files:**
- Modify: 既有 dirty tracked/untracked source、test、docs、deployment 文件。
- Create: `docs/superpowers/evidence/2026-09-28-baseline-split-map.json`

**Interfaces:**
- Consumes: Task 0 冻结清单、Task 1 hygiene findings、已有恢复快照。
- Produces: 可按子系统审查和回滚的提交序列；不改变代码行为；每个提交有明确测试命令和检查结果。

- [ ] **Step 1: 生成路径归属映射，不移动文件**

从 `git status --porcelain=v2` 读取全部路径，按前缀映射到 `common`、`gateway`、`frontend`、`spapi`、`ad`、`ai`、`finance`、`order`、`product`、`synthetic-data`、`deploy/k8s`、`docs`，未知路径进入 `review-required`。映射文件必须含每条路径的 `status`、`group`、`sha256`、`classification`，分类只能是 `source`、`test`、`docs`、`config`、`generated`、`temporary`、`secret-suspect`。

- [ ] **Step 2: 阻断危险内容**

对每个 `secret-suspect` 和 `temporary` 路径先人工查看，再用 Task 1 扫描器验证；真实 Secret 立即轮换；临时日志/JSON/补丁如需保留证据放在外部 recovery snapshot，不进入 Git。任何删除前记录原始路径、哈希、大小和恢复位置。

- [ ] **Step 3: 按子系统做独立测试和提交**

每个 group 执行对应模块测试；跨模块契约至少执行 `mvn -B -ntp -pl amz-common,amz-service/amz-service-spapi -am test`。用临时 index 提交当前 group，提交信息统一为 `feat(api-ready): <subsystem> ...` 或 `docs(api-ready): ...`；提交前后记录 `git show --stat` 和测试 exit code。禁止一个提交同时混入多个无依赖业务域。

- [ ] **Step 4: 恢复安全产物与忽略边界**

把恢复快照中的必要工具链源码重新放回，确认 `.gitignore` 只忽略可再生输出；日志、`target/`、`node_modules/`、`dist/`、Synthetic `out/` 不进入 Git。检查现有 152 MiB `_r83_spapi_final_out.log` 只在外部恢复快照/原路径留档，不进入发布树。

- [ ] **Step 5: 验证提交后的工作区语义**

Run:

```powershell
git status --short
python tools/release/repository_hygiene.py --root .
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp'
& 'C:\Users\Administrator\.cache\codex-tools\apache-maven-3.9.11\bin\mvn.cmd' -B -ntp test -fae
```

Expected: 剩余 dirty 项只能是明确记录的外部产物或下一 Phase 工作；tracked 发布树 hygiene PASS；Maven 18/18 SUCCESS。

- [ ] **Step 6: 更新基线证据**

把 split map、每组 commit SHA、测试结果追加到 Phase 0 verification 文档，并明确哪些改动尚未归类。若无法完整分类，保持任务未完成，不宣称“工作区已清理”。

---

## Phase 0 退出条件

- [ ] 恢复快照和冻结清单存在，哈希一致。
- [ ] tracked 发布树通过 repository hygiene；不存在真实 Secret、日志、临时脚本或 >10 MiB 非必要文件。
- [ ] Release manifest 对同一 commit/镜像 digest/源码输入逐字节确定，包含全部 49 个 Flyway checksum。
- [ ] Docker Bake 能表达 1 gateway + 15 service + frontend 的可追溯构建；未实际构建的镜像不标为已发布。
- [ ] CI 的必需检查不可 `continue-on-error`，关键 Python/契约测试、Checkstyle、Maven、前端、clean-tree 都阻断失败。
- [ ] 发布工作流定义 GHCR、SBOM、Grype、Cosign 和 manifest；远端未跑前保持 `NOT VERIFIED`。
- [ ] 回滚演练默认 dry-run，数据库回滚无自动 down 假设。
- [ ] 从干净 clone 可复现本地构建与测试；所有未验证外部环节明确列出。
- [ ] 现有 API-Ready 改动完成 subsystem 拆分，或明确列出尚未完成的 `review-required` 路径；不得用“代码存在”代替“发布成功”。