"""Static contract tests for .github/workflows/release.yml (TDD RED first)."""
import os
import re
import unittest

RELEASE_YML = os.path.join(
    os.path.dirname(__file__), "..", "..", ".github", "workflows", "release.yml"
)

CI_YML = os.path.join(os.path.dirname(RELEASE_YML), "ci.yml")
_TOOL_TEST = re.compile(r"tools\.release\.test_[a-z0-9_]+")


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

    def test_required_actions(self):
        for action in [
            "docker/setup-buildx-action",
            "docker/login-action",
            "docker/bake-action",
            "anchore/sbom-action",
            "anchore/scan-action",
            "sigstore/cosign-installer",
        ]:
            self.assertIn(action, self.raw, f"missing action {action}")

    def test_no_latest_only_tag(self):
        self.assertNotIn("tags: latest", self.raw)
        self.assertNotMatch = self.assertRegex; [self.assertNotRegex(self.raw, r"tags:\s*\n\s*-\s*latest")]

    def test_scan_fails_build(self):
        self.assertIn("fail-build: true", self.raw)
        self.assertIn("HIGH", self.raw.upper())
        self.assertIn("CRITICAL", self.raw.upper())

    def test_uses_bake_digest(self):
        self.assertIn("bake-metadata", self.raw.lower())

    def test_no_plaintext_secret_or_true(self):
        self.assertNotIn("|| true", self.raw)
        self.assertNotIn("${{ secrets.AMAZON_", self.raw)

    def test_release_assets(self):
        for asset in ["manifest", "sbom", "checksums"]:
            self.assertIn(asset, self.raw.lower())

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

    def test_workflow_dispatch_builds_without_pushing(self):
        # The advertised dry_run dispatch must not push to the registry.
        self.assertRegex(self.raw, r"push:\s*\$\{\{\s*github\.event_name == 'push'")

    def test_scans_every_release_image(self):
        # Scanning only the gateway produced a false sense of safety: it is the
        # one WebFlux image without Tomcat, while the 15 servlet services each
        # carried 28-35 HIGH/CRITICAL findings before remediation.
        self.assertIn("Scan remaining images", self.raw)
        match = re.search(r"for name in ([^;]+);", self.raw)
        self.assertIsNotNone(match, "release.yml must loop over the remaining images")
        scanned = set(match.group(1).split())
        expected = {
            "ad", "ai", "customer", "finance", "logistics", "message",
            "multiplatform", "ops", "order", "procurement", "product",
            "report", "search", "spapi", "user", "frontend",
        }
        self.assertEqual(expected, scanned, "every non-gateway image must be scanned")

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


