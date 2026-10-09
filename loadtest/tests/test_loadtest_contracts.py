# -*- coding: utf-8 -*-
"""Static contracts for the performance test assets.

These tests intentionally do not start the ERP stack. They prevent a
performance run from silently measuring 404/401 responses because an old
script used the wrong gateway path or authentication header.
"""
from __future__ import annotations

import importlib.util
import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LOADTEST = ROOT / "loadtest"
CODE_SUFFIXES = {".jmx", ".scala", ".ps1", ".sh", ".py"}
CODE_FILES = [
    path
    for path in LOADTEST.rglob("*")
    if path.is_file()
    and path.suffix in CODE_SUFFIXES
    and "tests" not in path.parts
]
TEXT = {path: path.read_text(encoding="utf-8") for path in CODE_FILES}


def load_bench_module():
    spec = importlib.util.spec_from_file_location(
        "bench_contract", LOADTEST / "scripts" / "bench.py"
    )
    if spec is None or spec.loader is None:
        raise RuntimeError("could not load bench.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class LoadtestContractTest(unittest.TestCase):
    def test_no_unsupported_gateway_api_prefixes(self):
        pattern = re.compile(r"/api/(order|report|finance|ai|spapi)\b", re.IGNORECASE)
        offenders = {
            str(path.relative_to(ROOT)): sorted(set(pattern.findall(text)))
            for path, text in TEXT.items()
            if pattern.search(text)
        }
        self.assertEqual({}, offenders, "gateway has no generic /api/** route")

    def test_authentication_uses_token_header(self):
        for path, text in TEXT.items():
            self.assertNotIn("Authorization", text, str(path.relative_to(ROOT)))
            self.assertNotIn("Bearer ", text, str(path.relative_to(ROOT)))

    def test_jmeter_uses_real_shop_scoped_routes(self):
        raw = (LOADTEST / "jmeter" / "order-api-stress-test.jmx").read_text(encoding="utf-8")
        for path in [
            "${context}/order/list",
            "${context}/spapi/sync/orders",
            "${context}/finance/profit/sku/${shopId}",
            "${context}/report/dashboard/${shopId}",
            "${context}/spapi/inventory/health/${shopId}",
            "${context}/ai/agent/chat",
        ]:
            self.assertIn(f'name="HTTPSampler.path">{path}</stringProp>', raw)
        self.assertIn('name="Header.name">token</stringProp>', raw)

    def test_gatling_uses_real_shop_scoped_routes(self):
        raw = (LOADTEST / "gatling" / "ErpStressTest.scala").read_text(encoding="utf-8")
        for path in [
            's"$apiContext/order/list"',
            's"$apiContext/spapi/sync/orders"',
            's"$apiContext/finance/profit/sku/$shopId"',
            's"$apiContext/report/dashboard/$shopId"',
            's"$apiContext/spapi/inventory/health/$shopId"',
            's"$apiContext/ai/agent/chat"',
        ]:
            self.assertIn(path, raw)
        self.assertIn('.header("token", authToken)', raw)
        self.assertIn('System.getProperty("target.context", "")', raw)

    def test_ai_payloads_use_agent_chat_dto(self):
        jmeter = (LOADTEST / "jmeter" / "order-api-stress-test.jmx").read_text(encoding="utf-8")
        gatling = (LOADTEST / "gatling" / "ErpStressTest.scala").read_text(encoding="utf-8")
        runner = LOADTEST / "scripts" / "bench.py"
        self.assertIn("&quot;messages&quot;", jmeter)
        self.assertIn('"messages"', gatling)
        self.assertTrue(runner.is_file(), "bench.py must exist")
        self.assertIn('"messages"', runner.read_text(encoding="utf-8"))
        for name, raw in {"jmeter": jmeter, "gatling": gatling}.items():
            self.assertNotIn("sessionId", raw, name)

    def test_default_all_is_read_only(self):
        module = load_bench_module()
        args = module.parse_args([])
        args.shop_id = "1"
        selected = [scenario.name for scenario in module.validate_args(args)]
        self.assertEqual(
            ["order-list", "report-dashboard", "finance-profit", "inventory-health"],
            selected,
        )
        self.assertNotIn("ai-agent", selected)
        self.assertNotIn("spapi-sync", selected)

    def test_jmeter_disables_ai_and_asserts_business_code(self):
        raw = (LOADTEST / "jmeter" / "order-api-stress-test.jmx").read_text(
            encoding="utf-8"
        )
        self.assertIn('testname="ai-agent" enabled="false"', raw)
        self.assertGreaterEqual(raw.count("<JSONPathAssertion "), 6)
        self.assertIn('<stringProp name="JSON_PATH">$.code</stringProp>', raw)
        self.assertIn('<stringProp name="EXPECTED_VALUE">200</stringProp>', raw)

    def test_gatling_ai_is_opt_in(self):
        raw = (LOADTEST / "gatling" / "ErpStressTest.scala").read_text(
            encoding="utf-8"
        )
        self.assertIn('Integer.getInteger("users.chat", 0)', raw)
        self.assertIn("if (usersChat > 0)", raw)

    def test_quick_bench_delegates_to_real_runner(self):
        ps1 = (LOADTEST / "scripts" / "quick-bench.ps1").read_text(encoding="utf-8")
        sh = (LOADTEST / "scripts" / "quick-bench.sh").read_text(encoding="utf-8")
        for name, raw in {"quick-bench.ps1": ps1, "quick-bench.sh": sh}.items():
            self.assertIn("bench.py", raw, name)
        self.assertNotIn('"-n"', ps1)
        self.assertNotIn('"-c"', ps1)
        self.assertNotIn("apachebench", sh.lower())
        self.assertNotIn(" ab ", sh.lower())

    def test_python_runner_supports_percentiles_and_scenarios(self):
        raw = (LOADTEST / "scripts" / "bench.py").read_text(encoding="utf-8")
        for token in [
            "ThreadPoolExecutor",
            "def percentile",
            '"p50"',
            '"p95"',
            '"p99"',
            '"requests_per_second"',
            '"error_rate"',
            '"order-list"',
            '"report-dashboard"',
            '"finance-profit"',
            '"inventory-health"',
            '"ai-agent"',
            '"token"',
        ]:
            self.assertIn(token, raw)

    def test_readme_marks_targets_as_unmeasured(self):
        raw = (LOADTEST / "jmeter" / "README.md").read_text(encoding="utf-8")
        self.assertIn("## Target values (not measured)", raw)
        self.assertIn("not a measured baseline", raw)


if __name__ == "__main__":
    unittest.main()

