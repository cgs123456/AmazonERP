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
            "docker/setup-buildx-action",
            "docker/login-action",
            "docker/bake-action",
            "anchore/syft:latest",
            # gateway 的扫描从 anchore/scan-action 换成了 grype 镜像 + cve_gate：
            # scan-action 只能对 HIGH/CRITICAL 一律失败，没有豁免机制（见 test_scan_fails_build）。
            "anchore/grype:latest",
            "sigstore/cosign-installer",
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


if __name__ == "__main__":
    unittest.main()


