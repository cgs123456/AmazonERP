#!/usr/bin/env python3
"""Build and verify deterministic, repository-bound release manifests."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Iterable, Sequence

COMMIT_PATTERN = re.compile(r"^[0-9a-f]{40}$")
IMAGE_DIGEST_PATTERN = re.compile(r"^sha256:[0-9a-f]{64}$")
SEMVER_PATTERN = re.compile(
    r"^(0|[1-9][0-9]*)\."
    r"(0|[1-9][0-9]*)\."
    r"(0|[1-9][0-9]*)"
    r"(?:-(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)"
    r"(?:\.(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*))*)?"
    r"(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$"
)
FRONTEND_ROOT_FILES = ("package.json", "package-lock.json", "index.html")
FRONTEND_ROOT_PATTERNS = ("tsconfig*.json", "vite.config.*")
FRONTEND_DIRECTORY = "amz-frontend"
MIGRATION_GLOB = "amz-service/*/src/main/resources/db/migration/V*.sql"
HASH_CHUNK_BYTES = 1024 * 1024


class ManifestVerificationError(ValueError):
    """Raised when a stored manifest does not match repository inputs."""


def canonical_json(data: dict) -> bytes:
    """Serialize a manifest with a stable key order and no insignificant whitespace."""

    return (
        json.dumps(data, sort_keys=True, separators=(",", ":"), ensure_ascii=False) + "\n"
    ).encode("utf-8")


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(HASH_CHUNK_BYTES):
            digest.update(chunk)
    return digest.hexdigest()


def _tree_sha256(entries: Iterable[tuple[str, str]]) -> str:
    ordered = sorted(entries, key=lambda item: item[0].encode("utf-8"))
    material = b"".join(
        relative_path.encode("utf-8") + b"\0" + content_sha256.encode("ascii")
        for relative_path, content_sha256 in ordered
    )
    return hashlib.sha256(material).hexdigest()


def _frontend_files(root: Path) -> list[Path]:
    frontend_root = root / FRONTEND_DIRECTORY
    if not frontend_root.is_dir():
        raise ValueError(f"frontend directory does not exist: {FRONTEND_DIRECTORY}")

    candidates: set[Path] = set()
    for filename in FRONTEND_ROOT_FILES:
        path = frontend_root / filename
        if path.is_file() and not path.is_symlink():
            candidates.add(path)
    for pattern in FRONTEND_ROOT_PATTERNS:
        for path in frontend_root.glob(pattern):
            if path.is_file() and not path.is_symlink():
                candidates.add(path)

    source_root = frontend_root / "src"
    if not source_root.is_dir():
        raise ValueError(f"frontend source directory does not exist: {FRONTEND_DIRECTORY}/src")
    for path in source_root.rglob("*"):
        if path.is_file() and not path.is_symlink():
            candidates.add(path)

    return sorted(
        candidates,
        key=lambda path: path.relative_to(root).as_posix().encode("utf-8"),
    )


def _migration_files(root: Path) -> list[Path]:
    candidates = [
        path
        for path in root.glob(MIGRATION_GLOB)
        if path.is_file()
        and not path.is_symlink()
        and "target" not in path.relative_to(root).parts
    ]
    return sorted(
        candidates,
        key=lambda path: path.relative_to(root).as_posix().encode("utf-8"),
    )


def _validate_inputs(commit: str, version: str, image_digest: str) -> None:
    if not COMMIT_PATTERN.fullmatch(commit):
        raise ValueError("commit must be exactly 40 lowercase hexadecimal characters")
    if not SEMVER_PATTERN.fullmatch(version):
        raise ValueError("version must be a valid semantic version")
    if not IMAGE_DIGEST_PATTERN.fullmatch(image_digest):
        raise ValueError("image_digest must use the form sha256:<64 lowercase hex characters>")


def build_manifest(
    root: Path,
    commit: str,
    version: str,
    image_ref: str,
    image_digest: str,
) -> dict:
    """Build a deterministic release manifest from repository-controlled inputs."""

    _validate_inputs(commit, version, image_digest)
    root = Path(root).resolve()

    frontend_entries: list[tuple[str, str]] = []
    for path in _frontend_files(root):
        relative_path = path.relative_to(root).as_posix()
        frontend_entries.append((relative_path, _sha256_file(path)))

    migration_items: list[dict[str, str]] = []
    for path in _migration_files(root):
        relative_path = path.relative_to(root).as_posix()
        module = path.relative_to(root).parts[1]
        migration_items.append(
            {
                "module": module,
                "path": relative_path,
                "sha256": _sha256_file(path),
            }
        )

    frontend = {
        "treeSha256": _tree_sha256(frontend_entries),
        "fileCount": len(frontend_entries),
    }
    migrations = {
        "treeSha256": _tree_sha256(
            (item["path"], item["sha256"]) for item in migration_items
        ),
        "count": len(migration_items),
        "items": migration_items,
    }
    unsigned_manifest = {
        "schemaVersion": 1,
        "source": {"commit": commit, "version": version},
        "image": {"ref": image_ref, "digest": image_digest},
        "frontend": frontend,
        "migrations": migrations,
        "manifestSha256": "",
    }
    manifest_sha256 = hashlib.sha256(canonical_json(unsigned_manifest)).hexdigest()
    unsigned_manifest["manifestSha256"] = manifest_sha256
    return unsigned_manifest


def _build_command(args: argparse.Namespace) -> int:
    manifest = build_manifest(
        root=args.root,
        commit=args.commit,
        version=args.version,
        image_ref=args.image_ref,
        image_digest=args.image_digest,
    )
    args.output.write_bytes(canonical_json(manifest))
    print(f"release manifest written: {args.output}")
    return 0


def _verify_command(args: argparse.Namespace) -> int:
    stored_bytes = args.manifest.read_bytes()
    document = json.loads(stored_bytes.decode("utf-8"))
    expected = build_manifest(
        root=args.root,
        commit=document["source"]["commit"],
        version=document["source"]["version"],
        image_ref=document["image"]["ref"],
        image_digest=document["image"]["digest"],
    )
    actual_bytes = canonical_json(expected)
    if stored_bytes != actual_bytes:
        raise ManifestVerificationError(
            f"verification failed for {args.manifest}: manifest does not match repository inputs"
        )
    print(f"release manifest verified: {args.manifest}")
    return 0


def _argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    build_parser = subparsers.add_parser("build", help="build a release manifest")
    build_parser.add_argument("--root", type=Path, required=True)
    build_parser.add_argument("--commit", required=True)
    build_parser.add_argument("--version", required=True)
    build_parser.add_argument("--image-ref", required=True)
    build_parser.add_argument("--image-digest", required=True)
    build_parser.add_argument("--output", type=Path, required=True)
    build_parser.set_defaults(handler=_build_command)

    verify_parser = subparsers.add_parser("verify", help="verify a stored release manifest")
    verify_parser.add_argument("--manifest", type=Path, required=True)
    verify_parser.add_argument("--root", type=Path, required=True)
    verify_parser.set_defaults(handler=_verify_command)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = _argument_parser()
    args = parser.parse_args(argv)
    try:
        return args.handler(args)
    except (KeyError, OSError, TypeError, ValueError, json.JSONDecodeError) as exc:
        print(f"release manifest error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
