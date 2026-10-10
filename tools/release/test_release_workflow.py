"""Static contract tests for .github/workflows/release.yml (TDD RED first)."""
import os
import re
import unittest

RELEASE_YML = os.path.join(
    os.path.dirname(__file__), "..", "..", ".github", "workflows", "release.yml"
)

CI_YML = os.path.join(os.path.dirname(RELEASE_YML), "ci.yml")
_TOOL_TEST = re.compile(r"tools\.release\.test_[a-z0-9_]+")


def release_image_names(raw):
    """Parse the workflow's single release-image list in declaration order."""
    match = re.search(r'(?m)^\s*RELEASE_IMAGES:\s*"([^"]+)"\s*$', raw)
    return tuple(match.group(1).split()) if match else ()

def release_tool_test_commands(raw):
    """Every `python -m unittest tools.release.* ...` command in a workflow."""
    return sorted(
        tuple(_TOOL_TEST.findall(line))
        for line in raw.splitlines()
        if "python -m unittest" in line and "tools.release." in line
    )


class TestReleaseWorkflow(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not os.path.isfile(RELEASE_YML):
            self.fail("release.yml not yet created (TDD RED)")
        with open(RELEASE_YML, "r", encoding="utf-8") as fh:
            cls.raw = fh.read()

    def test_triggers(self):
        self.assertIn("workflow_dispatch", self.raw)
        self.assertIn("push:", self.raw)
        self.assertIn("tags:", self.raw)
        self.assertRegex(self.raw, r"v\*\.\*\.\*")

    def test_permissions(self):
        self.assertIn("contents: write", self.raw)
        self.assertIn("packages: write", self.raw)
        self.assertIn("id-token: write", self.raw)

    def test_release_job_requires_production_environment(self):
        release_start = self.raw.index("\n  release:\n")
        release_block = self.raw[release_start:]
        self.assertRegex(
            release_block,
            r"(?m)^    environment:\s*\n\s+name:\s*production\s*$",
        )

    def test_required_actions(self):
        for action in [
            "docker/setup-buildx-action@v4",
            "docker/login-action@v4",
            "docker/bake-action@v7",
            "anchore/syft:latest",
            # gateway 的扫描从 anchore/scan-action 换成了 grype 镜像 + cve_gate：
            # scan-action 只能对 HIGH/CRITICAL 一律失败，没有豁免机制（见 test_scan_fails_build）。
            "anchore/grype:latest",
            "sigstore/cosign-installer@v3",
            "softprops/action-gh-release@v3",
        ]:
            self.assertIn(action, self.raw, f"missing action {action}")

    def test_no_latest_only_tag(self):
        self.assertNotIn("tags: latest", self.raw)
        self.assertNotMatch = self.assertRegex; [self.assertNotRegex(self.raw, r"tags:\s*\n\s*-\s*latest")]

    def test_scan_fails_build(self):
        # fail-build: true 曾经是唯一的门禁，现在由 cve_gate.py 承担：同样的
        # severity cutoff（high 及以上），未豁免的 HIGH/CRITICAL 仍然让步骤失败，
        # 而有 owner、有到期日的豁免才能放行。下面的断言钉住的是"不能绕过"，
        # 而不是某个具体 action 的名字。
        self.assertIn("cve_gate.py check", self.raw)
        self.assertIn("--cutoff high", self.raw)
        self.assertIn("HIGH", self.raw.upper())
        self.assertIn("CRITICAL", self.raw.upper())
        # 放宽 cutoff 是唯一能让整批 15 条 netty 告警静默消失的旋钮，直接禁掉。
        self.assertIsNone(
            re.search(r"--cutoff\s+(?!high\b)", self.raw),
            "the CVE gate cutoff must stay at high",
        )
        # 门禁步骤必须真的能失败：既不能 continue-on-error，也不能 || true 兜住。
        self.assertNotIn("continue-on-error", self.raw)
        self.assertNotIn("|| true", self.raw)

    def test_gate_runs_before_signing(self):
        # 只在签名之前拦住才有意义：一旦镜像被 cosign 签名，它就是"已发布"的。
        self.assertLess(self.raw.index("Scan + gate every release image"),
                        self.raw.index("Cosign sign every image digest"))

    def test_signs_every_release_image(self):
        self.assertIn("Cosign sign every image digest", self.raw)
        self.assertIn("${REF%:*}@${DIGEST}", self.raw)
        self.assertGreaterEqual(
            self.raw.count("for name in ${RELEASE_IMAGES}; do"),
            3,
            "SBOM, CVE scan, and cosign must each use the single release-image list",
        )
    def test_uses_bake_digest(self):
        self.assertIn("bake-metadata", self.raw.lower())

    def test_no_plaintext_secret_or_true(self):
        self.assertNotIn("|| true", self.raw)
        self.assertNotIn("${{ secrets.AMAZON_", self.raw)

    def test_release_assets(self):
        for asset in ["manifest", "sbom", "checksums"]:
            self.assertIn(asset, self.raw.lower())

    def test_release_assets_use_generated_sbom_directory(self):
        self.assertNotIn('sbom-gateway.spdx.json', self.raw)
        self.assertIn('sboms/${name}.spdx.json', self.raw)
        self.assertIn('cp sboms/*.spdx.json release-assets/', self.raw)
        self.assertIn('cd release-assets', self.raw)
        self.assertIn('sha256sum release-manifest.json *.spdx.json > checksums.sha256', self.raw)
        self.assertNotIn('sha256sum release-manifest.json sboms/*.spdx.json', self.raw)
        self.assertGreaterEqual(self.raw.count('release-assets/*'), 2)


    # --- cross-artifact contracts (added after the first real release dry run) ---
    # Presence-only checks above let a workflow ship that could never run: the
    # manifest step called a CLI shape that does not exist and every image
    # reference disagreed with docker-bake.hcl. These tests bind the workflow to
    # the real CLI signature and to the real bake image names/tags.

    def test_manifest_invocation_matches_cli(self):
        self.assertIn("release_manifest.py build", self.raw)
        for arg in ["--root", "--commit", "--image-ref", "--image-digest", "--output"]:
            self.assertIn(arg, self.raw, f"manifest step missing {arg}")
        self.assertNotRegex(self.raw, r"release_manifest\.py\s+--version")

    def test_image_name_matches_bake_targets(self):
        # docker-bake.hcl publishes ghcr.io/<owner>/amazonerp-gateway:<TAG>
        self.assertIn("amazonerp-gateway", self.raw)
        self.assertNotIn("amz-gateway", self.raw)

    def test_bake_tag_matches_release_version(self):
        # TAG defaults to "dev" in docker-bake.hcl; the workflow must override it.
        self.assertRegex(
            self.raw,
            r"(?m)^\s*TAG:\s*\$\{\{\s*needs\.quality-gate\.outputs\.version",
        )

    def test_release_image_list_matches_bake_targets(self):
        workflow_images = release_image_names(self.raw)
        self.assertEqual(17, len(workflow_images), "release workflow must list 17 images")

        bake_path = os.path.join(os.path.dirname(RELEASE_YML), "..", "..", "docker-bake.hcl")
        with open(bake_path, "r", encoding="utf-8") as fh:
            bake = fh.read()

        bake_images = []
        current = None
        for line in bake.splitlines():
            target_match = re.match(r'\s*target\s+"([^"]+)"\s*\{', line)
            if target_match:
                current = target_match.group(1)
                continue
            if current and re.match(r'\s*tags\s*=', line):
                bake_images.append(current)
                current = None

        self.assertEqual(workflow_images, tuple(bake_images), "release image order must match docker-bake.hcl")
        self.assertEqual(set(workflow_images), set(bake_images), "release image set must match docker-bake.hcl")

    def test_quality_gate_runs_frontend_typecheck_tests_and_build(self):
        for command in ("npx vue-tsc --noEmit", "npm run test:run", "npm run build"):
            self.assertIn(command, self.raw)

    def test_workflow_dispatch_has_no_unused_dry_run_input(self):
        self.assertIn("workflow_dispatch:", self.raw)
        self.assertNotIn("dry_run", self.raw)
    def test_release_tags_include_immutable_commit_sha(self):
        bake_path = os.path.join(os.path.dirname(RELEASE_YML), "..", "..", "docker-bake.hcl")
        with open(bake_path, "r", encoding="utf-8") as fh:
            bake = fh.read()
        tags = re.findall(r'tags\s*=\s*\["([^"]+)"\]', bake)
        self.assertEqual(17, len(tags), "expected one tag per release image")
        for tag in tags:
            self.assertTrue(
                tag.endswith(":${TAG}-${GIT_SHA}"),
                f"release tag must include the immutable commit SHA: {tag}",
            )
    def test_workflow_uses_sha_qualified_image_tag(self):
        self.assertIn("image_tag:", self.raw)
        self.assertIn("image_tag=", self.raw)
        self.assertIn("IMAGE_TAG:", self.raw)
        self.assertNotIn("amazonerp-gateway:${{ needs.quality-gate.outputs.version }}", self.raw)
        self.assertNotIn("amazonerp-${name}:${VERSION}", self.raw)
    def test_workflow_dispatch_builds_without_pushing(self):
        # The advertised dry_run dispatch must not push to the registry.
        self.assertRegex(self.raw, r"push:\s*\$\{\{\s*github\.event_name == 'push'")

    def test_scans_every_release_image(self):
        # Scanning only the gateway produced a false sense of safety: it is the
        # one WebFlux image without Tomcat, while the 15 servlet services each
        # carried 28-35 HIGH/CRITICAL findings before remediation.
        self.assertIn("Scan + gate every release image", self.raw)
        scanned = set(release_image_names(self.raw))
        self.assertEqual(17, len(scanned), "release.yml must declare all 17 release images")
        expected = {
            "gateway", "ad", "ai", "customer", "finance", "logistics", "message",
            "multiplatform", "ops", "order", "procurement", "product",
            "report", "search", "spapi", "user", "frontend",
        }
        self.assertEqual(expected, scanned, "every release image must be scanned")
        # 扫了但豁免串了镜像同样等于没扫：--image 必须由循环变量拼出，不能有手写常量。
        self.assertIn('--image "amazonerp-${name}"', self.raw)
        self.assertIsNone(
            re.search(r'--image\s+"?amazonerp-(?!\$\{name\})', self.raw),
            "cve_gate --image must be derived from the loop variable",
        )

    def test_generates_sbom_for_every_release_image(self):
        self.assertIn("Generate SBOM for every release image", self.raw)
        self.assertIn("anchore/syft:latest", self.raw)
        self.assertIn("sboms/${name}.spdx.json", self.raw)
        self.assertGreaterEqual(
            self.raw.count("for name in ${RELEASE_IMAGES}; do"),
            3,
            "SBOM, CVE scan, and cosign must each cover the release-image list",
        )
    def test_bake_registry_includes_owner(self):
        # The workflow-level env REGISTRY=ghcr.io overrides the identically named
        # docker-bake.hcl variable, so the bake step must re-add the owner or
        # every push is rejected with 400 (ghcr.io/amazonerp-* has no namespace).
        self.assertRegex(
            self.raw,
            r"(?m)^\s*REGISTRY:\s*\$\{\{\s*env\.REGISTRY\s*\}\}/\$\{\{\s*github\.repository_owner\s*\}\}",
        )


    def test_ci_and_release_run_the_same_release_tool_tests(self):
        # This list is copied into both workflows. Extending one of them only
        # silently leaves the other pipeline unable to fail on the new test,
        # and no existing check notices -- which is exactly how a gate ends up
        # being enforced in CI but not at release time.
        release_cmd = release_tool_test_commands(self.raw)
        self.assertTrue(release_cmd, "release.yml no longer runs the release tool tests")
        with open(CI_YML, "r", encoding="utf-8") as fh:
            ci_cmd = release_tool_test_commands(fh.read())
        self.assertEqual(release_cmd, ci_cmd, "ci.yml and release.yml must run identical release tool tests")

    def test_boot_smoke_marker_match_is_pipefail_and_regex_safe(self):
        # v0.1.13-v0.1.19: five consecutive release runs died in the prod boot
        # smoke with "\u672a\u5728 180 \u79d2\u5185\u51fa\u73b0\u542f\u52a8\u6807\u8bb0" while the marker was
        # in the log the whole time. Two independent shell traps:
        #
        # 1) pipefail + SIGPIPE: `docker logs ... | grep -q PATTERN` is judged
        #    FAILED even on a hit. grep -q exits at the first match, docker logs
        #    keeps writing into a closed pipe, takes SIGPIPE (exit 141), and
        #    `set -o pipefail` promotes that non-zero status to the pipeline's,
        #    so the `if` never takes the success branch.
        # 2) BRE bracket: the prod marker is `...activeProfiles=[prod]`; a plain
        #    `grep` reads `[prod]` as a character class, so the literal line can
        #    never match no matter how long it waits.
        #
        # The fix is structural: land the log in a file, then match the file
        # with a fixed-string grep. Neither the pipe nor the regex may come back.
        self.assertNotIn("| grep -q", self.raw)
        self.assertIn('grep -qF -- "$marker" "$logf"', self.raw)
        self.assertIn('docker logs "$container" > "$logf" 2>&1', self.raw)



    def _boot_smoke_block(self):
        """The single `Prod-profile boot smoke` step body, isolated from the rest of the workflow."""
        marker = "- name: Prod-profile boot smoke (with DB and Redis)"
        start = self.raw.index(marker)
        rest = self.raw[start + len(marker):]
        # the step ends at the next top-level `- name:` step
        nxt = rest.find("\n      - name: ")
        return rest if nxt < 0 else rest[:nxt]

    def test_boot_smoke_silences_skywalking_agent_without_a_collector(self):
        # 动因（2026-10-10 本机实测，order v0.1.19 真实镜像）：
        # Dockerfile 把 SW_AGENT_COLLECTOR_BACKEND_SERVICES 默认成 skywalking-oap:11800，
        # 而冒烟网络里根本没有这个容器/别名 —— 每次启动都刷
        # "Failed to resolve host skywalking-oap"（185s 内 7 条），把启动日志淹成噪声。
        # 这是纯噪声、不阻断启动，所以五连红之后一直被当成"别去追的东西"留了下来；
        # 但噪声本身是 bug 的藏身处：真正让 boot_wait 判失败的 ERROR 混在里面更难看见。
        #
        # 实测三种消噪法（order v0.1.19，185s）：
        #   默认 skywalking-oap   -> resolve-fail 7 条
        #   SW_AGENT_OPTS 置空     -> agent 根本不挂（SnifferConfigInitializer 0 行），
        #                             冒烟就不再覆盖"agent 挂在 ENTRYPOINT 上"这条链路
        #   collector=127.0.0.1:*  -> resolve-fail 0 条、agent 仍挂载
        # 因此正确做法是显式把 collector 指到本地黑洞地址，而不是把 agent 摘掉——
        # 摘掉 agent 会让冒烟对 SkyWalkingIdentityContractTest 守的那条接线失去观察力。
        block = self._boot_smoke_block()
        self.assertIn("SW_AGENT_COLLECTOR_BACKEND_SERVICES=127.0.0.1:11800", block,
                      "冒烟必须把 SkyWalking collector 显式指到本地黑洞地址，"
                      "否则每次启动都刷 'Failed to resolve host' 噪声（185s 实测 7 条）")
        # 覆盖必须写在所有腿共用的 SMOKE_ENV 里，不能只加在某一条腿上——
        # 否则后加的腿会退回镜像默认地址，噪声又回来。
        self.assertIn("SMOKE_ENV=(", block)
        env_line = [ln for ln in block.splitlines() if "SMOKE_ENV=(" in ln][0]
        self.assertIn("SW_AGENT_COLLECTOR_BACKEND_SERVICES=127.0.0.1:11800", env_line,
                      "collector 覆盖必须在 SMOKE_ENV 里，四条腿才会一致生效")
        # 不能靠"把 agent 摘掉"来消噪：那会让冒烟不再覆盖 agent 挂载这条链路。
        self.assertNotIn("SW_AGENT_OPTS=", block,
                         "不得用清空 SW_AGENT_OPTS 的方式消噪：那等于让冒烟不再覆盖 agent 挂载")

    def test_boot_smoke_creates_amz_user_and_runs_a_user_boot_leg(self):
        # 动因（2026-10-10 本机实测）：
        # FieldPermissionService 的事实源是 amz_user.amz_field_permission，但它被 amz-common
        # 编进每一个服务，于是 order/product 等腿启动时都会去查这张表。冒烟只建了
        # amz_spapi/amz_order/amz_product 三个库 —— amz_user 库不存在，SQL 直接
        # "bad SQL grammar" 报错，服务降级成「全部可见」：
        #   FieldPermissionService: 加载字段权限规则失败…（从未成功加载过则为「全部可见」）
        # 实测两种"建了库但没建表"的中间态都不够：
        #   amz_user 库不存在          -> bad SQL grammar（表都不存在）
        #   amz_user 库存在但为空      -> bad SQL grammar（表不存在）
        # 只有跑过一次 user 服务（Flyway 把 V1/V2 建表+种子规则灌进去）之后，
        # order 腿才会打出 "加载字段权限规则 8 条" 而不是降级 warn。
        # 所以缺口要同时补两半：建库 + 一条 user 服务启动腿。
        block = self._boot_smoke_block()
        self.assertIn("amz_user", block,
                      "冒烟必须建 amz_user 库：FieldPermissionService 的事实源在它里面")
        # 必须真跑一条 user 服务腿：Flyway 才会把 amz_field_permission 建出来。
        # 实测：库存在但表不存在时 order 腿仍然报 bad SQL grammar 并降级「全部可见」。
        self.assertIn("Started AmzServiceUserApplication", block,
                      "冒烟必须跑一条 user 服务腿，让 Flyway 把 amz_field_permission 建出来")
        # 顺序是契约：user 腿必须先于 order/product 腿，否则后启动的腿查表仍失败。
        self.assertLess(block.index("amz-smoke-user-prod"), block.index("amz-smoke-order-prod"),
                        "user 腿必须先于 order 腿：order 启动时就要查 amz_field_permission")
        # 只断言"进程起来了"不够：FieldPermissionService 加载失败只是 warn、服务照常 Started，
        # 降级「全部可见」会静默通过。成功行是「加载字段权限规则 N 条」，
        # 失败行是「加载字段权限规则失败…」，固定串区分不了，所以这里钉正则形态。
        self.assertIn("加载字段权限规则", block,
                      "user 腿必须断言字段权限真的加载成功，而不是只要进程起来就算过")
        self.assertIn('grep -qE "加载字段权限规则 [0-9]+ 条', block,
                      "user 腿的加载成功断言必须是 grep -qE 的『加载成功』形态")


if __name__ == "__main__":
    unittest.main()
