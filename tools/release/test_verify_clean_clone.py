"""TDD tests for verify_clean_clone.ps1 (RED first)."""
import os
import unittest

SCRIPT = os.path.join(
    os.path.dirname(__file__), "..", "release", "verify_clean_clone.ps1"
)


class TestVerifyCleanClone(unittest.TestCase):
    def test_script_exists(self):
        self.assertTrue(os.path.isfile(SCRIPT), "verify_clean_clone.ps1 not found")

    def test_no_git_clean(self):
        if not os.path.isfile(SCRIPT):
            self.fail("script missing")
        with open(SCRIPT, "r", encoding="utf-8-sig") as f:
            content = f.read()
        self.assertNotIn("git clean", content)

    def test_has_temp_cleanup_guard(self):
        if not os.path.isfile(SCRIPT):
            self.fail("script missing")
        with open(SCRIPT, "r", encoding="utf-8-sig") as f:
            content = f.read()
        self.assertIn("$env:TEMP", content)

    def test_has_evidence_output(self):
        if not os.path.isfile(SCRIPT):
            self.fail("script missing")
        with open(SCRIPT, "r", encoding="utf-8-sig") as f:
            content = f.read()
        self.assertIn("evidence", content.lower())


if __name__ == "__main__":
    unittest.main()
