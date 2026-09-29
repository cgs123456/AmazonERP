"""TDD tests for verify_clean_clone.ps1 (RED first)."""
import os
import unittest

SCRIPT = os.path.join(
    os.path.dirname(__file__), "..", "release", "verify_clean_clone.ps1"
)
RELEASE_TEST_MODULES = (
    "test_repository_hygiene",
    "test_release_manifest",
    "test_services_manifest",
    "test_release_workflow",
    "test_rollback_drill",
    "test_cve_gate",
    "test_verify_clean_clone",
)


class TestVerifyCleanClone(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not os.path.isfile(SCRIPT):
            raise AssertionError("verify_clean_clone.ps1 not found")
        with open(SCRIPT, "r", encoding="utf-8-sig") as f:
            cls.script = f.read()

    def test_script_exists(self):
        self.assertTrue(os.path.isfile(SCRIPT), "verify_clean_clone.ps1 not found")

    def test_no_git_clean(self):
        self.assertNotIn("git clean", self.script)

    def test_has_temp_cleanup_guard(self):
        self.assertIn("$env:TEMP", self.script)

    def test_has_evidence_output(self):
        self.assertIn("evidence", self.script.lower())

    def test_python_release_tests_cover_all_modules(self):
        for module in RELEASE_TEST_MODULES:
            with self.subTest(module=module):
                self.assertIn(f"tools.release.{module}", self.script)

    def test_hygiene_scans_tracked_and_untracked_files(self):
        self.assertIn("repository_hygiene.py --root .", self.script)
        self.assertIn("--include-untracked", self.script)

    def test_runs_git_diff_check(self):
        self.assertIn("git diff --check", self.script)

    def test_frontend_runs_typecheck_tests_and_build(self):
        self.assertIn("npx vue-tsc --noEmit", self.script)
        self.assertIn("npm run test:run", self.script)
        self.assertIn("npm run build", self.script)

    def test_manifest_build_and_verify_round_trip(self):
        self.assertIn("release_manifest.py build", self.script)
        self.assertIn("release_manifest.py verify", self.script)

    def test_docker_bake_print_is_attempted(self):
        self.assertIn("docker buildx bake --print", self.script)

    def test_docker_bake_uses_explicit_hcl_file(self):
        self.assertIn("docker buildx bake --print -f docker-bake.hcl", self.script)

    def test_missing_dependencies_are_not_verified(self):
        self.assertIn("NOT FOUND", self.script)
        self.assertIn("NOT VERIFIED", self.script)

    def test_source_repo_unchanged_check(self):
        self.assertIn("source-repo-unchanged", self.script)

    def test_failed_clone_is_preserved(self):
        self.assertIn("preserved on failure", self.script)

    def test_clone_pins_lf_checkout(self):
        self.assertIn("--config core.autocrlf=false", self.script)
        self.assertIn("--config core.eol=lf", self.script)

if __name__ == "__main__":
    unittest.main()