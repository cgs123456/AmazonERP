#!/usr/bin/env python3
"""Fail-closed repository hygiene checks for release candidates.

The scanner intentionally uses only the Python standard library. It scans Git
tracked files in CI and can additionally walk a local worktree for ignored
runtime artefacts before a release is frozen.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable, Sequence

RULES_VERSION = 1
LARGE_FILE_BYTES = 10 * 1024 * 1024

FORBIDDEN_ROOT_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"^_[^/]*\.log$", re.IGNORECASE), "root-runtime-artifact"),
    (re.compile(r"^mvn-[^/]*\.log$", re.IGNORECASE), "root-maven-log"),
    (re.compile(r"^[^/]*\.err\.log$", re.IGNORECASE), "root-runtime-artifact"),
    (re.compile(r"^round\d+[^/]*\.(?:json|log)$", re.IGNORECASE), "root-round-artifact"),
    (re.compile(r"^full\d*\.log$", re.IGNORECASE), "root-runtime-artifact"),
)
FORBIDDEN_PATH_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"(?:^|/)\.patch[^/]*\.py$", re.IGNORECASE), "temporary-script"),
    (re.compile(r"(?:^|/)[^/]+\.(?:tmp|bak|orig|rej)$", re.IGNORECASE), "temporary-file"),
)
SECRET_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b"), "aws-access-key"),
    (re.compile(r"\bgh[pousr]_[A-Za-z0-9_]{20,}\b"), "github-token"),
    (
        re.compile(r"(?im)^\s*-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----\s*$"),
        "private-key",
    ),
)
ASSIGNMENT_PATTERN = re.compile(
    r"""(?ix)
    \b(password|passwd|pwd|secret|token|api[_-]?key|client[_-]?secret)\b
    \s*[:=]\s*
    ["']?([^\s"'#;]{8,})
    """
)
PLACEHOLDER_MARKERS = (
    "${",
    "{{",
    "<",
    "example",
    "placeholder",
    "change-me",
    "change_me",
    "replace",
    "dummy",
    "mock",
    "test",
    "your_",
    "your-",
)
SAFE_SECRET_VALUES = {
    "admin",
    "changeme",
    "ci_root_pass",
    "guest",
    "minioadmin",
    "mysql",
    "password",
    "rabbitmq",
    "redis",
    "root",
}
SKIPPED_WALK_DIRS = {
    ".git",
    ".idea",
    ".mvn",
    ".pytest_cache",
    ".venv",
    "__pycache__",
    "dist",
    "logs",
    "node_modules",
    "out",
    "target",
}


@dataclass(frozen=True, order=True)
class Finding:
    rule: str
    path: str
    detail: str

    def to_dict(self) -> dict[str, str]:
        return asdict(self)


@dataclass(frozen=True)
class AllowlistEntry:
    path: str
    rule: str
    sha256: str


class HygieneAllowlist:
    """Exact path/rule/content-hash exceptions; wildcard exceptions are rejected."""

    def __init__(self, entries: Iterable[AllowlistEntry] = ()) -> None:
        self._entries = tuple(sorted(entries, key=lambda item: (item.path, item.rule, item.sha256)))

    @classmethod
    def empty(cls) -> "HygieneAllowlist":
        return cls()

    @classmethod
    def from_entries(cls, entries: Sequence[dict[str, str]]) -> "HygieneAllowlist":
        parsed: list[AllowlistEntry] = []
        for index, entry in enumerate(entries):
            try:
                path = str(entry["path"]).replace("\\", "/")
                rule = str(entry["rule"])
                sha256 = str(entry["sha256"]).lower()
            except (KeyError, TypeError) as exc:
                raise ValueError(f"allowlist entry {index} must contain path, rule, sha256") from exc
            if not path or path.startswith("/") or ".." in Path(path).parts:
                raise ValueError(f"allowlist entry {index} has unsafe path: {path!r}")
            if "*" in path or "?" in path or "[" in path:
                raise ValueError(f"allowlist entry {index} must not contain wildcards")
            if not re.fullmatch(r"[0-9a-f]{64}", sha256):
                raise ValueError(f"allowlist entry {index} has invalid sha256: {sha256!r}")
            parsed.append(AllowlistEntry(path=path, rule=rule, sha256=sha256))
        return cls(parsed)

    @classmethod
    def from_file(cls, path: Path) -> "HygieneAllowlist":
        if not path.exists():
            return cls.empty()
        document = json.loads(path.read_text(encoding="utf-8"))
        if document.get("schemaVersion") != 1:
            raise ValueError(f"unsupported allowlist schema in {path}")
        entries = document.get("entries", [])
        if not isinstance(entries, list):
            raise ValueError(f"allowlist entries must be a list in {path}")
        return cls.from_entries(entries)

    def allows(self, finding: Finding, content_sha256: str) -> bool:
        return AllowlistEntry(
            path=finding.path,
            rule=finding.rule,
            sha256=content_sha256.lower(),
        ) in self._entries


def _run_git(root: Path, args: Sequence[str]) -> list[Path]:
    completed = subprocess.run(
        ["git", "-C", str(root), *args],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if completed.returncode != 0:
        message = completed.stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError(f"git {' '.join(args)} failed: {message}")
    return [
        root / raw.decode("utf-8", errors="surrogateescape")
        for raw in completed.stdout.split(b"\0")
        if raw
    ]


def _walk_files(root: Path) -> list[Path]:
    files: list[Path] = []
    for current_root, dirs, names in os.walk(root):
        dirs[:] = sorted(name for name in dirs if name not in SKIPPED_WALK_DIRS)
        current = Path(current_root)
        for name in sorted(names):
            files.append(current / name)
    return files


def _candidate_files(root: Path, include_untracked: bool) -> list[Path]:
    if include_untracked:
        return sorted(_walk_files(root), key=lambda path: path.relative_to(root).as_posix())
    if (root / ".git").exists():
        return sorted(_run_git(root, ["ls-files", "-z"]), key=lambda path: path.relative_to(root).as_posix())
    return sorted(_walk_files(root), key=lambda path: path.relative_to(root).as_posix())


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _is_placeholder(value: str) -> bool:
    lowered = value.lower()
    if lowered in SAFE_SECRET_VALUES:
        return True
    return any(marker in lowered for marker in PLACEHOLDER_MARKERS)


def _secret_findings(relative_path: str, data: bytes) -> list[Finding]:
    if b"\0" in data:
        return []
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        return []

    findings: list[Finding] = []
    for pattern, rule in SECRET_PATTERNS:
        for match in pattern.finditer(text):
            line = text.count("\n", 0, match.start()) + 1
            findings.append(Finding(rule, relative_path, f"line {line}"))
    for match in ASSIGNMENT_PATTERN.finditer(text):
        value = match.group(2).strip().strip("'\"")
        if _is_placeholder(value):
            continue
        line_start = text.rfind("\n", 0, match.start()) + 1
        line_end = text.find("\n", match.end())
        if line_end < 0:
            line_end = len(text)
        line = text[line_start:line_end]
        if any(pattern.search(line) for pattern, _ in SECRET_PATTERNS):
            continue
        findings.append(Finding("secret-like-assignment", relative_path, f"line {text.count(chr(10), 0, match.start()) + 1}"))
    return findings


def scan_repository(
    root: Path,
    include_untracked: bool,
    allowlist: HygieneAllowlist,
) -> list[Finding]:
    root = root.resolve()
    if not root.is_dir():
        raise ValueError(f"repository root is not a directory: {root}")

    raw_findings: list[tuple[Finding, str]] = []
    for path in _candidate_files(root, include_untracked):
        if not path.is_file() or path.is_symlink():
            continue
        relative_path = path.relative_to(root).as_posix()
        content_sha256 = _sha256_file(path)
        size = path.stat().st_size

        if size > LARGE_FILE_BYTES:
            raw_findings.append(
                (Finding("large-file", relative_path, f"{size} bytes exceeds {LARGE_FILE_BYTES}"), content_sha256)
            )
            continue

        for pattern, rule in FORBIDDEN_ROOT_PATTERNS:
            if "/" not in relative_path and pattern.search(relative_path):
                raw_findings.append((Finding(rule, relative_path, "forbidden root runtime artefact"), content_sha256))
        for pattern, rule in FORBIDDEN_PATH_PATTERNS:
            if pattern.search(relative_path):
                raw_findings.append((Finding(rule, relative_path, "forbidden temporary artefact"), content_sha256))

        data = path.read_bytes()
        for finding in _secret_findings(relative_path, data):
            raw_findings.append((finding, content_sha256))

    return [
        finding
        for finding, content_sha256 in sorted(raw_findings, key=lambda item: (item[0].path, item[0].rule, item[0].detail))
        if not allowlist.allows(finding, content_sha256)
    ]


def _report(findings: Sequence[Finding]) -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "rulesVersion": RULES_VERSION,
        "summary": {"findings": len(findings)},
        "findings": [finding.to_dict() for finding in findings],
    }


def _parse_args(argv: Sequence[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Scan an AmazonERP tree for release hygiene violations.")
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--include-untracked", action="store_true")
    parser.add_argument("--allowlist", type=Path)
    parser.add_argument("--json", type=Path)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = _parse_args(argv)
    try:
        root = args.root.resolve()
        allowlist_path = args.allowlist or root / "tools/release/hygiene-allowlist.json"
        allowlist = HygieneAllowlist.from_file(allowlist_path)
        findings = scan_repository(root, args.include_untracked, allowlist)
    except (OSError, RuntimeError, ValueError, json.JSONDecodeError) as exc:
        print(f"repository hygiene configuration error: {exc}", file=sys.stderr)
        return 2

    report = _report(findings)
    rendered = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(rendered, encoding="utf-8", newline="\n")
    else:
        print(rendered, end="")

    if findings:
        print(f"repository hygiene failed: {len(findings)} finding(s)", file=sys.stderr)
        return 1
    print("repository hygiene passed", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())