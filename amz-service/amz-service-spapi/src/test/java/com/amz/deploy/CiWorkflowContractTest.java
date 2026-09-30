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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CI 工作流契约：CI 必须执行仓库内**全部**有测试的模块，且测试步骤不可吞掉失败。
 *
 * <p>存在动因（第 71 轮实测）：ci.yml 的测试步骤用硬编码 {@code -pl} 清单列了 15 个模块，
 * 而 {@code amz-service/amz-service-message} 有 {@code src/test/java/com/amz/session/SessionTest.java}
 * （8 例）却不在清单里——该模块的测试在 CI 中**从未执行**，且没有任何机制会在新增模块时报警。
 * 本测试把「清单完整性」变成可验证不变量：任何模块只要出现 {@code src/test/java/*.java}，
 * 就必须被 CI 的测试步骤覆盖，否则红灯。
 */
class CiWorkflowContractTest {

    private static final Path ROOT = findRepoRoot();
    private static final Path CI_WORKFLOW = ROOT.resolve(".github/workflows/ci.yml");

    /** 会让测试静默变绿的命令行开关：出现即失败（不是「提醒」而是硬门禁）。 */
    private static final List<String> FORBIDDEN_SKIP_FLAGS = List.of(
            "-DskipTests", "-Dmaven.test.skip", "-Dtest.skip", "--fail-never");

    /** 会把测试范围收窄到某个子集的开关：收窄本身允许，但必须同时满足覆盖性断言。 */
    private static final List<String> NARROWING_FLAGS = List.of("-Dtest=", "-Dgroups=", "-DexcludedGroups=");

    @Test
    void everyModuleWithJavaTestsIsExecutedByCi() throws IOException {
        Set<String> expected = modulesWithJavaTests();
        assertFalse(expected.isEmpty(), "未发现任何测试模块，扫描逻辑或仓库结构已变化");

        CiTestInvocations invocations = ciTestInvocations();
        assertFalse(invocations.runs.isEmpty(),
                "CI 中不存在任何 Maven 测试调用（.github/workflows/ci.yml）");

        Set<String> covered = invocations.coversWholeReactor
                ? expected
                : invocations.moduleSelectors;
        Set<String> uncovered = new LinkedHashSet<>(expected);
        uncovered.removeAll(covered);
        uncovered.removeAll(coveredArtifactIds(invocations.moduleSelectors));

        assertTrue(uncovered.isEmpty(),
                "以下模块存在 src/test/java 测试但 CI 未执行它们（新增模块时必须同步 CI 清单，"
                        + "或改为整仓 mvn test）：" + uncovered);
    }

    @Test
    void ciTestInvocationsCannotSilentlySwallowFailures() throws IOException {
        CiTestInvocations invocations = ciTestInvocations();
        assertFalse(invocations.runs.isEmpty(), "CI 中不存在任何 Maven 测试调用");

        for (String run : invocations.runs) {
            for (String flag : FORBIDDEN_SKIP_FLAGS) {
                assertFalse(run.contains(flag),
                        "CI 测试命令不得包含跳过开关 " + flag + "：" + run);
            }
            assertFalse(run.contains("|| true") || run.contains("|| exit 0"),
                    "CI 测试命令不得用 `|| true` 吞掉失败：" + run);
        }

        assertFalse(invocations.continueOnError,
                "承载测试的 job/step 不得设置 continue-on-error: true，否则红灯不会阻断合并");
        assertTrue(invocations.narrowing.isEmpty(),
                "CI 测试不得用 " + NARROWING_FLAGS + " 收窄范围，否则未列出的用例静默不执行："
                        + invocations.narrowing);
    }

    /**
     * 启动冒烟 job 必须存在且不能被削弱。
     * <p>
     * 动因：契约测试全部只断言配置文本，logback conversionRule 指向不存在的类导致 16 个服务
     * 启动即崩时它们仍然全绿（见 evidence/2026-09-30-p2-1-traceid-real-fix.md）。
     * runtime-smoke 是唯一真把进程跑起来的闸，因此"它存在"本身也要被守住，
     * 否则一次静默删改就能让这道闸消失而不留红灯。
     */
    @Test
    void runtimeSmokeGateExistsAndCannotBeWeakened() throws IOException {
        Map<String, Object> jobs = castMap(loadYaml(CI_WORKFLOW).get("jobs"));
        Map<String, Object> smoke = castMap(jobs.get("runtime-smoke"));
        assertFalse(smoke.isEmpty(),
                "CI 缺少 runtime-smoke job：全仓只有配置文本级契约测试，"
                        + "无法发现「编译与测试全绿但服务起不来」这类故障");
        assertFalse(Boolean.TRUE.equals(smoke.get("continue-on-error")),
                "runtime-smoke 不得设置 continue-on-error，否则启动崩溃不再阻断合并");

        List<String> runs = new ArrayList<>();
        for (Object stepValue : castList(smoke.get("steps"))) {
            Map<String, Object> step = castMap(stepValue);
            if (step.get("run") != null) {
                runs.add(String.valueOf(step.get("run")));
            }
            assertFalse(Boolean.TRUE.equals(step.get("continue-on-error")),
                    "runtime-smoke 的 step 不得单独设置 continue-on-error");
        }
        assertTrue(runs.stream().anyMatch(run -> run.contains("tools/ci/runtime_smoke.py") && run.contains("--jar")),
                "runtime-smoke 必须真的调用冒烟脚本并指向一个 fat jar，"
                        + "否则它可能退化成 grep 而失去意义。实际步骤：" + runs);
        assertTrue(runs.stream().anyMatch(run -> run.contains("tools.ci.test_runtime_smoke")),
                "runtime-smoke job 必须先跑判定逻辑的单测：闸门自身要证明它会咬人");
        String allRuns = String.join("\n", runs);
        long smokeInvocations = allRuns.lines().filter(line -> line.contains("runtime_smoke.py")).count();
        assertTrue(allRuns.contains("amz-service-message-1.0-SNAPSHOT.jar")
                        && allRuns.contains("amz-gateway-1.0-SNAPSHOT.jar") && smokeInvocations >= 2,
                "启动冒烟必须至少覆盖 message 与 gateway 两条腿：2026-09-30 的 crypto.key 少配"
                        + "实际炸的是 gateway，而只起 message 的冒烟对此完全无感——"
                        + "多一条腿就多覆盖一类「公共组件被装配、部署面少配一个变量」的故障。实际步骤：" + runs);

        for (String run : runs) {
            assertFalse(run.contains("|| true") || run.contains("|| exit 0") || run.contains("-fae"),
                    "runtime-smoke 不得吞掉失败：" + run);
        }
    }

    // ------------------------------------------------------------------
    // CI 工作流解析
    // ------------------------------------------------------------------

    private record CiTestInvocations(List<String> runs,
                                     Set<String> moduleSelectors,
                                     boolean coversWholeReactor,
                                     List<String> narrowing,
                                     boolean continueOnError) {
    }

    @SuppressWarnings("unchecked")
    private static CiTestInvocations ciTestInvocations() throws IOException {
        Map<String, Object> root = loadYaml(CI_WORKFLOW);
        Map<String, Object> jobs = castMap(root.get("jobs"));
        List<String> runs = new ArrayList<>();
        Set<String> selectors = new TreeSet<>();
        List<String> narrowing = new ArrayList<>();
        boolean wholeReactor = false;
        boolean continueOnError = false;

        for (Object jobValue : jobs.values()) {
            Map<String, Object> job = castMap(jobValue);
            List<String> jobTestRuns = new ArrayList<>();
            List<Boolean> jobStepContinueOnError = new ArrayList<>();
            for (Object stepValue : castList(job.get("steps"))) {
                Map<String, Object> step = castMap(stepValue);
                String run = step.get("run") == null ? "" : String.valueOf(step.get("run"));
                if (!isMavenTestRun(run)) {
                    continue;
                }
                jobTestRuns.add(run);
                jobStepContinueOnError.add(Boolean.TRUE.equals(step.get("continue-on-error")));
            }
            // 只有真正承载测试的 job/step 才受「不得吞掉失败」约束；
            // 与测试无关的建议性 job（如 checkstyle）允许 continue-on-error。
            if (jobTestRuns.isEmpty()) {
                continue;
            }
            if (Boolean.TRUE.equals(job.get("continue-on-error"))) {
                continueOnError = true;
            }
            for (int i = 0; i < jobTestRuns.size(); i++) {
                String run = jobTestRuns.get(i);
                runs.add(run);
                if (Boolean.TRUE.equals(jobStepContinueOnError.get(i))) {
                    continueOnError = true;
                }
                for (String flag : NARROWING_FLAGS) {
                    if (run.contains(flag)) {
                        narrowing.add(flag + " @ " + run);
                    }
                }
                Matcher pl = Pattern.compile("-pl\\s+([^\\s]+)").matcher(run);
                if (pl.find()) {
                    for (String selector : pl.group(1).split(",")) {
                        String trimmed = selector.trim();
                        if (!trimmed.isEmpty()) {
                            selectors.add(trimmed);
                        }
                    }
                } else {
                    wholeReactor = true;
                }
            }
        }
        return new CiTestInvocations(runs, selectors, wholeReactor, narrowing, continueOnError);
    }

    /** 只认「跑测试」的 Maven 命令，排除 compile / checkstyle / package -DskipTests 之类的步骤。 */
    private static boolean isMavenTestRun(String run) {
        if (run == null || run.isBlank()) {
            return false;
        }
        boolean maven = run.contains("mvn ") || run.startsWith("mvn");
        if (!maven) {
            return false;
        }
        return Pattern.compile("(^|\\s)(test|verify)(\\s|$)").matcher(run).find();
    }

    private static Set<String> coveredArtifactIds(Set<String> selectors) {
        Set<String> ids = new LinkedHashSet<>();
        for (String selector : selectors) {
            if (selector.startsWith(":")) {
                ids.add(selector.substring(1));
            }
        }
        return ids;
    }

    // ------------------------------------------------------------------
    // 仓库扫描
    // ------------------------------------------------------------------

    private static Set<String> modulesWithJavaTests() throws IOException {
        Set<String> modules = new TreeSet<>();
        Path serviceDir = ROOT.resolve("amz-service");
        if (Files.isDirectory(serviceDir)) {
            try (Stream<Path> dirs = Files.list(serviceDir)) {
                for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                    if (hasJavaTests(dir)) {
                        modules.add("amz-service/" + dir.getFileName());
                    }
                }
            }
        }
        for (String top : List.of("amz-common", "amz-gateway")) {
            Path dir = ROOT.resolve(top);
            if (hasJavaTests(dir)) {
                modules.add(top);
            }
        }
        return modules;
    }

    private static boolean hasJavaTests(Path moduleDir) throws IOException {
        Path testJava = moduleDir.resolve("src/test/java");
        if (!Files.isDirectory(testJava)) {
            return false;
        }
        try (Stream<Path> files = Files.walk(testJava)) {
            return files.anyMatch(path -> path.toString().endsWith(".java"));
        }
    }

    // ------------------------------------------------------------------
    // 通用工具
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Object loaded = new Yaml().load(reader);
            return loaded instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    private static Path findRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getParent()) {
            if (Files.isRegularFile(current.resolve(".github/workflows/ci.yml"))
                    && Files.isDirectory(current.resolve("amz-service"))) {
                return current;
            }
        }
        throw new IllegalStateException("无法从 user.dir 定位仓库根目录：" + System.getProperty("user.dir"));
    }
}
