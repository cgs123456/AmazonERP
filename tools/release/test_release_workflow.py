"""Static contract tests for .github/workflows/release.yml (TDD RED first)."""
import os
import unittest

RELEASE_YML = os.path.join(
    os.path.dirname(__file__), "..", "..", ".github", "workflows", "release.yml"
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


if __name__ == "__main__":
    unittest.main()


