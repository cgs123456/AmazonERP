package com.amz.deploy;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract tests for the Phase 0 release governance gates in CI. */
class ReleaseGovernanceContractTest {

    private static final Path ROOT = findRepoRoot();
    private static final Path CI_WORKFLOW = ROOT.resolve(".github/workflows/ci.yml");
    private static final Path CHECKSTYLE_CONFIG = ROOT.resolve("checkstyle-critical.xml");

    private static final Set<String> REQUIRED_JOBS = Set.of(
            "checkstyle", "hygiene", "release-manifest", "test", "frontend", "synthetic-data",
            "mysql-import", "docker");

    @Test
    void ciRunsRepositoryHygieneAndDeterministicReleaseTools() throws IOException {
        Map<String, Object> workflow = loadYaml(CI_WORKFLOW);
        assertWorkflowTriggers(workflow);
        Map<String, Object> jobs = castMap(workflow.get("jobs"));
        assertRequiredJobs(jobs);

        List<String> hygieneRuns = runsForStep(jobs, "hygiene", "Repository hygiene");
        assertFalse(hygieneRuns.isEmpty(), "CI must run repository_hygiene.py as a required gate");
        assertTrue(hygieneRuns.contains("python tools/release/repository_hygiene.py --root ."),
                "CI must run the repository hygiene scanner with the repository root");

        List<String> releaseToolRuns = runsForStep(jobs, "hygiene", "Release tool tests");
        String expectedTests = "python -m unittest tools.release.test_repository_hygiene "
                + "tools.release.test_release_manifest tools.release.test_services_manifest -v";
        assertTrue(releaseToolRuns.contains(expectedTests),
                "CI must run all three Phase 0 release tool test suites");

        List<String> manifestRuns = runsForStep(jobs, "release-manifest", "Build deterministic manifest");
        assertFalse(manifestRuns.isEmpty(), "CI must build a deterministic release manifest");
        assertTrue(manifestRuns.stream().anyMatch(run -> run.contains("python tools/release/release_manifest.py build")),
                "CI must invoke release_manifest.py build");
        assertTrue(uploadedArtifactPaths(jobs, "release-manifest").contains("release-manifest.json"),
                "CI must upload the generated release-manifest.json artifact");
    }

    @Test
    void requiredGovernanceJobsCannotSilentlySkipFailures() throws IOException {
        Map<String, Object> jobs = castMap(loadYaml(CI_WORKFLOW).get("jobs"));
        assertRequiredJobs(jobs);

        for (String jobName : REQUIRED_JOBS) {
            Map<String, Object> job = castMap(jobs.get(jobName));
            assertFalse(isTrue(job.get("continue-on-error")), jobName + " must not set continue-on-error: true");
            List<Map<String, Object>> steps = steps(job);
            for (int index = 0; index < steps.size(); index++) {
                Map<String, Object> step = steps.get(index);
                assertFalse(isTrue(step.get("continue-on-error")),
                        jobName + " step " + index + " must not set continue-on-error: true");
            }
        }
    }

    @Test
    void testJobVerifiesThatCiLeavesTheCheckoutClean() throws IOException {
        Map<String, Object> jobs = castMap(loadYaml(CI_WORKFLOW).get("jobs"));
        assertRequiredJobs(jobs);
        List<String> cleanTreeRuns = runsForStep(jobs, "test", "Verify clean checkout");
        assertFalse(cleanTreeRuns.isEmpty(), "CI test job must verify a clean checkout after build/test");

        String run = String.join("\n", cleanTreeRuns);
        assertTrue(run.contains("git diff --exit-code"), "clean-tree check must run git diff --exit-code");
        assertTrue(run.contains("git ls-files --others --exclude-standard"),
                "clean-tree check must detect untracked source pollution");
    }

    @Test
    void dockerCanOnlyRunAfterAllRequiredGates() throws IOException {
        Map<String, Object> jobs = castMap(loadYaml(CI_WORKFLOW).get("jobs"));
        assertRequiredJobs(jobs);
        Map<String, Object> docker = castMap(jobs.get("docker"));
        Set<String> needs = stringSet(docker.get("needs"));
        for (String required : REQUIRED_JOBS) {
            if (!"docker".equals(required)) {
                assertTrue(needs.contains(required), "docker must need required gate: " + required);
            }
        }
    }

    @Test
    void criticalCheckstyleIsAHardGateWithTheRequiredRules() throws IOException {
        Map<String, Object> jobs = castMap(loadYaml(CI_WORKFLOW).get("jobs"));
        assertRequiredJobs(jobs);
        Map<String, Object> checkstyle = castMap(jobs.get("checkstyle"));
        assertFalse(isTrue(checkstyle.get("continue-on-error")), "critical checkstyle must block CI");

        List<String> runs = new ArrayList<>();
        for (Map<String, Object> step : steps(checkstyle)) {
            String run = stringOrNull(step.get("run"));
            if (run != null) {
                runs.add(run);
            }
        }
        assertTrue(runs.stream().anyMatch(run -> run.contains("mvn -B checkstyle:check")
                        && run.contains("-Dcheckstyle.config.location=checkstyle-critical.xml")),
                "CI must run mvn checkstyle:check with checkstyle-critical.xml");

        assertTrue(Files.isRegularFile(CHECKSTYLE_CONFIG), "checkstyle-critical.xml must exist");
        String config = Files.readString(CHECKSTYLE_CONFIG, StandardCharsets.UTF_8);
        for (String requiredRule : new String[] {
                "FileTabCharacter", "RegexpSingleline", "NewlineAtEndOfFile",
                "AvoidStarImport", "OneTopLevelClass", "OuterTypeFilename"}) {
            assertTrue(config.contains("<module name=\"" + requiredRule + "\""),
                    "critical checkstyle must enforce " + requiredRule);
        }
    }

    private static void assertWorkflowTriggers(Map<String, Object> workflow) {
        assertTrue(workflow.containsKey("on") || workflow.containsKey(Boolean.TRUE),
                "CI workflow must define workflow triggers");
        assertTrue(Files.exists(CI_WORKFLOW), "CI workflow must exist");
    }

    private static void assertRequiredJobs(Map<String, Object> jobs) {
        for (String required : REQUIRED_JOBS) {
            assertTrue(jobs.containsKey(required), "CI must define required job: " + required);
        }
    }

    private static List<String> runsForStep(Map<String, Object> jobs, String jobName, String stepName) {
        List<String> runs = new ArrayList<>();
        for (Map<String, Object> step : steps(castMap(jobs.get(jobName)))) {
            if (stepName.equals(stringOrNull(step.get("name")))) {
                String run = stringOrNull(step.get("run"));
                if (run != null) {
                    runs.add(run);
                }
            }
        }
        return runs;
    }

    private static List<String> uploadedArtifactPaths(Map<String, Object> jobs, String jobName) {
        List<String> paths = new ArrayList<>();
        for (Map<String, Object> step : steps(castMap(jobs.get(jobName)))) {
            if ("actions/upload-artifact@v4".equals(stringOrNull(step.get("uses")))) {
                Map<String, Object> with = castMap(step.get("with"));
                String path = stringOrNull(with.get("path"));
                if (path != null) {
                    paths.add(path);
                }
            }
        }
        return paths;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> steps(Map<String, Object> job) {
        List<Map<String, Object>> result = new ArrayList<>();
        Object rawSteps = job.get("steps");
        if (rawSteps instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    result.add((Map<String, Object>) map);
                }
            }
        }
        return result;
    }

    private static Set<String> stringSet(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet());
        }
        return Set.of(String.valueOf(value));
    }

    private static boolean isTrue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value).trim());
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Object loaded = new Yaml().load(reader);
            return loaded instanceof Map<?, ?> map
                    ? (Map<String, Object>) map
                    : new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
    }

    private static Path findRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getParent()) {
            if (Files.isRegularFile(current.resolve(".github/workflows/ci.yml"))
                    && Files.isDirectory(current.resolve("amz-service"))) {
                return current;
            }
        }
        throw new IllegalStateException("Unable to locate repository root: " + System.getProperty("user.dir"));
    }
}
