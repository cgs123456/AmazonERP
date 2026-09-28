#!/usr/bin/env python3
"""Contract tests for the reproducible multi-service OCI build manifest."""

from __future__ import annotations

import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SERVICES_PATH = ROOT / "tools" / "release" / "services.json"
DOCKERFILE_PATH = ROOT / "Dockerfile"
BAKE_PATH = ROOT / "docker-bake.hcl"
FRONTEND_DOCKERFILE = ROOT / "amz-frontend" / "Dockerfile"
SERVICE_FIELDS = {"name", "module", "port", "healthPath", "imageSuffix"}
EXPECTED_SERVICE_COUNT = 16
EXPECTED_TARGET_COUNT = 17


def extract_blocks(text: str, block_type: str) -> dict[str, str]:
    pattern = re.compile(
        rf'(?ms)^{re.escape(block_type)}\s+"([^"]+)"\s*\{{(?P<body>.*?)^\}}'
    )
    return {match.group(1): match.group("body") for match in pattern.finditer(text)}


def extract_string(block: str, field: str) -> str | None:
    match = re.search(rf'(?m)^\s*{re.escape(field)}\s*=\s*"([^"]*)"', block)
    return match.group(1) if match else None


def extract_arg(block: str, name: str) -> str | None:
    match = re.search(rf'(?m)^\s*{re.escape(name)}\s*=\s*"([^"]*)"', block)
    return match.group(1) if match else None


def parse_server_port(path: Path) -> int:
    lines = path.read_text(encoding="utf-8").splitlines()
    inside_server = False
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        indent = len(line) - len(line.lstrip())
        if indent == 0:
            inside_server = stripped == "server:"
            continue
        if inside_server and re.fullmatch(r"port:\s*([0-9]+)", stripped):
            return int(re.fullmatch(r"port:\s*([0-9]+)", stripped).group(1))
    raise AssertionError(f"server.port not found in {path}")


class ServicesManifestTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.document = json.loads(SERVICES_PATH.read_text(encoding="utf-8"))
        cls.services = cls.document["services"]
        cls.by_name = {service["name"]: service for service in cls.services}
        cls.bake_text = BAKE_PATH.read_text(encoding="utf-8")
        cls.targets = extract_blocks(cls.bake_text, "target")
        cls.groups = extract_blocks(cls.bake_text, "group")
        cls.variables = extract_blocks(cls.bake_text, "variable")

    def test_schema_and_stable_service_inventory(self) -> None:
        self.assertEqual({"schemaVersion", "services"}, set(self.document))
        self.assertEqual(1, self.document["schemaVersion"])
        self.assertEqual(EXPECTED_SERVICE_COUNT, len(self.services))
        self.assertEqual(
            sorted(self.services, key=lambda service: service["name"]),
            self.services,
        )
        for service in self.services:
            self.assertEqual(SERVICE_FIELDS, set(service))
            self.assertRegex(service["name"], r"^[a-z][a-z0-9-]*$")
            self.assertRegex(service["imageSuffix"], r"^[a-z][a-z0-9-]*$")
            self.assertTrue(service["healthPath"].startswith("/"))

    def test_modules_exist_and_cover_java_build_targets(self) -> None:
        expected_modules = {"amz-gateway"}
        expected_modules.update(
            path.relative_to(ROOT).as_posix()
            for path in (ROOT / "amz-service").iterdir()
            if path.is_dir() and (path / "pom.xml").is_file()
        )
        actual_modules = {service["module"] for service in self.services}

        self.assertEqual(expected_modules, actual_modules)
        self.assertTrue((ROOT / "amz-frontend").is_dir())
        for module in actual_modules:
            self.assertTrue((ROOT / module / "pom.xml").is_file(), module)

    def test_ports_names_and_suffixes_are_valid_and_unique(self) -> None:
        names = [service["name"] for service in self.services]
        suffixes = [service["imageSuffix"] for service in self.services]
        ports = [service["port"] for service in self.services]

        self.assertEqual(len(names), len(set(names)))
        self.assertEqual(len(suffixes), len(set(suffixes)))
        self.assertEqual(len(ports), len(set(ports)))
        for port in ports:
            self.assertGreaterEqual(port, 1024)
            self.assertLessEqual(port, 65535)

    def test_default_application_ports_match_metadata(self) -> None:
        for service in self.services:
            with self.subTest(service=service["name"]):
                application = ROOT / service["module"] / "src" / "main" / "resources" / "application.yml"
                self.assertTrue(application.is_file(), application)
                self.assertEqual(service["port"], parse_server_port(application))

    def test_root_dockerfile_emits_required_oci_labels(self) -> None:
        dockerfile = DOCKERFILE_PATH.read_text(encoding="utf-8")
        for argument in ("VERSION", "VCS_REF", "BUILD_DATE"):
            self.assertRegex(dockerfile, rf"(?m)^ARG {argument}=")
        for label in (
            "org.opencontainers.image.revision",
            "org.opencontainers.image.version",
            "org.opencontainers.image.created",
            "org.opencontainers.image.source",
        ):
            self.assertIn(label, dockerfile)

    def test_bake_targets_match_services_and_frontend(self) -> None:
        service_targets = {service["name"] for service in self.services}
        all_build_targets = service_targets | {"frontend"}

        self.assertEqual(EXPECTED_TARGET_COUNT, len(all_build_targets))
        self.assertEqual(all_build_targets, set(self.targets) - {"common"})
        self.assertEqual(
            {"REGISTRY", "TAG", "GIT_SHA", "BUILD_DATE"},
            set(self.variables),
        )

    def test_bake_java_targets_forward_modules_ports_and_oci_metadata(self) -> None:
        common = self.targets["common"]
        self.assertEqual(".", extract_string(common, "context"))
        self.assertEqual("Dockerfile", extract_string(common, "dockerfile"))
        self.assertIn('"linux/amd64"', common)
        # arm64 is deliberately deferred until the jar build moves out of the
        # image: 17 in-container Maven builds under QEMU exceed practical
        # runner time limits. Re-enabling it must be a conscious change.
        self.assertNotIn('"linux/arm64"', common)

        for service in self.services:
            with self.subTest(service=service["name"]):
                block = self.targets[service["name"]]
                self.assertRegex(block, r'(?m)^\s*inherits\s*=\s*\["common"\]')
                self.assertEqual(service["module"], extract_arg(block, "MODULE"))
                self.assertEqual(str(service["port"]), extract_arg(block, "PORT"))
                self.assertEqual("${TAG}", extract_arg(block, "VERSION"))
                self.assertEqual("${GIT_SHA}", extract_arg(block, "VCS_REF"))
                self.assertEqual("${BUILD_DATE}", extract_arg(block, "BUILD_DATE"))

    def test_frontend_target_uses_its_own_dockerfile(self) -> None:
        frontend = self.targets["frontend"]

        self.assertTrue(FRONTEND_DOCKERFILE.is_file())
        self.assertEqual("amz-frontend/Dockerfile", extract_string(frontend, "dockerfile"))
        self.assertEqual("${TAG}", extract_arg(frontend, "VERSION"))
        self.assertEqual("${GIT_SHA}", extract_arg(frontend, "VCS_REF"))
        self.assertEqual("${BUILD_DATE}", extract_arg(frontend, "BUILD_DATE"))
        self.assertTrue((ROOT / "amz-frontend" / "package-lock.json").is_file())

    def test_default_group_covers_every_build_target(self) -> None:
        expected = {service["name"] for service in self.services} | {"frontend"}
        default_block = self.groups.get("default", "")
        actual = set(re.findall(r'"([a-z][a-z0-9-]*)"', default_block))

        self.assertEqual(expected, actual)

if __name__ == "__main__":
    unittest.main()