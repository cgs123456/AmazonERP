#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Small, dependency-free HTTP load runner for the AmazonERP gateway.

The runner intentionally targets the gateway origin directly (for example
http://127.0.0.1:10010).  It does not add the frontend's /api prefix and it
uses the gateway's ``token`` header contract.
"""
from __future__ import annotations

import argparse
import http.client
import json
import math
import os
import queue
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Dict, Iterable, List, Optional, Tuple
from urllib.parse import urlparse


@dataclass(frozen=True)
class Scenario:
    name: str
    method: str
    path_template: str
    body: Optional[Dict[str, Any]] = None
    requires_shop: bool = True


SCENARIOS: Tuple[Scenario, ...] = (
    Scenario(
        "order-list",
        "GET",
        "/order/list?shopId={shop_id}&page=1&size=20",
    ),
    Scenario(
        "report-dashboard",
        "GET",
        "/report/dashboard/{shop_id}?dateRange=7d",
    ),
    Scenario(
        "finance-profit",
        "GET",
        "/finance/profit/sku/{shop_id}?depositAfter=2026-01-01&depositBefore=2026-12-31",
    ),
    Scenario(
        "inventory-health",
        "GET",
        "/spapi/inventory/health/{shop_id}",
    ),
    Scenario(
        "ai-agent",
        "POST",
        "/ai/agent/chat",
        body={
            "messages": [
                {
                    "role": "user",
                    "content": "Analyze last week's sales and give top 5 inventory actions.",
                }
            ]
        },
        requires_shop=False,
    ),
    Scenario(
        "spapi-sync",
        "POST",
        "/spapi/sync/orders?shopId={shop_id}",
    ),
)

SCENARIOS_BY_NAME = {scenario.name: scenario for scenario in SCENARIOS}
READ_SCENARIOS = tuple(SCENARIOS[:4])
OPT_IN_SCENARIOS = tuple(SCENARIOS[4:])


def percentile(values: Iterable[float], fraction: float) -> float:
    """Return a linearly interpolated percentile for a list of values."""
    ordered = sorted(values)
    if not ordered:
        return 0.0
    if len(ordered) == 1:
        return ordered[0]
    position = (len(ordered) - 1) * fraction
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    weight = position - lower
    return ordered[lower] * (1.0 - weight) + ordered[upper] * weight


def parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--base-url",
        default=os.environ.get("TARGET_BASE_URL", "http://127.0.0.1:10010"),
        help="Gateway origin, without a frontend /api prefix.",
    )
    parser.add_argument(
        "--token",
        default=os.environ.get("AUTH_TOKEN") or os.environ.get("TOKEN", ""),
        help="JWT passed in the gateway's token header.",
    )
    parser.add_argument(
        "--shop-id",
        default=os.environ.get("SHOP_ID", ""),
        help="Shop id used by @ShopScoped endpoints.",
    )
    parser.add_argument(
        "--scenario",
        action="append",
        choices=["all"] + [scenario.name for scenario in SCENARIOS],
        default=[],
        help="Scenario to run; repeat for multiple scenarios. Default: four read paths; AI and sync are opt-in.",
    )
    parser.add_argument("--concurrency", type=int, default=10)
    parser.add_argument("--requests", type=int, default=100)
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--warmup", type=int, default=3)
    parser.add_argument(
        "--output",
        default="",
        help="Optional path for the JSON result. The JSON is always printed to stdout.",
    )
    return parser.parse_args(argv)


def validate_args(args: argparse.Namespace) -> List[Scenario]:
    if args.concurrency < 1:
        raise ValueError("--concurrency must be >= 1")
    if args.requests < 1:
        raise ValueError("--requests must be >= 1")
    if args.timeout <= 0:
        raise ValueError("--timeout must be > 0")
    if args.warmup < 0:
        raise ValueError("--warmup must be >= 0")

    parsed = urlparse(args.base_url)
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        raise ValueError("--base-url must be an http(s) origin")
    if parsed.path.rstrip("/") in ("/api", "/api/"):
        raise ValueError("--base-url must be the gateway origin, not a frontend /api URL")

    requested = args.scenario or ["all"]
    if "all" in requested:
        if len(requested) > 1:
            raise ValueError("--scenario all cannot be combined with explicit scenarios")
        selected = list(READ_SCENARIOS)
    else:
        selected = [SCENARIOS_BY_NAME[name] for name in requested]
    if any(scenario.requires_shop for scenario in selected) and not args.shop_id:
        raise ValueError("--shop-id is required for shop-scoped scenarios")
    return selected


def build_path(scenario: Scenario, shop_id: str) -> str:
    return scenario.path_template.format(shop_id=shop_id)


def request_once(
    scenario: Scenario,
    base_url: str,
    token: str,
    shop_id: str,
    timeout: float,
) -> Dict[str, Any]:
    parsed = urlparse(base_url)
    host = parsed.hostname or ""
    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    connection_class = (
        http.client.HTTPSConnection if parsed.scheme == "https" else http.client.HTTPConnection
    )
    path = (parsed.path.rstrip("/") if parsed.path else "") + build_path(scenario, shop_id)
    body_bytes: Optional[bytes] = None
    headers = {"Accept": "application/json", "User-Agent": "amazon-erp-bench/1.0"}
    if token:
        headers["token"] = token
    if scenario.body is not None:
        body_bytes = json.dumps(scenario.body, ensure_ascii=True).encode("utf-8")
        headers["Content-Type"] = "application/json"

    started = time.perf_counter()
    status = 0
    error = ""
    response_code: Optional[int] = None
    response_bytes = 0
    try:
        connection = connection_class(host, port, timeout=timeout)
        try:
            connection.request(scenario.method, path, body=body_bytes, headers=headers)
            response = connection.getresponse()
            payload = response.read()
            status = response.status
            response_bytes = len(payload)
            if payload:
                try:
                    decoded = json.loads(payload.decode("utf-8"))
                    if isinstance(decoded, dict) and "code" in decoded:
                        response_code = decoded.get("code")
                except (UnicodeDecodeError, json.JSONDecodeError):
                    pass
        finally:
            connection.close()
    except Exception as exc:  # network failures are data for the benchmark
        error = f"{type(exc).__name__}: {exc}"
    elapsed_ms = (time.perf_counter() - started) * 1000.0
    success = 200 <= status < 300 and not error
    if success and response_code is not None and response_code != 200:
        success = False
        error = f"business code {response_code}"
    return {
        "success": success,
        "status": status,
        "response_code": response_code,
        "latency_ms": elapsed_ms,
        "bytes": response_bytes,
        "error": error,
    }


def run_scenario(
    scenario: Scenario,
    base_url: str,
    token: str,
    shop_id: str,
    concurrency: int,
    requests: int,
    timeout: float,
    warmup: int,
) -> Dict[str, Any]:
    for _ in range(warmup):
        request_once(scenario, base_url, token, shop_id, timeout)

    tasks: "queue.Queue[int]" = queue.Queue()
    for index in range(requests):
        tasks.put(index)
    results: List[Dict[str, Any]] = []
    result_lock = threading.Lock()

    def worker() -> None:
        while True:
            try:
                tasks.get_nowait()
            except queue.Empty:
                return
            try:
                result = request_once(scenario, base_url, token, shop_id, timeout)
                with result_lock:
                    results.append(result)
            finally:
                tasks.task_done()

    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = [executor.submit(worker) for _ in range(concurrency)]
        for future in futures:
            future.result()
    duration = time.perf_counter() - started

    latencies = [float(item["latency_ms"]) for item in results]
    success_count = sum(1 for item in results if item["success"])
    failure_count = len(results) - success_count
    status_counts: Dict[str, int] = {}
    for item in results:
        key = str(item["status"])
        status_counts[key] = status_counts.get(key, 0) + 1
    errors = [item["error"] for item in results if item["error"]]
    return {
        "scenario": scenario.name,
        "method": scenario.method,
        "path": build_path(scenario, shop_id),
        "requests": len(results),
        "success_count": success_count,
        "failure_count": failure_count,
        "error_rate": (failure_count / len(results)) if results else 1.0,
        "requests_per_second": (len(results) / duration) if duration else 0.0,
        "duration_seconds": duration,
        "latency_ms": {
            "min": min(latencies) if latencies else 0.0,
            "mean": (sum(latencies) / len(latencies)) if latencies else 0.0,
            "p50": percentile(latencies, 0.50),
            "p95": percentile(latencies, 0.95),
            "p99": percentile(latencies, 0.99),
            "max": max(latencies) if latencies else 0.0,
        },
        "status_counts": status_counts,
        "sample_errors": errors[:5],
    }


def main(argv: Optional[List[str]] = None) -> int:
    args = parse_args(argv)
    try:
        selected = validate_args(args)
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    if not args.token:
        print("error: --token is required (or set AUTH_TOKEN)", file=sys.stderr)
        return 2

    started_at = datetime.now(timezone.utc).isoformat()
    scenario_results = []
    for scenario in selected:
        print(f"[bench] running {scenario.name} ...", file=sys.stderr)
        scenario_results.append(
            run_scenario(
                scenario=scenario,
                base_url=args.base_url,
                token=args.token,
                shop_id=args.shop_id,
                concurrency=args.concurrency,
                requests=args.requests,
                timeout=args.timeout,
                warmup=args.warmup,
            )
        )

    summary = {
        "base_url": args.base_url,
        "shop_id": args.shop_id,
        "token_configured": bool(args.token),
        "concurrency": args.concurrency,
        "requests_per_scenario": args.requests,
        "timeout_seconds": args.timeout,
        "warmup_requests_per_scenario": args.warmup,
        "started_at_utc": started_at,
        "finished_at_utc": datetime.now(timezone.utc).isoformat(),
        "scenarios": scenario_results,
    }
    rendered = json.dumps(summary, ensure_ascii=True, indent=2)
    print(rendered)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as handle:
            handle.write(rendered + "\n")

    if any(item["success_count"] == 0 for item in scenario_results):
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
