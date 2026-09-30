package com.amz.deploy;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SkyWalking 链路追踪接线契约：agent 必须挂在 JAVA_OPTS 覆盖不到的地方，且 16 个服务各有独立身份。
 * <p>
 * 背景（本轮实测）：
 * <ul>
 *   <li>{@code Dockerfile} 曾把 {@code -javaagent} 只写在 {@code ENV JAVA_OPTS} 默认值里，
 *       而 ENTRYPOINT 是 {@code java $JAVA_OPTS -jar}；docker-compose 传
 *       {@code JAVA_OPTS=${JAVA_OPTS:-}}、k8s ConfigMap 也自带 {@code JAVA_OPTS}，
 *       两条部署路径的整体覆盖会把 agent 一起摘掉，日志 traceId 因此恒为降级值；</li>
 *   <li>全仓没有任何 {@code SW_AGENT_NAME}，镜像默认值 {@code amz-service} 会让 16 个服务
 *       在 SkyWalking 里塌缩成同一个服务名，链路拓扑无从谈起。</li>
 * </ul>
 * 这两条都属于"配置看起来已接线、运行时静默失效"，只查 logback XML 文本的契约测试抓不到。
 */
class SkyWalkingIdentityContractTest {

    private static final Path ROOT = findRepoRoot();
    private static final String AGENT_JAR = "/skywalking-agent/skywalking-agent.jar";
    private static final Set<String> EXPECTED_SERVICES = expectedServices();

    @Test
    void agentAttachIsIndependentOfJavaOpts() throws IOException {
        List<String> lines = Files.readAllLines(ROOT.resolve("Dockerfile"), StandardCharsets.UTF_8);

        String agentOpts = singlePrefixed(lines, "ENV SW_AGENT_OPTS=");
        assertTrue(agentOpts.contains("-javaagent:" + AGENT_JAR),
                "SW_AGENT_OPTS 必须携带 -javaagent：" + agentOpts);

        String javaOpts = singlePrefixed(lines, "ENV JAVA_OPTS=");
        assertFalse(javaOpts.contains("-javaagent"),
                "JAVA_OPTS 默认值不能再含 -javaagent，否则与 SW_AGENT_OPTS 叠加会把同一个 agent 挂载两次："
                        + javaOpts);

        String entrypoint = singlePrefixed(lines, "ENTRYPOINT");
        assertTrue(entrypoint.contains("${SW_AGENT_OPTS}"),
                "ENTRYPOINT 必须拼接 ${SW_AGENT_OPTS}，否则 agent 挂载仍依赖 JAVA_OPTS：" + entrypoint);
        assertTrue(entrypoint.contains("exec java"),
                "ENTRYPOINT 必须用 exec 让 JVM 成为 PID 1，否则 SIGTERM 打不到进程、滚动更新要等 grace period："
                        + entrypoint);
        assertTrue(entrypoint.contains("-javaagent"),
                "ENTRYPOINT 必须先从 JAVA_OPTS 里剔除历史 -javaagent（去重），兜住运维自建的旧 .env");
    }

    @Test
    void agentUsesRealConfigVariableNames() throws IOException {
        String dockerfile = Files.readString(ROOT.resolve("Dockerfile"), StandardCharsets.UTF_8);
        List<String> lines = Files.readAllLines(ROOT.resolve("Dockerfile"), StandardCharsets.UTF_8);

        assertFalse(lines.stream().anyMatch(line -> line.startsWith("ENV SW_COLLECTOR=")),
                "SW_COLLECTOR 从未被镜像内 agent.config 引用，是死变量；"
                        + "真实变量名是 SW_AGENT_COLLECTOR_BACKEND_SERVICES");
        assertTrue(dockerfile.contains("ENV SW_AGENT_COLLECTOR_BACKEND_SERVICES="),
                "必须用 agent 真实读取的 collector 变量名");
        assertTrue(dockerfile.contains("ENV SW_AGENT_NAMESPACE="),
                "缺少命名空间声明，多套环境共用一个 OAP 时服务会混在一张拓扑图里");
        assertTrue(dockerfile.contains("ENV SW_LOGGING_DIR="),
                "agent 默认写 /skywalking-agent/logs（root 属主），非 root 运行用户写不进去会产生 stderr 噪声");

        assertTrue(dockerfile.contains("rm -rf /skywalking-agent/optional-reporter-plugins"),
                "可选 reporter 插件带着无修复版本的 CVE 依赖，不得拷回镜像");
    }

    @Test
    void imageMustNotCarryASharedAgentServiceName() throws IOException {
        List<String> lines = Files.readAllLines(ROOT.resolve("Dockerfile"), StandardCharsets.UTF_8);
        assertFalse(lines.stream().anyMatch(line -> line.startsWith("ENV SW_AGENT_NAME=")),
                "镜像不得给 SW_AGENT_NAME 默认值：默认值会让忘配身份的服务静默共用一个名字，"
                        + "而不是暴露成明显错误的名字");

        for (String value : allDeclaredAgentNames()) {
            assertFalse("amz-service".equals(value) || value.isBlank(),
                    "部署清单里不得把服务名设成共享默认值 amz-service：" + value);
        }
    }

    @Test
    void composeGivesEveryApplicationServiceItsOwnAgentName() throws IOException {
        Map<String, Object> compose = loadYaml(ROOT.resolve("docker-compose.yml"));
        Map<String, ?> services = castMap(compose.get("services"));
        Map<String, String> found = new TreeMap<>();
        for (Map.Entry<String, ?> entry : services.entrySet()) {
            Map<String, String> env = environmentValues(entry.getValue());
            if (!env.containsKey("SPRING_PROFILES_ACTIVE")) {
                continue;
            }
            String name = env.get("SW_AGENT_NAME");
            assertNotNull(name, entry.getKey() + " 是应用服务但没有 SW_AGENT_NAME，"
                    + "在 SkyWalking 里会与其它服务共用身份，链路拓扑无法区分");
            assertEquals(entry.getKey(), name,
                    entry.getKey() + " 的 SW_AGENT_NAME 必须等于 compose 服务名，便于与指标/日志的 service 字段对齐");
            found.put(entry.getKey(), name);
        }
        assertEquals(EXPECTED_SERVICES, found.keySet(),
                "compose 中带 SW_AGENT_NAME 的应用服务集合必须是 gateway + 15 个业务服务");
    }

    @Test
    void k8sManifestsGiveEveryDeploymentItsOwnAgentName() throws IOException {
        List<Path> manifests = new ArrayList<>();
        try (var stream = Files.list(ROOT.resolve("k8s").resolve("services"))) {
            stream.filter(path -> path.getFileName().toString().endsWith(".yaml"))
                    .sorted()
                    .forEach(manifests::add);
        }
        assertEquals(16, manifests.size(), "k8s/services 必须是 gateway + 15 个业务服务清单");

        Set<String> names = new HashSet<>();
        for (Path manifest : manifests) {
            for (Object document : new Yaml().loadAll(read(manifest))) {
                Map<String, Object> doc = castMap(document);
                if (!"Deployment".equals(doc.get("kind"))) {
                    continue;
                }
                String deploymentName = String.valueOf(castMap(doc.get("metadata")).get("name"));
                String agentName = mainContainerAgentName(doc);
                assertNotNull(agentName, manifest.getFileName()
                        + " 的 Deployment 没有 SW_AGENT_NAME");
                assertEquals(deploymentName, agentName,
                        manifest.getFileName() + " 的 SW_AGENT_NAME 必须等于 Deployment 名");
                names.add(agentName);
            }
        }
        assertEquals(16, names.size(), "16 个 Deployment 的服务名必须互不相同，实际只得到 " + names.size() + " 个：" + names);
        assertEquals(EXPECTED_SERVICES, names, "k8s 侧 SkyWalking 服务名集合与部署清单不一致");
    }

    /**
     * 跨文件一致性：应用侧写死的 collector 地址，必须能在 observability 清单里找到同名的
     * Service 与 gRPC 端口。否则 agent 连不上 collector，日志 traceId 静默退化为
     * Ignored_Trace，且服务本身毫无报错——正是本轮反复遇到的"接线看着完好、运行时失效"那一类。
     */
    @Test
    void k8sApplicationsPointAtTheDeclaredOapService() throws IOException {
        Path infra = ROOT.resolve("k8s").resolve("infra").resolve("skywalking.yaml");
        assertTrue(Files.isRegularFile(infra),
                "缺少 " + infra + "：k8s 侧没有采集端，16 个服务的 agent 上报无处可去");

        Map<String, Object> oapService = null;
        for (Object document : new Yaml().loadAll(read(infra))) {
            Map<String, Object> doc = castMap(document);
            if ("Service".equals(doc.get("kind"))
                    && "skywalking-oap".equals(String.valueOf(castMap(doc.get("metadata")).get("name")))) {
                oapService = doc;
            }
        }
        assertNotNull(oapService,
                "skywalking.yaml 未声明名为 skywalking-oap 的 Service，应用侧 collector 地址是悬空主机名");

        String namespace = String.valueOf(castMap(oapService.get("metadata")).get("namespace"));
        Object ports = castMap(oapService.get("spec")).get("ports");
        assertTrue(ports instanceof List<?>, "skywalking-oap Service 的 ports 必须是列表");
        String grpcPort = null;
        for (Object item : (List<?>) ports) {
            Map<String, Object> port = castMap(item);
            if ("grpc".equals(String.valueOf(port.get("name")))) {
                grpcPort = String.valueOf(port.get("port"));
            }
        }
        assertNotNull(grpcPort, "skywalking-oap Service 缺少名为 grpc 的端口（agent 上报口）");
        String expected = "skywalking-oap." + namespace + ".svc.cluster.local:" + grpcPort;

        List<Map<String, Object>> deployments = k8sDeployments();
        assertEquals(16, deployments.size(), "被检查的应用 Deployment 必须是 gateway + 15 个业务服务");
        for (Map<String, Object> deployment : deployments) {
            String name = String.valueOf(castMap(deployment.get("metadata")).get("name"));
            assertEquals(expected, mainContainerEnv(deployment).get("SW_AGENT_COLLECTOR_BACKEND_SERVICES"),
                    name + " 的 SW_AGENT_COLLECTOR_BACKEND_SERVICES 与 OAP Service 声明不一致");
        }
    }

    /** compose 与 k8s 两侧声明的全部 SW_AGENT_NAME 值。 */
    private static Set<String> allDeclaredAgentNames() throws IOException {
        Set<String> values = new HashSet<>();
        Map<String, Object> compose = loadYaml(ROOT.resolve("docker-compose.yml"));
        for (Object service : castMap(compose.get("services")).values()) {
            String name = environmentValues(service).get("SW_AGENT_NAME");
            if (name != null) {
                values.add(name);
            }
        }
        try (var stream = Files.list(ROOT.resolve("k8s").resolve("services"))) {
            List<Path> manifests = stream.sorted().toList();
            for (Path manifest : manifests) {
                for (Object document : new Yaml().loadAll(read(manifest))) {
                    Map<String, Object> doc = castMap(document);
                    if ("Deployment".equals(doc.get("kind"))) {
                        String name = mainContainerAgentName(doc);
                        if (name != null) {
                            values.add(name);
                        }
                    }
                }
            }
        }
        return values;
    }

    private static String mainContainerAgentName(Map<String, Object> deployment) {
        return mainContainerEnv(deployment).get("SW_AGENT_NAME");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> mainContainerEnv(Map<String, Object> deployment) {
        Map<String, Object> template = castMap(castMap(deployment.get("spec")).get("template"));
        Map<String, Object> podSpec = castMap(template.get("spec"));
        Object containers = podSpec.get("containers");
        if (!(containers instanceof List<?> list)) {
            throw new AssertionError("Deployment 缺少 containers 列表：" + deployment.get("metadata"));
        }
        for (Object container : list) {
            Map<String, String> env = environmentValues(container);
            if (env.containsKey("SPRING_PROFILES_ACTIVE")) {
                return env;
            }
        }
        throw new AssertionError("没有主容器声明 SPRING_PROFILES_ACTIVE，无法定位应用容器："
                + castMap(deployment.get("metadata")).get("name"));
    }

    /** k8s/services 下的 16 个 Deployment 文档。 */
    private static List<Map<String, Object>> k8sDeployments() throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        try (var stream = Files.list(ROOT.resolve("k8s").resolve("services"))) {
            for (Path manifest : stream.sorted().toList()) {
                for (Object document : new Yaml().loadAll(read(manifest))) {
                    Map<String, Object> doc = castMap(document);
                    if ("Deployment".equals(doc.get("kind"))) {
                        result.add(doc);
                    }
                }
            }
        }
        return result;
    }

    /**
     * 支持三种 environment 写法：compose 的 {@code - K=V} 字符串列表、
     * k8s flow-style 的 {@code - { name: K, value: V }}，以及 k8s block-style 的
     * {@code - name: K} + {@code value: V}（snakeyaml 会解析成 Map 而不是字符串）。
     * value 不是标量（valueFrom 形态）时跳过，避免把整张 Map 当值读进来。
     */
    private static Map<String, String> environmentValues(Object owner) {
        Map<String, Object> body = castMap(owner);
        Map<String, String> result = new LinkedHashMap<>();
        // compose 用 environment，k8s container 用 env
        Object environment = body.containsKey("env") ? body.get("env") : body.get("environment");
        if (environment instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object key = map.get("name");
                    if (key != null) {
                        // valueFrom 形态的条目没有标量 value，仍要登记名字，
                        // 否则"哪个容器是主容器"的判定会读不到 SPRING_PROFILES_ACTIVE
                        Object value = map.get("value");
                        result.put(String.valueOf(key), value == null ? "" : String.valueOf(value));
                    }
                    continue;
                }
                String entry = String.valueOf(item);
                int split = entry.indexOf('=');
                if (split > 0) {
                    result.put(entry.substring(0, split), entry.substring(split + 1));
                }
            }
        } else if (environment instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        return result;
    }

    private static String singlePrefixed(List<String> lines, String prefix) {
        List<String> hits = lines.stream().filter(line -> line.startsWith(prefix)).toList();
        assertEquals(1, hits.size(), "Dockerfile 中应以 " + prefix + " 开头恰好一行，实际 " + hits.size() + " 行");
        return hits.get(0);
    }

    private static Set<String> expectedServices() {
        Set<String> names = new HashSet<>();
        names.add("amz-gateway");
        for (String service : List.of("ad", "ai", "customer", "finance", "logistics", "message",
                "multiplatform", "ops", "order", "procurement", "product", "report", "search", "spapi", "user")) {
            names.add("amz-service-" + service);
        }
        return names;
    }

    private static Map<String, Object> loadYaml(Path path) throws IOException {
        Object document = new Yaml().load(read(path));
        return document instanceof Map<?, ?> map ? castMap(map) : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static Path findRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getParent()) {
            if (Files.isRegularFile(current.resolve("docker-compose.yml"))
                    && Files.isDirectory(current.resolve("prometheus"))) {
                return current;
            }
        }
        throw new IllegalStateException("无法从 user.dir 定位仓库根目录：" + System.getProperty("user.dir"));
    }
}
