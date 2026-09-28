"""TDD tests for rollback_drill.py (RED first)."""
import json
import os
import unittest
from unittest import mock
from unittest.mock import patch

MOD = "tools.release.rollback_drill"


class TestRollbackPlan(unittest.TestCase):
    def _manifest(self, digest="sha256:aaa", commit="abc123", migration_count=5, tree_sha="t1"):
        return {
            "source": {"commit": commit},
            "image": {"ref": "ghcr.io/x/amz-gateway", "digest": digest},
            "migrations": {"count": migration_count, "treeSha256": tree_sha},
        }

    def _plan(self, current, previous, namespace="prod"):
        from tools.release import rollback_drill
        return rollback_drill.build_plan(current, previous, namespace)

    def test_same_digest_rejected(self):
        cur = self._manifest()
        prev = self._manifest()
        with self.assertRaises(ValueError):
            self._plan(cur, prev)

    def test_missing_field_rejected(self):
        cur = self._manifest()
        prev = self._manifest()
        del prev["image"]["digest"]
        with self.assertRaises(ValueError):
            self._plan(cur, prev)

    def test_migration_diff_blocks_apply(self):
        cur = self._manifest(digest="sha256:bbb", migration_count=6)
        prev = self._manifest(digest="sha256:aaa", migration_count=5)
        plan = self._plan(cur, prev)
        self.assertEqual(plan["databaseAction"], "MANUAL_REVIEW_REQUIRED")
        self.assertTrue(plan.get("applyBlocked"))

    def test_image_rollback_allowed(self):
        cur = self._manifest(digest="sha256:bbb")
        prev = self._manifest(digest="sha256:aaa")
        plan = self._plan(cur, prev)
        self.assertEqual(plan["databaseAction"], "NONE")
        self.assertFalse(plan.get("applyBlocked", False))
        cmds = plan.get("commands", [])
        self.assertTrue(all(c[0] == "kubectl" for c in cmds))

    def test_dry_run_no_subprocess(self):
        cur = self._manifest(digest="sha256:bbb")
        prev = self._manifest(digest="sha256:aaa")
        from tools.release import rollback_drill
        with patch("subprocess.run") as sr:
            rollback_drill.execute_plan(self._plan(cur, prev), apply=False)
            sr.assert_not_called()

    def test_apply_calls_kubectl(self):
        cur = self._manifest(digest="sha256:bbb")
        prev = self._manifest(digest="sha256:aaa")
        from tools.release import rollback_drill
        plan = self._plan(cur, prev)
        with patch("subprocess.run") as sr:
            sr.return_value = mock.MagicMock(returncode=0)
            rollback_drill.execute_plan(plan, apply=True, context="ctx1")
            self.assertTrue(sr.called)

    def test_empty_context_rejected(self):
        cur = self._manifest(digest="sha256:bbb")
        prev = self._manifest(digest="sha256:aaa")
        from tools.release import rollback_drill
        with self.assertRaises(ValueError):
            rollback_drill.execute_plan(self._plan(cur, prev), apply=True, context="")


if __name__ == "__main__":
    unittest.main()
