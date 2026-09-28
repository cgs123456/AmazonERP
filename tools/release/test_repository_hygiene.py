from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from tools.release.repository_hygiene import (
    HygieneAllowlist,
    LARGE_FILE_BYTES,
    scan_repository,
)


class RepositoryHygieneTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def write(self, relative_path: str, content: bytes | str) -> Path:
        path = self.root / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(content, str):
            path.write_text(content, encoding="utf-8", newline="\n")
        else:
            path.write_bytes(content)
        return path

    def scan(self, allowlist: HygieneAllowlist | None = None):
        return scan_repository(
            self.root,
            include_untracked=True,
            allowlist=allowlist or HygieneAllowlist.empty(),
        )

    def test_flags_root_runtime_and_round_artifacts(self) -> None:
        self.write("_run.log", "boot\n")
        self.write("mvn-test.log", "test\n")
        self.write("round33-result.json", "{}\n")

        findings = self.scan()
        codes = {(finding.rule, finding.path) for finding in findings}

        self.assertIn(("root-runtime-artifact", "_run.log"), codes)
        self.assertIn(("root-maven-log", "mvn-test.log"), codes)
        self.assertIn(("root-round-artifact", "round33-result.json"), codes)

    def test_flags_temporary_scripts_and_files(self) -> None:
        self.write(".patch_tmp.py", "pass\n")
        self.write("tools/fix.tmp", "temporary\n")
        self.write("docs/reference.orig", "temporary\n")

        self.assertEqual(
            {
                ("temporary-script", ".patch_tmp.py"),
                ("temporary-file", "tools/fix.tmp"),
                ("temporary-file", "docs/reference.orig"),
            },
            {(finding.rule, finding.path) for finding in self.scan()},
        )

    def test_flags_files_over_the_large_file_limit(self) -> None:
        self.write("assets/large.bin", b"x" * (LARGE_FILE_BYTES + 1))
        self.write("assets/at-limit.bin", b"x" * LARGE_FILE_BYTES)

        findings = self.scan()

        self.assertEqual([("large-file", "assets/large.bin")], [(f.rule, f.path) for f in findings])

    def test_flags_high_confidence_secrets(self) -> None:
        github_token = "GITHUB_TOKEN=" + "gh" + "p_" + "abcdefghijklmnopqrstuvwxyzABCDEFGHIJ"
        aws_access_key = "AWS_ACCESS_KEY_ID=" + "AK" + "IA" + "1234567890ABCDEF"
        private_key_header = "-----BEGIN " + "PRIVATE KEY-----"
        self.write("config/credentials.env", github_token + "\n")
        self.write("config/aws.txt", aws_access_key + "\n")
        self.write("config/private.pem", private_key_header + "\nnot-a-real-key\n")

        self.assertEqual(
            {
                ("github-token", "config/credentials.env"),
                ("aws-access-key", "config/aws.txt"),
                ("private-key", "config/private.pem"),
            },
            {(finding.rule, finding.path) for finding in self.scan()},
        )

    def test_does_not_flag_placeholders_and_common_test_values(self) -> None:
        self.write(
            "config/example.env",
            "\n".join(
                [
                    "DB_PASSWORD=${DB_PASSWORD}",
                    "API_TOKEN=placeholder-token",
                    "CLIENT_SECRET=example-secret",
                    "AWS_ACCESS_KEY_ID=YOUR_AWS_ACCESS_KEY_ID",
                ]
            )
            + "\n",
        )

        self.assertEqual([], self.scan())

    def test_allowlist_requires_exact_path_rule_and_hash(self) -> None:
        path = self.write("_legacy.log", "documented evidence\n")
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        allowlist = HygieneAllowlist.from_entries(
            [
                {
                    "path": "_legacy.log",
                    "rule": "root-runtime-artifact",
                    "sha256": digest,
                }
            ]
        )

        self.assertEqual([], self.scan(allowlist=allowlist))

        path.write_text("changed evidence\n", encoding="utf-8", newline="\n")
        findings = self.scan(allowlist=allowlist)
        self.assertEqual([("root-runtime-artifact", "_legacy.log")], [(f.rule, f.path) for f in findings])

    def test_cli_writes_deterministic_sorted_json_and_returns_one(self) -> None:
        self.write("_run.log", "first\n")
        report_one = self.root / "report-one.json"
        report_two = self.root / "report-two.json"
        command = [
            sys.executable,
            "tools/release/repository_hygiene.py",
            "--root",
            str(self.root),
            "--include-untracked",
            "--json",
        ]

        first = subprocess.run(command + [str(report_one)], cwd=Path(__file__).resolve().parents[2], check=False)
        second = subprocess.run(command + [str(report_two)], cwd=Path(__file__).resolve().parents[2], check=False)

        self.assertEqual(1, first.returncode)
        self.assertEqual(1, second.returncode)
        self.assertEqual(report_one.read_bytes(), report_two.read_bytes())
        report = json.loads(report_one.read_text(encoding="utf-8"))
        self.assertEqual(1, report["summary"]["findings"])
        self.assertEqual([["root-runtime-artifact", "_run.log"]], [[item["rule"], item["path"]] for item in report["findings"]])


if __name__ == "__main__":
    unittest.main()