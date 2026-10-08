#!/usr/bin/env python3
"""Generate B-bucket offline simulation fixtures from official published models.

Sources (all verbatim snapshots already in-repo, Apache-2.0):
- SP-API OpenAPI models under amz-service-spapi/src/test/resources/contracts/*.json
  (the ``x-amzn-api-sandbox.static[].response`` blocks are Amazon's own published
  sandbox responses; for operations without one, the minimal 202/204 response is
  derived from the official response schema).
- Keepa product object documentation shape (products[].stats.current[] indices).
- DeepSeek / OpenAI-compatible chat-completion response shape.

Nothing here contacts a live platform. Every generated artifact carries an
explicit ``synthetic`` marker so demo output can never be mistaken for real
platform data.
"""

from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SNAPSHOT_DIR = ROOT / "amz-service" / "amz-service-spapi" / "src" / "test" / "resources" / "contracts"
CATALOG = ROOT / "amz-service" / "amz-service-spapi" / "src" / "main" / "java" / "com" / "amz" / "client" / "SpApiOperationCatalog.java"
SPAPI_TARGET = ROOT / "amz-service" / "amz-service-spapi" / "src" / "main" / "resources" / "mock"
AI_TARGET = ROOT / "amz-service" / "amz-service-ai" / "src" / "test" / "resources" / "contracts"
PRODUCT_TARGET = ROOT / "amz-service" / "amz-service-product" / "src" / "test" / "resources" / "contracts"

FAMILIES = {
    "catalogItems": "catalogItems_2022-04-01.json",
    "listingsItems": "listingsItems_2021-08-01.json",
    "notifications": "notifications.json",
    "productPricing": "productPricing_2022-05-01.json",
    "fbaInbound": "fulfillmentInbound_2024-03-20.json",
}

CATALOG_ENTRY = re.compile(
    r'new SpApiOperationSpec\(\s*"([a-zA-Z]+\.[a-zA-Z]+)",\s*"([a-zA-Z]+)",\s*'
    r'"(GET|POST|PUT|PATCH|DELETE)",\s*"(/[^"]+)"'
)


def json_bytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def file_metadata(data: bytes) -> dict[str, object]:
    return {
        "bytes": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
        "lines": data.count(b"\n"),
    }


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_specs() -> dict[str, dict]:
    return {fam: json.loads((SNAPSHOT_DIR / fn).read_text(encoding="utf-8")) for fam, fn in FAMILIES.items()}


def load_catalog_entries() -> list[tuple[str, str, str, str]]:
    text = CATALOG.read_text(encoding="utf-8")
    entries = CATALOG_ENTRY.findall(text)
    if len(entries) != 64:
        raise SystemExit(f"expected 64 catalog entries, regex found {len(entries)}")
    return entries


def deref(spec: dict, ref: str) -> dict:
    node = spec
    for part in ref.lstrip("#/").split("/"):
        node = node[part]
    return node


def schema_minimal(spec: dict, schema: dict) -> object:
    """Derive a minimal deterministic response from an official response schema."""
    if "$ref" in schema:
        node = deref(spec, schema["$ref"])
        out: dict[str, object] = {}
        for name in node.get("required", []):
            prop = node.get("properties", {}).get(name, {})
            if prop.get("type") == "string":
                out[name] = "SYNTHETIC-" + name
            elif prop.get("type") == "integer":
                out[name] = 0
            else:
                out[name] = "SYNTHETIC-" + name
        return out
    if schema.get("type") == "object":
        return {}
    return {}


def official_response(spec: dict, method: str, path: str) -> tuple[object, str]:
    """Return (payload, source) for one catalog operation."""
    op = spec["paths"][path][method.lower()]
    for code in ("200", "202", "204"):
        resp = op.get("responses", {}).get(code, {})
        static = resp.get("x-amzn-api-sandbox", {}).get("static", [])
        if static and static[0].get("response") is not None:
            return static[0]["response"], f"official-sandbox-static[{code}]"
        schema = resp.get("schema", resp.get("content", {}).get("application/json", {}).get("schema"))
        if schema is not None:
            return schema_minimal(spec, schema), f"official-schema[{code}]"
    if code == "204" or resp.get("description", "").lower().startswith("no content"):
        return {}, "official-schema[204]"
    raise SystemExit(f"no official response shape found for {method} {path}")


def build_spapi_mock() -> tuple[dict[str, object], dict[str, object]]:
    specs = load_specs()
    entries = load_catalog_entries()
    responses: dict[str, object] = {}
    sources: dict[str, str] = {}
    for operation_id, family, method, path in entries:
        payload, source = official_response(specs[family], method, path)
        body: dict[str, object] = {"synthetic": True}
        if isinstance(payload, dict):
            body.update(payload)
        elif payload is None:
            body["payload"] = None
        else:
            body["payload"] = payload
        responses[operation_id] = body
        sources[operation_id] = f"{FAMILIES[family]}:{source}"
    provenance = {
        "fixtureVersion": 1,
        "generatedBy": "tools/contract-fixtures/generate_b_bucket_fixtures.py",
        "sources": {
            fam: {
                "file": FAMILIES[fam],
                "sha256": sha256_file(SNAPSHOT_DIR / FAMILIES[fam]),
                "publisher": "amzn/selling-partner-api-models (Apache-2.0)",
                "usage": "x-amzn-api-sandbox.static responses verbatim; schema-derived minimal for ops without one",
            }
            for fam in FAMILIES
        },
        "operations": sources,
        "syntheticPolicy": (
            "Every operation response is wrapped with top-level synthetic=true. "
            "Payload fields come verbatim from Amazon's published sandbox blocks "
            "(including Amazon's own TEST_CASE_* placeholder values) or are derived "
            "from the official response schema. Never activate against prod profiles."
        ),
    }
    return responses, provenance


KEEPA_INDICES = {
    "1": "NEW price in cents (US$29.99 -> 2999)",
    "3": "SalesRank",
    "16": "ReviewCount",
    "18": "RatingMilli (4500 = 4.50)",
}


def build_keepa_fixture() -> tuple[dict[str, object], dict[str, object]]:
    current: list[object] = [-1] * 19
    current[1] = 2999
    current[3] = 15234
    current[16] = 1287
    current[18] = 4500
    product = {
        "asin": "B0SYN000001",
        "title": "SYN Wireless Earbuds Pro, Bluetooth 5.3, USB-C, Black",
        "domainId": 1,
        "stats": {"current": current},
    }
    fixture = {
        "synthetic": True,
        "timestamp": 1760000000000,
        "products": [product],
        "indicesNote": KEEPA_INDICES,
    }
    provenance = {
        "fixtureVersion": 1,
        "generatedBy": "tools/contract-fixtures/generate_b_bucket_fixtures.py",
        "sources": {
            "shape": "Keepa product object documentation (products[].stats.current[] index layout)",
            "indices": KEEPA_INDICES,
            "inRepoConsumer": "KeepaCompetitorScheduler.parseKeepaStats",
        },
        "syntheticPolicy": (
            "top-level synthetic=true, title carries the SYN prefix used by the demo "
            "data recognizability system; values are deterministic placeholders in "
            "Keepa's official units (cents / milli-rating)."
        ),
    }
    return fixture, provenance


def build_deepseek_fixture() -> tuple[dict[str, object], dict[str, object]]:
    fixture = {
        "synthetic": True,
        "id": "chatcmpl-synthetic-0001",
        "object": "chat.completion",
        "created": 1760000000,
        "model": "deepseek-chat",
        "choices": [
            {
                "index": 0,
                "message": {"role": "assistant", "content": "SYNTHETIC assistant reply from the offline demo fixture."},
                "finish_reason": "stop",
            }
        ],
        "usage": {"prompt_tokens": 12, "completion_tokens": 9, "total_tokens": 21},
    }
    provenance = {
        "fixtureVersion": 1,
        "generatedBy": "tools/contract-fixtures/generate_b_bucket_fixtures.py",
        "sources": {
            "shape": "OpenAI-compatible /chat/completions response schema (DeepSeek API docs follow it)",
            "inRepoConsumer": "AiServiceImpl.extractContent (choices[0].message.content)",
        },
        "syntheticPolicy": (
            "top-level synthetic=true plus SYNTHETIC content prefix; never send "
            "network traffic to DeepSeek without a real key."
        ),
    }
    return fixture, provenance


def write_target(path: Path, payload: object, provenance: dict, readme_title: str,
                 filename: str = "fixtures.json") -> None:
    path.mkdir(parents=True, exist_ok=True)
    data = json_bytes(payload)
    prov_bytes = json_bytes(provenance)
    (path / filename).write_bytes(data)
    (path / "provenance.json").write_bytes(prov_bytes)
    readme = (
        f"# {readme_title}\n\n"
        "Generated by `tools/contract-fixtures/generate_b_bucket_fixtures.py` from the\n"
        "official published models already vendored in this repository. Regenerate with:\n\n"
        "```\npython tools/contract-fixtures/generate_b_bucket_fixtures.py\n```\n\n"
        "Do not hand-edit: digest + coverage are asserted by contract tests.\n"
    )
    (path / "README.md").write_text(readme, encoding="utf-8")
    print(f"wrote {path} fixtures={len(data)}B provenance={len(prov_bytes)}B")


def main() -> int:
    responses, spapi_prov = build_spapi_mock()
    write_target(SPAPI_TARGET, responses, spapi_prov, "SP-API mock sandbox responses (mock profile)")
    keepa, keepa_prov = build_keepa_fixture()
    write_target(PRODUCT_TARGET, keepa, keepa_prov, "Keepa product/stats contract fixture",
                 "keepa-product-stats.json")
    deepseek, deepseek_prov = build_deepseek_fixture()
    write_target(AI_TARGET, deepseek, deepseek_prov, "DeepSeek chat-completion contract fixture",
                 "deepseek-chat-completion.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())
