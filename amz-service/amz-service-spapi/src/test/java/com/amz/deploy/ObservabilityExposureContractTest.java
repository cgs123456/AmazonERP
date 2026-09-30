package com.amz.deploy;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可观测性暴露面契约：Prometheus 抓取目标必须真实可用，健康探针必须显式开启。
 * <p>
 * 背景（Boot 3.5.16 官方配置元数据实测）：
 * <ul>
 *   <li>{@code management.endpoints.web.exposure.include} 默认 {@code ["health"]}，
 *       未显式配置时 {@code /actuator/prometheus} 返回 404；</li>
 *   <li>{@code management.endpoint.health.probes.enabled} 默认 {@code false}，
 *       仅当检测到 KUBERNETES / CLOUD_FOUNDRY 平台时才自动开启，
 *       因此 docker-compose 与本地验收下 {@code /actuator/health/readiness} 会 404。</li>
 * </ul>
 * 这两条默认值的组合，会让 k8s 探针与 Prometheus 抓取在"看起来已接线"的配置下静默失效。
 */
class ObservabilityExposureContractTest {

    private static final Path ROOT = findRepoRoot();
    private static final Set<String> SERVICES = Set.of(
            "ad", "ai", "customer", "finance", "logistics", "message",
            "multiplatform", "ops", "order", "procurement", "product",
            "report", "search", "spapi", "user");
    private static final Pattern SCRAPE_TARGET = Pattern.compile("['\"](amz-[a-z0-9-]+):(\\d+)['\"]");

    @Test
    void everyScrapeTargetResolvesToItsOwnServerPort() throws IOException {
        Map<String, String> targets = scrapeTargetsByModule();
        Map<String, Path> modules = moduleApplicationFiles();
        assertEquals(16, modules.size(), "模块清单应覆盖 gateway + 15 个业务服务");
        assertEquals(16, targets.size(), "prometheus.yml 应抓取 gateway + 15 个业务服务");
        assertEquals(modules.keySet(), targets.keySet(),
                "Prometheus 抓取目标必须与可部署模块一一对应，不能漏抓也不能抓不存在的服务");

        for (Map.Entry<String, String> target : targets.entrySet()) {
            String module = target.getKey();
            String serverPort = serverPort(modules.get(module));
            assertEquals(serverPort, target.getValue(),
                    "prometheus.yml 中 " + module + " 的抓取端口与该服务 server.port 不一致："
                            + "抓取会直接连接失败（不是 404，是连不上）");
        }
    }

    @Test
    void everyScrapedModuleExposesPrometheusEndpoint() throws IOException {
        Map<String, Path> modules = moduleApplicationFiles();
        for (Map.Entry<String, Path> entry : modules.entrySet()) {
            Set<String> include = exposureInclude(management(entry.getValue()));
            assertTrue(include.contains("prometheus"),
                    entry.getKey() + " 未暴露 prometheus 端点：Boot 默认 web exposure 只有 health，"
                            + "/actuator/prometheus 会 404，Prometheus 抓不到任何指标");
        }
    }

    @Test
    void everyModuleEnablesLivenessAndReadinessProbes() throws IOException {
        Map<String, Path> modules = moduleApplicationFiles();
        for (Map.Entry<String, Path> entry : modules.entrySet()) {
            Map<String, Object> management = management(entry.getValue());
            Object enabled = value(management, "endpoint", "health", "probes", "enabled");
            assertEquals("true", String.valueOf(enabled),
                    entry.getKey() + " 未显式开启 management.endpoint.health.probes.enabled："
                            + "非 k8s 环境（compose / 本地）下 /actuator/health/readiness 与 liveness 会 404，"
                            + "k8s 内则依赖平台探测侥幸生效，两种环境行为不一致");
        }
    }

    @Test
    void everyModuleKeepsHealthDetailsPrivate() throws IOException {
        Map<String, Path> modules = moduleApplicationFiles();
        for (Map.Entry<String, Path> entry : modules.entrySet()) {
            Map<String, Object> management = management(entry.getValue());
            Object showDetails = value(management, "endpoint", "health", "show-details");
            assertEquals("never", String.valueOf(showDetails),
                    entry.getKey() + " 必须设置 management.endpoint.health.show-details=never，"
                            + "避免经 /actuator/health 泄露数据源与依赖细节");
        }
    }

    @Test
    void everyModuleUsesTheBootThreePrometheusRegistryKey() throws IOException {
        Map<String, Path> modules = moduleApplicationFiles();
        for (Map.Entry<String, Path> entry : modules.entrySet()) {
            Map<String, Object> management = management(entry.getValue());
            Object enabled = value(management, "prometheus", "metrics", "export", "enabled");
            assertEquals("true", String.valueOf(enabled),
                    entry.getKey() + " 必须显式设置 management.prometheus.metrics.export.enabled=true");
            assertFalse(management.containsKey("metrics"),
                    entry.getKey() + " 仍在用 Boot 2 的 management.metrics.export.prometheus.*："
                            + "该前缀在 Boot 3 已无绑定类（spring-boot-actuator-autoconfigure 3.5.16 只绑定 "
                            + "management.prometheus.metrics.export），配置项是死代码");
        }
    }

    @Test
    void ingressMustNotExposeActuator() throws IOException {
        String text = Files.readString(ROOT.resolve("k8s/ingress.yaml"), StandardCharsets.UTF_8);
        assertFalse(text.contains("/actuator"),
                "k8s/ingress.yaml 不应将 /actuator 路由到公网：k8s 探针走 kubelet 直连 pod，"
                        + "Prometheus 走内网 service DNS，公网 /actuator 只会暴露指标与健康详情泄露面；"
                        + "运维需要 actuator 时用 kubectl port-forward 或内网 ingress");
    }

    @Test
    void yamlExtractionIsTrustworthy() {
        String withPrometheus = String.join("\n",
                "server:",
                "  port: 8097",
                "---",
                "management:",
                "  prometheus:",
                "    metrics:",
                "      export:",
                "        enabled: true",
                "  endpoints:",
                "    web:",
                "      exposure:",
                "        include: health,info,prometheus",
                "  endpoint:",
                "    health:",
                "      probes:",
                "        enabled: true",
                "      show-details: never",
                "");
        Map<String, Object> management = managementOf(withPrometheus);
        assertTrue(exposureInclude(management).contains("prometheus"));
        assertEquals("true", String.valueOf(value(management, "endpoint", "health", "probes", "enabled")));
        assertEquals("never", String.valueOf(value(management, "endpoint", "health", "show-details")));
        assertEquals("true", String.valueOf(value(management, "prometheus", "metrics", "export", "enabled")));
        assertEquals("8097", portOf(withPrometheus), "必须能跨 '---' 文档分隔符读到 server.port");

        String withoutPrometheus = String.join("\n",
                "management:",
                "  endpoints:",
                "    web:",
                "      exposure:",
                "        include: health,info",
                "");
        assertFalse(exposureInclude(managementOf(withoutPrometheus)).contains("prometheus"),
                "解析器必须能区分缺少 prometheus 的配置，否则本套件会永远通过");
    }

    private static Map<String, Path> moduleApplicationFiles() {
        Map<String, Path> files = new TreeMap<>();
        files.put("gateway", ROOT.resolve("amz-gateway/src/main/resources/application.yml"));
        for (String service : SERVICES) {
            files.put(service, ROOT.resolve("amz-service/amz-service-" + service
                    + "/src/main/resources/application.yml"));
        }
        for (Map.Entry<String, Path> entry : files.entrySet()) {
            assertTrue(Files.isRegularFile(entry.getValue()), "缺少 application.yml：" + entry.getValue());
        }
        return files;
    }

    private static Map<String, String> scrapeTargetsByModule() throws IOException {
        String text = Files.readString(ROOT.resolve("prometheus/prometheus.yml"), StandardCharsets.UTF_8);
        Map<String, String> targets = new LinkedHashMap<>();
        Matcher matcher = SCRAPE_TARGET.matcher(text);
        while (matcher.find()) {
            String host = matcher.group(1);
            String module = host.equals("amz-gateway") ? "gateway" : host.replace("amz-service-", "");
            targets.put(module, matcher.group(2));
        }
        return targets;
    }

    private static String serverPort(Path yml) throws IOException {
        for (Map<String, Object> document : documents(yml)) {
            Object port = value(document, "server", "port");
            if (port != null) {
                return String.valueOf(port);
            }
        }
        return "null";
    }

    private static Map<String, Object> management(Path yml) throws IOException {
        for (Map<String, Object> document : documents(yml)) {
            if (document.get("management") instanceof Map<?, ?>) {
                return castMap(document.get("management"));
            }
        }
        return new LinkedHashMap<>();
    }

    private static Map<String, Object> managementOf(String yaml) {
        for (Object document : new Yaml().loadAll(new StringReader(yaml))) {
            if (document instanceof Map<?, ?> map && map.get("management") instanceof Map<?, ?>) {
                return castMap(map.get("management"));
            }
        }
        return new LinkedHashMap<>();
    }

    private static String portOf(String yaml) {
        for (Object document : new Yaml().loadAll(new StringReader(yaml))) {
            if (document instanceof Map<?, ?> map) {
                Object port = value(castMap(map), "server", "port");
                if (port != null) {
                    return String.valueOf(port);
                }
            }
        }
        return "null";
    }

    private static Set<String> exposureInclude(Map<String, Object> management) {
        Object include = value(management, "endpoints", "web", "exposure", "include");
        Set<String> values = new java.util.LinkedHashSet<>();
        if (include instanceof java.util.Collection<?> items) {
            for (Object item : items) {
                values.add(String.valueOf(item).trim());
            }
        } else if (include != null) {
            for (String item : String.valueOf(include).split(",")) {
                values.add(item.trim());
            }
        }
        return values;
    }

    private static Iterable<Map<String, Object>> documents(Path yml) throws IOException {
        java.util.List<Map<String, Object>> parsed = new java.util.ArrayList<>();
        try (Reader reader = Files.newBufferedReader(yml, StandardCharsets.UTF_8)) {
            for (Object document : new Yaml().loadAll(reader)) {
                if (document instanceof Map<?, ?> map) {
                    parsed.add(castMap(map));
                }
            }
        }
        return parsed;
    }

    private static Object value(Map<String, Object> root, String... path) {
        Object current = root;
        for (String key : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = castMap(map).get(key);
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : new LinkedHashMap<>();
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
