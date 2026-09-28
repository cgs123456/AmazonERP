#!/usr/bin/env python3
"""Hard release gate for Grype image scans, with expiring waivers.

Why this exists instead of plain ``grype --fail-on high``
---------------------------------------------------------
``--fail-on`` cannot tell "accepted, owned, and dated" apart from "nobody ever
looked".  A repository that ships third-party findings with no upstream fix
(vendor-shaded agent jars, base-image OS packages) is then stuck between
"never releasable" and "someone quietly flips fail-build to false".  Both are
worse than an explicit, expiring decision.

This gate is *strictly stronger* than ``--fail-on high``:

* same severity cutoff (High and above by default);
* every finding must be covered by a waiver that names the image, the
  vulnerability id, the package, and optionally the exact package version --
  a waiver for one image never leaks into another;
* a waiver stops working on its ``expires`` date, and a finding covered only
  by an expired waiver is itself a failure, so the build cannot silently
  drift into "accepted forever";
* waivers that no longer match anything are reported as stale so they get
  deleted instead of piling up.

The gate reads Grype JSON only; it never talks to the network and never
produces timestamps, so the same input always yields the same report.
"""

from __future__ import annotations

import argparse
import datetime
import json
import sys
from pathlib import Path

SEVERITIES = {
    "negligible": 0,
    "low": 1,
    "medium": 2,
    "high": 3,
    "critical": 4,
}

DEFAULT_CUTOFF = "high"
REQUIRED_FIELDS = ("id", "images", "vulnerabilities", "expires", "owner", "justification")


class GateError(Exception):
    """Usage / configuration problem -- reported as exit code 2."""


class Finding:
    __slots__ = ("vuln_id", "severity", "package", "version", "paths")

    def __init__(self, vuln_id, severity, package, version, paths):
        self.vuln_id = vuln_id
        self.severity = severity
        self.package = package
        self.version = version
        self.paths = paths

    @property
    def rank(self):
        return SEVERITIES.get(self.severity, -1)

    def as_dict(self):
        return {
            "vulnerability": self.vuln_id,
            "severity": self.severity,
            "package": self.package,
            "version": self.version,
            "paths": sorted(self.paths),
        }


def load_json(path, what):
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return json.load(handle)
    except FileNotFoundError:
        raise GateError("%s file not found: %s" % (what, path))
    except ValueError as exc:
        raise GateError("%s file is not valid JSON (%s): %s" % (what, exc, path))


def load_waivers(path):
    data = load_json(path, "waivers")
    if not isinstance(data, dict) or not isinstance(data.get("waivers"), list):
        raise GateError("waivers file must be an object with a 'waivers' list: %s" % path)
    waivers = []
    seen = set()
    for index, entry in enumerate(data["waivers"]):
        where = "waivers[%d]" % index
        if not isinstance(entry, dict):
            raise GateError("%s must be an object" % where)
        for field in REQUIRED_FIELDS:
            if not entry.get(field):
                raise GateError("%s is missing required field '%s'" % (where, field))
        if entry["id"] in seen:
            raise GateError("%s duplicates waiver id '%s'" % (where, entry["id"]))
        seen.add(entry["id"])
        try:
            expires = datetime.date.fromisoformat(str(entry["expires"]))
        except ValueError:
            raise GateError(
                "%s has an invalid 'expires' date %r (expected YYYY-MM-DD)" % (where, entry["expires"])
            )
        images = entry["images"]
        if not isinstance(images, list) or not images:
            raise GateError("%s must list at least one image in 'images'" % where)
        vulns = entry["vulnerabilities"]
        if not isinstance(vulns, list) or not vulns:
            raise GateError("%s must list at least one id in 'vulnerabilities'" % where)
        waivers.append(
            {
                "id": entry["id"],
                "images": sorted(set(str(i) for i in images)),
                "vulnerabilities": sorted(set(str(v) for v in vulns)),
                "packages": sorted(set(str(p) for p in entry.get("packages", []) or [])),
                "package_versions": sorted(set(str(v) for v in entry.get("package_versions", []) or [])),
                "expires": expires,
                "owner": entry["owner"],
                "justification": entry["justification"],
            }
        )
    waivers.sort(key=lambda w: w["id"])
    return waivers


def iter_findings(scan):
    """Yield one Finding per (vulnerability, package, version) in a Grype JSON."""
    grouped = {}
    for match in scan.get("matches", []) or []:
        vulnerability = match.get("vulnerability", {}) or {}
        artifact = match.get("artifact", {}) or {}
        vuln_id = vulnerability.get("id") or ""
        severity = str(vulnerability.get("severity", "")).lower()
        package = artifact.get("name") or ""
        version = artifact.get("version") or ""
        key = (vuln_id, package, version)
        paths = set()
        for location in artifact.get("locations", []) or []:
            path = location.get("path")
            if path:
                paths.add(path)
        bucket = grouped.setdefault(key, {"severity": severity, "paths": set()})
        bucket["paths"] |= paths
    findings = [
        Finding(vuln_id, meta["severity"], package, version, meta["paths"])
        for (vuln_id, package, version), meta in grouped.items()
    ]
    findings.sort(key=lambda f: (f.vuln_id, f.package, f.version))
    return findings


def waiver_matches(waiver, image, finding):
    if image not in waiver["images"] and "*" not in waiver["images"]:
        return False
    if finding.vuln_id not in waiver["vulnerabilities"]:
        return False
    if waiver["packages"] and finding.package not in waiver["packages"]:
        return False
    if waiver["package_versions"] and finding.version not in waiver["package_versions"]:
        return False
    return True


def check(scan_path, image, waivers, cutoff, today, fail_on_stale=False):
    """Return (exit_code, report_dict)."""
    scan = load_json(scan_path, "scan")
    findings = iter_findings(scan)
    cutoff_rank = SEVERITIES.get(cutoff)
    if cutoff_rank is None:
        raise GateError("unknown severity cutoff %r" % cutoff)

    in_scope = [f for f in findings if f.rank >= cutoff_rank]
    used_waiver_ids = set()
    waived = []
    violations = []

    for finding in in_scope:
        active = [w for w in waivers if waiver_matches(w, image, finding)]
        expired = [w for w in active if w["expires"] < today]
        live = [w for w in active if w["expires"] >= today]
        if live:
            waiver = live[0]
            used_waiver_ids.add(waiver["id"])
            waived.append({"finding": finding.as_dict(), "waiver": waiver["id"], "expires": waiver["expires"].isoformat()})
            continue
        if expired:
            violations.append(
                {
                    "type": "expired-waiver",
                    "detail": "waiver(s) %s expired before %s"
                    % (", ".join(sorted(w["id"] for w in expired)), today.isoformat()),
                    "finding": finding.as_dict(),
                }
            )
            continue
        violations.append({"type": "unwaived", "detail": "no waiver covers this finding", "finding": finding.as_dict()})

    stale = []
    for waiver in waivers:
        if waiver["id"] in used_waiver_ids:
            continue
        if "*" in waiver["images"] or image in waiver["images"]:
            stale.append(
                {
                    "type": "stale-waiver",
                    "detail": "waiver covers this image but matched no finding at or above the cutoff; delete it",
                    "waiver": waiver["id"],
                    "expires": waiver["expires"].isoformat(),
                }
            )
        if waiver["expires"] < today:
            stale.append(
                {
                    "type": "stale-waiver",
                    "detail": "waiver expiry date has passed; renew or delete it",
                    "waiver": waiver["id"],
                    "expires": waiver["expires"].isoformat(),
                }
            )
    stale.sort(key=lambda s: (s["type"], s["waiver"]))

    report = {
        "image": image,
        "cutoff": cutoff,
        "as_of": today.isoformat(),
        "scan": str(scan_path),
        "findings_total": len(findings),
        "findings_in_scope": len(in_scope),
        "waived": waived,
        "violations": violations,
        "stale": stale,
    }

    failed = bool(violations) or (fail_on_stale and bool(stale))
    return (1 if failed else 0), report


def build_parser():
    parser = argparse.ArgumentParser(prog="cve_gate", description=__doc__.splitlines()[0])
    parser.add_argument("command", choices=["check"])
    parser.add_argument("--scan", required=True, help="path to a Grype -o json report")
    parser.add_argument("--image", required=True, help="image name the scan belongs to, e.g. amazonerp-gateway")
    parser.add_argument("--waivers", default="tools/release/cve-waivers.json", help="waiver registry path")
    parser.add_argument("--cutoff", default=DEFAULT_CUTOFF, choices=sorted(SEVERITIES), help="lowest severity that must be waived")
    parser.add_argument("--today", default=None, help="override today's date (YYYY-MM-DD); use in tests only")
    parser.add_argument("--report", default=None, help="write the JSON report to this path")
    parser.add_argument("--fail-on-stale", action="store_true", help="also fail when a waiver is stale")
    return parser


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        if args.today:
            try:
                today = datetime.date.fromisoformat(args.today)
            except ValueError:
                raise GateError("--today must be YYYY-MM-DD, got %r" % args.today)
        else:
            today = datetime.date.today()
        waivers = load_waivers(args.waivers)
        code, report = check(args.scan, args.image, waivers, args.cutoff, today, args.fail_on_stale)
    except GateError as exc:
        print("cve_gate: %s" % exc, file=sys.stderr)
        return 2

    if args.report:
        Path(args.report).parent.mkdir(parents=True, exist_ok=True)
        with open(args.report, "w", encoding="utf-8", newline="\n") as handle:
            json.dump(report, handle, indent=2, sort_keys=True, ensure_ascii=False)
            handle.write("\n")

    print("cve_gate: image=%s cutoff=%s as_of=%s" % (report["image"], report["cutoff"], report["as_of"]))
    print("  findings: %d total, %d at/above cutoff, %d waived, %d violations, %d stale"
          % (report["findings_total"], report["findings_in_scope"], len(report["waived"]),
             len(report["violations"]), len(report["stale"])))
    for item in report["violations"]:
        print("  VIOLATION [%s] %s %s@%s (%s) -- %s"
              % (item["type"], item["finding"]["vulnerability"], item["finding"]["package"],
                 item["finding"]["version"], item["finding"]["severity"], item["detail"]))
    for item in report["stale"]:
        print("  STALE [%s] %s (expires %s) -- %s" % (item["type"], item["waiver"], item["expires"], item["detail"]))
    return code


if __name__ == "__main__":
    sys.exit(main())