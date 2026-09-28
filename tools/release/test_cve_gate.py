"""Contract tests for tools/release/cve_gate.py.

These tests exist because a waiver registry is easy to write and very easy to
let rot: an entry that outlives its expiry, or that quietly applies to every
image, turns a hard gate back into a rubber stamp.  Each test below pins one
way the gate must refuse to be lenient.
"""

import datetime
import io
import json
import re
import unittest
from contextlib import redirect_stdout
from pathlib import Path

from tools.release import cve_gate

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "tools" / "release" / "testdata" / "cve_gate"
REAL_WAIVERS = ROOT / "tools" / "release" / "cve-waivers.json"
BAKE = ROOT / "docker-bake.hcl"


def run(scan, image, waivers=None, cutoff="high", today="2026-09-28", fail_on_stale=False):
    args = ["check", "--scan", str(scan), "--image", image, "--today", today]
    if waivers is not None:
        args += ["--waivers", str(waivers)]
    args += ["--cutoff", cutoff]
    if fail_on_stale:
        args += ["--fail-on-stale"]
    buf = io.StringIO()
    with redirect_stdout(buf):
        code = cve_gate.main(args)
    return code, buf.getvalue()


def report(scan, image, waivers=None, cutoff="high", today="2026-09-28"):
    out = Path(scan).with_suffix(".report.json")
    args = ["check", "--scan", str(scan), "--image", image, "--today", today,
            "--cutoff", cutoff, "--report", str(out)]
    if waivers is not None:
        args += ["--waivers", str(waivers)]
    buf = io.StringIO()
    with redirect_stdout(buf):
        cve_gate.main(args)
    text = out.read_text(encoding="utf-8")
    out.unlink()
    return json.loads(text)


class GateRefusesToBeLenient(unittest.TestCase):
    def test_unwaived_high_finding_fails_the_build(self):
        # RED guard: an image nobody wrote a waiver for must not pass.
        code, _ = run(DATA / "grype-sample.json", "amazonerp-order", waivers=DATA / "waivers.json")
        self.assertEqual(1, code)

    def test_waiver_scoped_to_one_image_does_not_leak_to_another(self):
        rep = report(DATA / "grype-sample.json", "amazonerp-order", waivers=DATA / "waivers.json")
        self.assertEqual(0, len(rep["waived"]))
        types = sorted(v["type"] for v in rep["violations"])
        self.assertEqual(["unwaived", "unwaived"], types)

    def test_matching_waiver_passes(self):
        code, _ = run(DATA / "grype-gateway-clean.json", "amazonerp-gateway", waivers=DATA / "waivers.json")
        self.assertEqual(0, code)

    def test_findings_below_cutoff_are_not_gated(self):
        rep = report(DATA / "grype-sample.json", "amazonerp-gateway", waivers=DATA / "waivers.json")
        gated = {v["finding"]["vulnerability"] for v in rep["violations"]} | {
            w["finding"]["vulnerability"] for w in rep["waived"]
        }
        self.assertNotIn("GHSA-test-medium", gated)

    def test_expired_waiver_is_itself_a_failure(self):
        # The whole point of the registry: acceptance must expire, not drift.
        code, _ = run(DATA / "grype-gateway-clean.json", "amazonerp-gateway",
                      waivers=DATA / "waivers.json", today="2027-01-01")
        self.assertEqual(1, code)
        rep = report(DATA / "grype-gateway-clean.json", "amazonerp-gateway",
                     waivers=DATA / "waivers.json", today="2027-01-01")
        self.assertTrue(all(v["type"] == "expired-waiver" for v in rep["violations"]))

    def test_expiry_boundary_is_inclusive_on_the_last_day(self):
        code, _ = run(DATA / "grype-gateway-clean.json", "amazonerp-gateway",
                      waivers=DATA / "waivers.json", today="2026-12-31")
        self.assertEqual(0, code)

    def test_version_pin_stops_the_waiver_applying_to_a_new_version(self):
        code, _ = run(DATA / "grype-netty-other-version.json", "amazonerp-gateway",
                      waivers=DATA / "waivers.json")
        self.assertEqual(1, code)

    def test_stale_waiver_is_reported_and_can_be_made_fatal(self):
        # No finding at/above the cutoff, so the gateway waiver is unused -- it is stale.
        clean = DATA / "grype-no-high.json"
        rep = report(clean, "amazonerp-gateway", waivers=DATA / "waivers.json")
        self.assertEqual(0, len(rep["violations"]))
        self.assertIn("sample-netty", [s["waiver"] for s in rep["stale"]])
        code, _ = run(clean, "amazonerp-gateway", waivers=DATA / "waivers.json", fail_on_stale=True)
        self.assertEqual(1, code)

    def test_malformed_registry_is_a_usage_error_not_a_pass(self):
        code, _ = run(DATA / "grype-sample.json", "amazonerp-gateway", waivers=DATA / "waivers-invalid.json")
        self.assertEqual(2, code)

    def test_missing_scan_file_is_a_usage_error(self):
        code, _ = run(DATA / "does-not-exist.json", "amazonerp-gateway", waivers=DATA / "waivers.json")
        self.assertEqual(2, code)


class ReportIsDeterministic(unittest.TestCase):
    def test_same_inputs_produce_the_same_report(self):
        first = report(DATA / "grype-sample.json", "amazonerp-gateway", waivers=DATA / "waivers.json")
        second = report(DATA / "grype-sample.json", "amazonerp-gateway", waivers=DATA / "waivers.json")
        self.assertEqual(
            json.dumps(first, sort_keys=True, ensure_ascii=False),
            json.dumps(second, sort_keys=True, ensure_ascii=False),
        )


class ShippedRegistryIsWellFormed(unittest.TestCase):
    """The real waiver file is part of the release gate, so it is pinned too."""

    def setUp(self):
        self.waivers = cve_gate.load_waivers(str(REAL_WAIVERS))

    def test_registry_loads_and_every_entry_is_complete(self):
        self.assertTrue(self.waivers)
        for waiver in self.waivers:
            self.assertTrue(waiver["images"])
            self.assertTrue(waiver["vulnerabilities"])
            self.assertTrue(waiver["owner"].strip())
            self.assertTrue(waiver["justification"].strip())
            self.assertIsInstance(waiver["expires"], datetime.date)

    def test_image_names_must_exist_in_docker_bake(self):
        # A typo in an image name would silently disable a waiver forever.
        bake_images = set(re.findall(r"amazonerp-[a-z0-9-]+", BAKE.read_text(encoding="utf-8")))
        named = {img for waiver in self.waivers for img in waiver["images"]}
        self.assertTrue(named, "no waiver names any image")
        self.assertEqual(set(), named - bake_images, "waiver references images that docker-bake.hcl never builds")

    def test_no_waiver_uses_the_wildcard_image(self):
        for waiver in self.waivers:
            self.assertNotIn("*", waiver["images"], "%s must name images explicitly" % waiver["id"])

    def test_no_waiver_is_already_expired(self):
        today = datetime.date.today()
        for waiver in self.waivers:
            self.assertGreaterEqual(waiver["expires"], today, "%s is already expired" % waiver["id"])


if __name__ == "__main__":
    unittest.main()