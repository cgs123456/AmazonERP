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
    void everyModuleUsesSkyWalkingTraceIdConverter() throws IOException {
        Map<String, Path> modules = moduleApplicationFiles();
        for (Map.Entry<String, Path> entry : modules.entrySet()) {
            String content = Files.readString(entry.getValue(), StandardCharsets.UTF_8);
            assertTrue(content.contains("[%traceId]"),
                    entry.getKey() + " 日志 pattern 必须使用 %traceId（SkyWalking 转换器），"
                            + "而不是 %X{traceId}（MDC 直读，无任何代码向 MDC 写入，"
                            + "永远输出空字符串）");
            assertFalse(content.contains("%X{traceId}"),
                    entry.getKey() + " 日志 pattern 仍在使用 %X{traceId}："
                            + "全仓无代码调用 MDC.put(\"traceId\",...)，该占位符永远输出空");
            assertFalse(content.contains("%X{tid}"),
                    entry.getKey() + " 日志 pattern 使用了 %X{tid}：SkyWalking 只增强自己的 "
                            + "mdc.LogbackMDCPatternConverter，且需要 TraceIdMDCPatternLogbackLayout 注册转换词；"
                            + "直接 %X{tid} 同样是读空 MDC（已实测输出为空）");
            assertTrue(content.contains("%wEx"),
                    entry.getKey() + " 日志 pattern 缺少 %wEx（异常输出转换词）："
                            + "自定义 pattern 覆盖 Spring Boot 默认后异常堆栈不会打印");
        }
    }

    @Test
    void logbackSpringRegistersTraceIdConversionRule() throws IOException {
        String logback = Files.readString(
                ROOT.resolve("amz-common/src/main/resources/logback-spring.xml"), StandardCharsets.UTF_8);
        assertTrue(logback.contains("conversionWord=\"traceId\""),
                "logback-spring.xml 必须注册 conversionWord=traceId，"
                        + "否则 %traceId 在运行时会报 conversionWord 未定义");
        assertTrue(logback.contains("org.apache.skywalking.apm.toolkit.log.logback.v1.x.LogbackPatternConverter"),
                "logback-spring.xml 必须把 %traceId 绑定到 toolkit 里真实存在的 LogbackPatternConverter"
                        + "（该类的常量返回值 TID: N/A 是无 agent 时的降级值，有 agent 时由 "
                        + "apm-toolkit-logback-1.x-activation 字节码增强为真值）");
        assertFalse(logback.contains("TraceIdConverter"),
                "logback-spring.xml 引用了 TraceIdConverter：该类不存在于 apm-toolkit-logback-1.x:9.7.0。"
                        + "logback 会记录 ERROR status，Spring Boot 3.5 随即抛 "
                        + "IllegalStateException(\"Logback configuration error detected\") 导致 16 个服务启动失败");
    }

    @Test
    void rootPomManagesSkyWalkingToolkitVersion() throws IOException {
        String pom = Files.readString(ROOT.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertTrue(pom.contains("<artifactId>apm-toolkit-logback-1.x</artifactId>"),
                "根 pom 的 dependencyManagement 必须管理 apm-toolkit-logback-1.x 版本，"
                        + "否则各模块声明时无版本可用");
        assertTrue(pom.contains("<skywalking.version>9.7.0</skywalking.version>"),
                "根 pom 必须声明 skywalking.version，且与 Dockerfile agent 9.7.0 对齐");
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

    @Test
    void structuredJsonLoggingProfileIsComplete() throws IOException {
        String logback = Files.readString(
                ROOT.resolve("amz-common/src/main/resources/logback-spring.xml"), StandardCharsets.UTF_8);

        assertTrue(logback.contains("<springProfile name=\"!log-json\">"),
                "缺少 !log-json 分支：未启用 log-json 时服务会失去文本控制台日志");
        assertTrue(logback.contains("<springProfile name=\"log-json\">"),
                "缺少 log-json 分支：无法通过 profile 开启结构化 JSON 日志");
        assertTrue(logback.contains("<appender name=\"JSON_CONSOLE\""),
                "log-json 分支必须定义 JSON_CONSOLE 控制台 appender");
        assertTrue(logback.contains("net.logstash.logback.encoder.LogstashEncoder"),
                "JSON_CONSOLE 必须使用 logstash-logback-encoder 的 LogstashEncoder");
        assertTrue(logback.contains("org.apache.skywalking.apm.toolkit.log.logback.v1.x.logstash.TraceIdJsonProvider"),
                "JSON 日志必须用 SkyWalking 官方 TraceIdJsonProvider 注入 traceId："
                        + "它输出的值不带 TID: 前缀，可直接拿去 SkyWalking UI 查这条 trace");
        assertTrue(logback.contains("<fieldName>TID</fieldName>"),
                "必须显式声明 <fieldName>TID</fieldName>：provider 的构造器不设置字段名，"
                        + "一旦 encoder 未通过 FieldNamesAware 回写，字段会被静默丢弃（无 agent 时已实测为整字段缺失）");
        assertFalse(logback.contains("\"traceId\":\"%traceId\""),
                "JSON 分支不要用 pattern provider 输出 traceId：agent 增强后的 LogbackPatternConverter "
                        + "返回带 \"TID:\" 前缀的值，会让 traceId 字段无法直接用于检索");
        assertTrue(logback.contains("<springProperty name=\"serviceName\" source=\"spring.application.name\""),
                "JSON 日志必须通过 springProperty 注入 spring.application.name，否则无法识别来源服务");
        assertTrue(logback.contains("<appender-ref ref=\"JSON_CONSOLE\"/>"),
                "log-json 分支的 root logger 必须挂载 JSON_CONSOLE");
        assertTrue(logback.indexOf("<springProfile name=\"log-json\">")
                        < logback.indexOf("<appender-ref ref=\"JSON_CONSOLE\"/>"),
                "JSON_CONSOLE 必须只在 log-json 分支内挂载");
    }

    @Test
    void rootPomManagesLogstashEncoderVersion() throws IOException {
        String pom = Files.readString(ROOT.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertTrue(pom.contains("<logstash-logback-encoder.version>8.0</logstash-logback-encoder.version>"),
                "根 pom 必须显式钉住 logstash-logback-encoder 版本，避免跟随传递依赖漂移");
        assertTrue(pom.contains("<artifactId>logstash-logback-encoder</artifactId>"),
                "根 pom 的 dependencyManagement 必须管理 logstash-logback-encoder");
    }

    @Test
    void composeDefaultsKeepTextLoggingWithJsonAsOptIn() throws IOException {
        String compose = Files.readString(ROOT.resolve("docker-compose.yml"), StandardCharsets.UTF_8);
        long matches = compose.lines()
                .filter(line -> line.contains("${SPRING_PROFILES_ACTIVE:-prod}"))
                .count();
        assertEquals(16, matches,
                "docker-compose 应有 16 个应用服务默认 prod 文本日志（gateway + 15 个业务服务）");
        assertFalse(compose.contains(":-prod,log-json}"),
                "compose 默认 profile 不得打开 log-json：logstash/logstash.conf 的 grok 消费者按文本 pattern 解析，"
                        + "整体切成 JSON 会让现网采集与告警立刻断链；JSON 必须由部署侧显式 opt-in");
    }

    @Test
    void kubernetesDefaultProfileKeepsTextLogging() throws IOException {
        String configMap = Files.readString(ROOT.resolve("k8s/configmap.yaml"), StandardCharsets.UTF_8);
        assertTrue(configMap.contains("SPRING_PROFILES_ACTIVE: prod"),
                "k8s 集中配置的默认 profile 必须是 prod 文本日志");
        assertFalse(configMap.contains("prod,log-json"),
                "k8s 默认 profile 不得打开 log-json，理由同 compose：采集端未确认支持 JSON 前不改日志形态");
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
