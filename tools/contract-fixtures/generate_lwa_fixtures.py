#!/usr/bin/env python3
"""Generate and verify the official LWA token-exchange contract fixtures.

The source values are copied from Amazon's public SP-API documentation. They are
documentation examples, not credentials, and must never be used against Amazon.
"""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TARGET = (
    ROOT
    / "amz-service"
    / "amz-service-spapi"
    / "src"
    / "test"
    / "resources"
    / "contracts"
    / "lwa-token"
)

SOURCE = {
    "publisher": "Amazon",
    "document": "Connect to the SP-API",
    "canonicalUrl": "https://developer-docs.amazon.com/sp-api/docs/connecting-to-the-selling-partner-api",
    "markdownUrl": "https://developer-docs.amazon.com/sp-api/docs/connecting-to-the-selling-partner-api.md",
    "documentUpdatedAt": "2026-09-09T22:32:25.000Z",
    "snapshotRetrievedAt": "2026-09-24T19:04:34+08:00",
    "snapshotBytes": 19403,
    "snapshotSha256": "d8ff4f0d83ab41f04cbbe3266b17d65b47e966cb814b88464e7010f399a344cb",
    "requestSnippetLines": "40-48",
    "responseSnippetLines": "75-85",
}

REQUEST = {
    "fixtureVersion": 1,
    "operation": "lwa_refresh_token_exchange",
    "http": {
        "method": "POST",
        "url": "https://api.amazon.com/auth/o2/token",
        "contentTypeExampleLiteral": "application/x-www-form-urlencoded;charset=UTF-8",
        "contentTypeMediaType": "application/x-www-form-urlencoded",
    },
    "form": {
        "grant_type": "refresh_token",
        "refresh_token": "Aztr|...",
        "client_id": "foodev",
        "client_secret": "Y76SDl2F",
    },
}

SUCCESS = {
    "access_token": "Atza|IQEBLjAsAhRmHjNgHpi0U-Dme37rR6CuUpSREXAMPLE",
    "token_type": "bearer",
    "expires_in": 3600,
    "refresh_token": "Atzr|IQEBLzAtAhRPpMJxdwVz2Nn6f2y-tpJX2DeXEXAMPLE",
}


def json_bytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def file_metadata(data: bytes) -> dict[str, object]:
    return {
        "bytes": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
        "lines": data.count(b"\n"),
    }


def build() -> dict[Path, bytes]:
    request_bytes = json_bytes(REQUEST)
    success_bytes = json_bytes(SUCCESS)
    provenance = {
        "fixtureVersion": 1,
        "source": SOURCE,
        "generatedBy": "tools/contract-fixtures/generate_lwa_fixtures.py",
        "officialSdk": {
            "used": False,
            "reason": (
                "The official documentation contains a complete known-answer request and "
                "response example; no SDK execution is needed for this fixture."
            ),
        },
        "files": {
            "refresh-token-request.json": file_metadata(request_bytes),
            "refresh-token-success.json": file_metadata(success_bytes),
        },
    }
    provenance_bytes = json_bytes(provenance)

    readme = f'''# LWA Token Contract Fixtures

This directory stores the known-answer LWA refresh-token exchange example published
by Amazon. Every `refresh_token`, `client_secret`, and token value below is a public
documentation example, **not a real credential**, and must never be used against
Amazon.

## Provenance

| Item | Value |
|---|---|
| Official document | [Connect to the SP-API]({SOURCE["canonicalUrl"]}) |
| Markdown snapshot | [{SOURCE["markdownUrl"]}]({SOURCE["markdownUrl"]}) |
| Document `updatedAt` | `{SOURCE["documentUpdatedAt"]}` |
| Snapshot retrieved at | `{SOURCE["snapshotRetrievedAt"]}` |
| Snapshot bytes | `{SOURCE["snapshotBytes"]}` |
| Snapshot SHA-256 | `{SOURCE["snapshotSha256"]}` |
| Request example lines | `{SOURCE["requestSnippetLines"]}` |
| Success response lines | `{SOURCE["responseSnippetLines"]}` |
| Generate/check command | `python tools/contract-fixtures/generate_lwa_fixtures.py [--check]` |

This fixture uses the complete official documentation example (Step 4 path (a));
no official SDK was invoked, so there is no SDK coordinate/version to record. JSON
whitespace is normalized, while every field name and value is copied from the
official example. The source snapshot SHA-256 and fixture SHA-256 values make drift
independently detectable.

## Files

| File | Bytes | SHA-256 | Purpose |
|---|---:|---|---|
| `refresh-token-request.json` | {len(request_bytes)} | `{hashlib.sha256(request_bytes).hexdigest()}` | POST URL, media type, and the four form fields |
| `refresh-token-success.json` | {len(success_bytes)} | `{hashlib.sha256(success_bytes).hexdigest()}` | `access_token`, `token_type=bearer`, `expires_in`, `refresh_token` |
| `provenance.json` | {len(provenance_bytes)} | `{hashlib.sha256(provenance_bytes).hexdigest()}` | Source, retrieval time, generator, and fixture digests |

## Consumers

`amz-service/amz-service-spapi/src/test/java/com/amz/auth/LwaTokenExchangeContractTest.java`
loads these files from the test classpath and asserts that:

- the outbound request matches the official URL, method, media type, and form fields;
- the official success response parses and requires `token_type=bearer`;
- missing or invalid `access_token`, `token_type`, or `expires_in` fails closed;
- the byte counts and SHA-256 values recorded in `provenance.json` match the files.

## Evidence Boundary

The official documentation fixture is **E3** evidence. Running it through an
in-process transport proves only that this repository's implementation matches the
documented contract (**E2** code-level verification); it does not replace a real
credential-backed **A5/E4-E5** integration run.
'''

    return {
        TARGET / "refresh-token-request.json": request_bytes,
        TARGET / "refresh-token-success.json": success_bytes,
        TARGET / "provenance.json": provenance_bytes,
        TARGET / "README.md": readme.encode("utf-8"),
    }


def main() -> int:
    check = "--check" in sys.argv[1:]
    expected = build()
    drift: list[Path] = []
    for path, data in expected.items():
        if check:
            if not path.exists() or path.read_bytes() != data:
                drift.append(path)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)

    if check:
        if drift:
            print("LWA fixture drift detected:")
            for path in drift:
                print(f"  - {path.relative_to(ROOT)}")
            return 1
        print(f"LWA fixtures are up to date ({len(expected)} files)")
        return 0

    print(f"Wrote {len(expected)} LWA fixtures to {TARGET}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())