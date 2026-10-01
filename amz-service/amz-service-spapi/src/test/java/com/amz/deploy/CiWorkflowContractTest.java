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

    /**
     * env 门控的集成测试必须真的接进 CI，接进去的变量必须真的有人读。
     * <p>
     * 存在动因：这类 IT 用 {@code @EnabledIfEnvironmentVariable} 守"没有 MySQL 的开发机整类跳过"，
     * 代价是<b>只要 CI 少给一个变量，它就永久静默跳过而全仓仍然绿</b>——
     * 那比没有这道闸更危险，因为它对外表现为"集成测试跑过了"。
     * 本用例两头都查：变量集合从测试源码里<b>扫出来</b>（不是手抄清单，否则新增 IT 不会被发现），
     * 每个都要在 CI 某一步的 env 里出现，且那个 job 真的挂了 mysql service；
     * 反向也查：CI 里给了却没有测试读取的 {@code *_IT_URL} 同样算红。
     * <p>
     * 允许"故意不接"，但必须写明理由，且理由指向的变量确实存在——
     * 否则允许集会变成存放绿灯的地方。
     */
    @Test
    void envGatedIntegrationTestsAreWiredIntoCiOrDeliberatelyExcluded() throws IOException {
        Map<String, String> ciEnvKeys = ciStepEnvKeysByJob();
        Map<String, Boolean> jobHasMysql = jobHasMysqlService();

        Set<String> discovered = new LinkedHashSet<>();
        for (Path file : javaTestFiles()) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("EnabledIfEnvironmentVariable\\(\\s*named\\s*=\\s*\"([A-Z0-9_]+)\"")
                    .matcher(source);
            while (m.find()) {
                discovered.add(m.group(1));
            }
        }
        // 空扫描不等于没有门控测试：一旦扫描逻辑或注解写法变了，必须炸而不是全绿
        assertTrue(discovered.size() >= 6,
                "只扫到 " + discovered.size() + " 个 env 门控变量，怀疑扫描面变了：" + discovered);

        List<String> unwired = new ArrayList<>();
        for (String variable : discovered) {
            if (DELIBERATELY_NOT_IN_CI.containsKey(variable)) {
                continue;
            }
            String job = ciEnvKeys.get(variable);
            if (job == null) {
                unwired.add(variable + "（无任何 CI step env 提供）");
            } else if (variable.endsWith("_URL") && !Boolean.TRUE.equals(jobHasMysql.get(job))) {
                unwired.add(variable + "（由 job " + job + " 提供，但该 job 没有 mysql service）");
            }
        }
        assertTrue(unwired.isEmpty(),
                "以下 env 门控集成测试没有真正接进 CI，它们在 CI 里会整类静默跳过：" + unwired);

        for (Map.Entry<String, String> exclusion : DELIBERATELY_NOT_IN_CI.entrySet()) {
            assertTrue(discovered.contains(exclusion.getKey()),
                    "允许清单里的 " + exclusion.getKey() + " 在测试源码里已不存在，条目该删掉："
                            + exclusion.getValue());
            assertFalse(ciEnvKeys.containsKey(exclusion.getKey()),
                    "允许清单说 " + exclusion.getKey() + " 故意不接 CI，但 CI 其实给了它："
                            + "请把条目删掉，让扫描去守它");
            assertFalse(exclusion.getValue().isBlank(), "故意不接 CI 必须写理由");
        }

        List<String> orphanCiVars = new ArrayList<>();
        for (String key : ciEnvKeys.keySet()) {
            if (key.endsWith("_IT_URL") && !discovered.contains(key)) {
                orphanCiVars.add(key + "（job：" + ciEnvKeys.get(key) + "）");
            }
        }
        assertTrue(orphanCiVars.isEmpty(),
                "CI 提供了 *_IT_URL 却没有任何测试源码用它作启用开关——这是假的「集成覆盖」信号："
                        + orphanCiVars);
    }

    /** 故意不接 CI 的 env 门控测试及其理由（理由非空 + 变量仍存在，见上面的反向检查）。 */
    private static final Map<String, String> DELIBERATELY_NOT_IN_CI = Map.of(
            "RUN_INTEGRATION_TESTS",
            "SpApiIntegrationTest 要真实的 SP-API 凭证与外部网络，CI 里跑等于把凭据下放进公共 runner；"
                    + "它由本地按文档手动执行，不作为常驻闸口");

    // ------------------------------------------------------------------
    // CI 工作流解析
    // ------------------------------------------------------------------

    private static Map<String, String> ciStepEnvKeysByJob() throws IOException {
        Map<String, Object> jobs = castMap(loadYaml(CI_WORKFLOW).get("jobs"));
        Map<String, String> keyToJob = new LinkedHashMap<>();
        for (Map.Entry<String, Object> jobEntry : jobs.entrySet()) {
            Map<String, Object> job = castMap(jobEntry.getValue());
            for (Object stepValue : castList(job.get("steps"))) {
                Map<String, Object> env = castMap(castMap(stepValue).get("env"));
                for (String key : env.keySet()) {
                    keyToJob.putIfAbsent(key, jobEntry.getKey());
                }
            }
        }
        return keyToJob;
    }

    private static Map<String, Boolean> jobHasMysqlService() throws IOException {
        Map<String, Object> jobs = castMap(loadYaml(CI_WORKFLOW).get("jobs"));
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> jobEntry : jobs.entrySet()) {
            Map<String, Object> services = castMap(castMap(jobEntry.getValue()).get("services"));
            out.put(jobEntry.getKey(), services.containsKey("mysql"));
        }
        return out;
    }

    private static List<Path> javaTestFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        Path serviceDir = ROOT.resolve("amz-service");
        List<Path> roots = new ArrayList<>(List.of(ROOT.resolve("amz-common"), ROOT.resolve("amz-gateway")));
        if (Files.isDirectory(serviceDir)) {
            try (Stream<Path> dirs = Files.list(serviceDir)) {
                dirs.filter(Files::isDirectory).forEach(roots::add);
            }
        }
        for (Path moduleRoot : roots) {
            Path testJava = moduleRoot.resolve("src/test/java");
            if (!Files.isDirectory(testJava)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(testJava)) {
                walk.filter(path -> path.toString().endsWith(".java")).forEach(files::add);
            }
        }
        assertFalse(files.isEmpty(), "没有扫到任何测试源文件，扫描面已变");
        return files;
    }

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
