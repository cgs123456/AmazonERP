#!/usr/bin/env python3
"""Contract tests for deterministic release manifest generation."""

from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from tools.release.release_manifest import build_manifest, canonical_json

ROOT = Path(__file__).resolve().parents[2]
FIXTURE_PATH = Path(__file__).with_name("testdata") / "release_manifest" / "fixture.json"
COMMIT = "a" * 40
VERSION = "1.0.0"
IMAGE_REF = "ghcr.io/acme/amazonerp-spapi:1.0.0"
IMAGE_DIGEST = "sha256:" + "b" * 64


def write_file(path: Path, content: str | bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(content, str):
        path.write_text(content, encoding="utf-8", newline="\n")
    else:
        path.write_bytes(content)


def load_fixture() -> dict[str, dict[str, str]]:
    return json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))


def make_fixture_root(directory: Path) -> Path:
    fixture = load_fixture()
    for relative_path, content in fixture["frontendFiles"].items():
        write_file(directory / relative_path, content)
    for relative_path, content in fixture["migrationFiles"].items():
        write_file(directory / relative_path, content)
    return directory


def tree_digest(entries: list[tuple[str, str]]) -> str:
    material = b"".join(
        relative_path.encode("utf-8") + b"\0" + content_sha256.encode("ascii")
        for relative_path, content_sha256 in sorted(entries, key=lambda item: item[0].encode("utf-8"))
    )
    return hashlib.sha256(material).hexdigest()


class ReleaseManifestTest(unittest.TestCase):
    def build_fixture_manifest(self, root: Path) -> dict:
        return build_manifest(root, COMMIT, VERSION, IMAGE_REF, IMAGE_DIGEST)

    def test_manifest_is_byte_identical_for_same_input(self) -> None:
        first = canonical_json(build_manifest(ROOT, COMMIT, VERSION, IMAGE_REF, IMAGE_DIGEST))
        second = canonical_json(build_manifest(ROOT, COMMIT, VERSION, IMAGE_REF, IMAGE_DIGEST))

        self.assertEqual(first, second)

    def test_manifest_has_fixed_schema_and_no_absolute_or_volatile_fields(self) -> None:
        manifest = build_manifest(ROOT, COMMIT, VERSION, IMAGE_REF, IMAGE_DIGEST)

        self.assertEqual(
            {"schemaVersion", "source", "image", "frontend", "migrations", "manifestSha256"},
            set(manifest),
        )
        self.assertEqual({"commit", "version"}, set(manifest["source"]))
        self.assertEqual({"ref", "digest"}, set(manifest["image"]))
        self.assertEqual({"treeSha256", "fileCount"}, set(manifest["frontend"]))
        self.assertEqual({"treeSha256", "count", "items"}, set(manifest["migrations"]))
        self.assertEqual(1, manifest["schemaVersion"])
        self.assertEqual({"module", "path", "sha256"}, set(manifest["migrations"]["items"][0]))

        serialized = canonical_json(manifest).decode("utf-8")
        self.assertNotIn(str(ROOT.resolve()), serialized)
        self.assertNotIn(ROOT.resolve().as_posix(), serialized)
        for forbidden_key in ("createdAt", "generatedAt", "timestamp", "randomSeed", "nonce"):
            self.assertNotIn(forbidden_key, serialized)

    def test_manifest_sha256_matches_canonical_unsigned_document(self) -> None:
        manifest = build_manifest(ROOT, COMMIT, VERSION, IMAGE_REF, IMAGE_DIGEST)
        unsigned = dict(manifest)
        unsigned["manifestSha256"] = ""

        expected = hashlib.sha256(canonical_json(unsigned)).hexdigest()

        self.assertEqual(expected, manifest["manifestSha256"])
        self.assertRegex(manifest["manifestSha256"], r"^[0-9a-f]{64}$")

    def test_commit_must_be_exactly_40_lowercase_hex(self) -> None:
        invalid = ("", "a" * 39, "a" * 41, "A" * 40, "g" * 40)
        for commit in invalid:
            with self.subTest(commit=commit), self.assertRaisesRegex(ValueError, "commit"):
                build_manifest(ROOT, commit, VERSION, IMAGE_REF, IMAGE_DIGEST)

    def test_image_digest_must_be_prefixed_sha256_hex(self) -> None:
        invalid = ("", "b" * 64, "sha256:" + "B" * 64, "sha256:" + "b" * 63, "md5:" + "b" * 64)
        for digest in invalid:
            with self.subTest(digest=digest), self.assertRaisesRegex(ValueError, "image_digest"):
                build_manifest(ROOT, COMMIT, VERSION, IMAGE_REF, digest)

    def test_version_must_be_semver(self) -> None:
        for version in ("1.0", "v1.0.0", "01.0.0", "1.0.0.0", ""):
            with self.subTest(version=version), self.assertRaisesRegex(ValueError, "version"):
                build_manifest(ROOT, COMMIT, version, IMAGE_REF, IMAGE_DIGEST)

    def test_fixture_frontend_tree_digest_uses_only_release_inputs(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = make_fixture_root(Path(temporary))
            manifest = self.build_fixture_manifest(root)
            included = {
                "amz-frontend/index.html": read_text(root / "amz-frontend/index.html"),
                "amz-frontend/package.json": read_text(root / "amz-frontend/package.json"),
                "amz-frontend/src/main.ts": read_text(root / "amz-frontend/src/main.ts"),
                "amz-frontend/src/types.d.ts": read_text(root / "amz-frontend/src/types.d.ts"),
            }
            expected_entries = [
                (path, hashlib.sha256(content.encode("utf-8")).hexdigest())
                for path, content in included.items()
            ]
            expected = tree_digest(expected_entries)

            self.assertEqual(expected, manifest["frontend"]["treeSha256"])
            self.assertEqual(4, manifest["frontend"]["fileCount"])

            write_file(root / "amz-frontend/runtime-only.tmp", "ignored\n")
            write_file(root / "amz-frontend/node_modules/extra/index.js", "ignored\n")
            write_file(root / "amz-frontend/dist/extra.js", "ignored\n")
            os.utime(root / "amz-frontend/package.json", None)
            unchanged = self.build_fixture_manifest(root)

            self.assertEqual(manifest["frontend"], unchanged["frontend"])

            write_file(root / "amz-frontend/src/main.ts", "console.log(\"changed\");\n")
            changed = self.build_fixture_manifest(root)

            self.assertNotEqual(manifest["frontend"]["treeSha256"], changed["frontend"]["treeSha256"])
            self.assertEqual(4, changed["frontend"]["fileCount"])

    def test_fixture_migration_inventory_is_sorted_and_complete(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = make_fixture_root(Path(temporary))
            manifest = self.build_fixture_manifest(root)
            items = manifest["migrations"]["items"]

            self.assertEqual(2, manifest["migrations"]["count"])
            self.assertEqual(
                [
                    "amz-service/amz-service-alpha/src/main/resources/db/migration/V1__init.sql",
                    "amz-service/amz-service-beta/src/main/resources/db/migration/V2__more.sql",
                ],
                [item["path"] for item in items],
            )
            self.assertEqual(["amz-service-alpha", "amz-service-beta"], [item["module"] for item in items])
            for item in items:
                content = (root / item["path"]).read_bytes()
                self.assertEqual(hashlib.sha256(content).hexdigest(), item["sha256"])
                self.assertRegex(item["sha256"], r"^[0-9a-f]{64}$")

            expected = tree_digest([(item["path"], item["sha256"]) for item in items])
            self.assertEqual(expected, manifest["migrations"]["treeSha256"])

    def test_current_flyway_inventory_contains_all_50_files(self) -> None:
        manifest = build_manifest(ROOT, COMMIT, VERSION, IMAGE_REF, IMAGE_DIGEST)
        expected_paths = sorted(
            path.relative_to(ROOT).as_posix()
            for path in ROOT.glob("amz-service/*/src/main/resources/db/migration/V*__*.sql")
            if not any(part == "target" for part in path.parts)
        )

        self.assertEqual(50, len(expected_paths))
        self.assertEqual(50, manifest["migrations"]["count"])
        self.assertEqual(expected_paths, [item["path"] for item in manifest["migrations"]["items"]])

    def test_cli_builds_verifies_and_rejects_changed_input(self) -> None:
        script = ROOT / "tools" / "release" / "release_manifest.py"
        with tempfile.TemporaryDirectory() as temporary:
            root = make_fixture_root(Path(temporary))
            output = root / "release-manifest.json"
            build_command = [
                sys.executable,
                str(script),
                "build",
                "--root",
                str(root),
                "--commit",
                COMMIT,
                "--version",
                VERSION,
                "--image-ref",
                IMAGE_REF,
                "--image-digest",
                IMAGE_DIGEST,
                "--output",
                str(output),
            ]
            build = subprocess.run(build_command, cwd=ROOT, check=False, capture_output=True, text=True)
            self.assertEqual(0, build.returncode, build.stderr)
            document = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(canonical_json(document), output.read_bytes())

            verify = subprocess.run(
                [sys.executable, str(script), "verify", "--manifest", str(output), "--root", str(root)],
                cwd=ROOT,
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(0, verify.returncode, verify.stderr)

            write_file(root / "amz-frontend/src/main.ts", "console.log(\"tampered\");\n")
            rejected = subprocess.run(
                [sys.executable, str(script), "verify", "--manifest", str(output), "--root", str(root)],
                cwd=ROOT,
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(1, rejected.returncode)
            self.assertIn("verification failed", rejected.stderr.lower())


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


if __name__ == "__main__":
    unittest.main()


