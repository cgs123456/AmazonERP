package com.amz.deploy;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 部署清单契约：环境模板、K8s 引用、Secret 结构和 profile 必须一致。
 */
class DeploymentManifestContractTest {

    private static final Path ROOT = findRepoRoot();
    private static final Pattern COMPOSE_VARIABLE = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)");
    private static final Pattern ENV_LINE = Pattern.compile("(?m)^([A-Z][A-Z0-9_]*)=");
    private static final Set<String> K8S_ONLY_ENV = Set.of("JAVA_OPTS_GATEWAY");
    private static final Set<String> MANDATORY_SECRET_PLACEHOLDERS = Set.of(
            "DB_USERNAME", "DB_PASSWORD", "REDIS_PASSWORD",
            "RABBITMQ_USERNAME", "RABBITMQ_PASSWORD",
            "MONGO_USERNAME", "MONGO_PASSWORD");

    @Test
    void envExamplesCoverComposeAndConfigMapWithoutStaleAliases() throws IOException {
        Set<String> prodKeys = envKeys(ROOT.resolve(".env.example"));
        Set<String> demoKeys = envKeys(ROOT.resolve(".env.demo.example"));
        assertEquals(prodKeys, demoKeys,
                "生产模板与无凭证演示模板必须提供完全相同的键，避免演示路径偷偷缺配置");

        Set<String> composeVariables = composeVariables();
        assertTrue(prodKeys.containsAll(composeVariables),
                ".env.example 缺少 Compose 变量：" + difference(composeVariables, prodKeys));

        Set<String> configMapKeys = yamlDataKeys(ROOT.resolve("k8s/configmap.yaml"));
        Set<String> requiredConfigMapKeys = new HashSet<>(configMapKeys);
        requiredConfigMapKeys.removeAll(K8S_ONLY_ENV);
        assertTrue(prodKeys.containsAll(requiredConfigMapKeys),
                ".env.example 缺少 ConfigMap 键：" + difference(requiredConfigMapKeys, prodKeys));

        assertFalse(prodKeys.contains("MQ_USERNAME"), "已废弃的 MQ_USERNAME 不得继续出现在模板中");
        assertFalse(prodKeys.contains("MQ_PASSWORD"), "已废弃的 MQ_PASSWORD 不得继续出现在模板中");

        Map<String, String> prod = envValues(ROOT.resolve(".env.example"));
        Map<String, String> demo = envValues(ROOT.resolve(".env.demo.example"));
        assertEquals("prod", prod.get("SPRING_PROFILES_ACTIVE"));
        assertEquals("mock", demo.get("SPRING_PROFILES_ACTIVE"));
        assertEquals("true", prod.get("SPAPI_REQUIRE_CREDENTIALS"));
        assertEquals("false", demo.get("SPAPI_REQUIRE_CREDENTIALS"));
        assertTrue(Base64.getDecoder().decode(demo.get("AMZ_CRYPTO_KEY")).length == 32,
                "演示模板的 AMZ_CRYPTO_KEY 必须解码为 32 字节");
        assertTrue(demo.get("JWT_SECRET_KEY").getBytes(StandardCharsets.UTF_8).length >= 32,
                "演示模板的 JWT_SECRET_KEY 至少 32 字节");
    }

    @Test
    void spapiUploadMultipartLimitsAreExplicitAndBounded() throws IOException {
        Map<String, Object> root = loadYaml(ROOT.resolve(
                "amz-service/amz-service-spapi/src/main/resources/application.yml"));
        Map<String, Object> spring = castMap(root.get("spring"));
        Map<String, Object> servlet = castMap(spring.get("servlet"));
        Map<String, Object> multipart = castMap(servlet.get("multipart"));

        assertEquals("10MB", String.valueOf(multipart.get("max-file-size")));
        assertEquals("11MB", String.valueOf(multipart.get("max-request-size")));
    }

    @Test
    void spapiPrometheusEndpointExposesRateLimitObservationGauge() throws IOException {
        Map<String, Object> root = loadYaml(ROOT.resolve(
                "amz-service/amz-service-spapi/src/main/resources/application.yml"));
        Map<String, Object> management = castMap(root.get("management"));
        Map<String, Object> endpoints = castMap(management.get("endpoints"));
        Map<String, Object> web = castMap(endpoints.get("web"));
        Map<String, Object> exposure = castMap(web.get("exposure"));
        assertTrue(String.valueOf(exposure.get("include")).contains("prometheus"),
                "P0-52b 的限流观测 Gauge 必须能从 /actuator/prometheus 抓取，不能只存在于内存");

        Map<String, Object> prometheus = castMap(management.get("prometheus"));
        Map<String, Object> metrics = castMap(prometheus.get("metrics"));
        Map<String, Object> export = castMap(metrics.get("export"));
        assertEquals(Boolean.TRUE, export.get("enabled"),
                "Spring Boot 3 的主配置键必须显式启用 Prometheus registry");
        assertFalse(castMap(management.get("metrics")).containsKey("export"),
                "不得继续使用 Boot 2 已废弃的 management.metrics.export.prometheus.enabled");
    }

    @Test
    void gatewayExposesConnectorCapabilityPathAlias() throws IOException {
        Map<String, Object> root = loadFirstYamlDocument(
                ROOT.resolve("amz-gateway/src/main/resources/application.yml"));
        Map<String, Object> spring = castMap(root.get("spring"));
        Map<String, Object> cloud = castMap(spring.get("cloud"));
        Map<String, Object> gateway = castMap(cloud.get("gateway"));
        List<Object> routes = castList(gateway.get("routes"));

        Map<String, Object> alias = routes.stream()
                .map(DeploymentManifestContractTest::castMap)
                .filter(route -> "amz-service-spapi-connectors-api".equals(route.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少 /api/connectors 网关别名路由"));

        assertEquals("lb://amz-service-spapi", alias.get("uri"));
        assertTrue(castList(alias.get("predicates")).isEmpty() == false
                        && String.valueOf(castList(alias.get("predicates")).get(0))
                        .equals("Path=/api/connectors/**"),
                "别名路由必须只匹配 /api/connectors/**");
        assertEquals("RewritePath=/api/connectors(?<segment>.*), /spapi/connectors${segment}",
                String.valueOf(castList(alias.get("filters")).get(0)),
                "别名必须重写到服务实际映射 /spapi/connectors，不能把 /api 前缀原样转发");
    }

    @Test
    void gatewayExposesCredentialManagementPathAlias() throws IOException {
        Map<String, Object> root = loadFirstYamlDocument(
                ROOT.resolve("amz-gateway/src/main/resources/application.yml"));
        Map<String, Object> spring = castMap(root.get("spring"));
        Map<String, Object> cloud = castMap(spring.get("cloud"));
        Map<String, Object> gateway = castMap(cloud.get("gateway"));
        List<Object> routes = castList(gateway.get("routes"));

        Map<String, Object> alias = routes.stream()
                .map(DeploymentManifestContractTest::castMap)
                .filter(route -> "amz-service-spapi-credentials-api".equals(route.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少 /api/credentials 网关别名路由"));

        assertEquals("lb://amz-service-spapi", alias.get("uri"));
        assertTrue(castList(alias.get("predicates")).isEmpty() == false
                        && String.valueOf(castList(alias.get("predicates")).get(0))
                        .equals("Path=/api/credentials/**"),
                "凭证别名路由必须只匹配 /api/credentials/**");
        assertEquals("RewritePath=/api/credentials(?<segment>.*), /spapi/credentials${segment}",
                String.valueOf(castList(alias.get("filters")).get(0)),
                "凭证别名必须重写到服务实际映射 /spapi/credentials");
    }

    @Test
    void gatewayExposesPreflightPathAlias() throws IOException {
        Map<String, Object> root = loadFirstYamlDocument(
                ROOT.resolve("amz-gateway/src/main/resources/application.yml"));
        Map<String, Object> spring = castMap(root.get("spring"));
        Map<String, Object> cloud = castMap(spring.get("cloud"));
        Map<String, Object> gateway = castMap(cloud.get("gateway"));
        List<Object> routes = castList(gateway.get("routes"));

        Map<String, Object> alias = routes.stream()
                .map(DeploymentManifestContractTest::castMap)
                .filter(route -> "amz-service-spapi-preflight-api".equals(route.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少 /api/preflight 网关别名路由"));

        assertEquals("lb://amz-service-spapi", alias.get("uri"));
        assertTrue(castList(alias.get("predicates")).isEmpty() == false
                        && String.valueOf(castList(alias.get("predicates")).get(0))
                        .equals("Path=/api/preflight/**"),
                "别名路由必须只匹配 /api/preflight/**");
        assertEquals("RewritePath=/api/preflight(?<segment>.*), /spapi/preflight${segment}",
                String.valueOf(castList(alias.get("filters")).get(0)),
                "别名必须重写到服务实际映射 /spapi/preflight，不能把 /api 前缀原样转发");
    }
    @Test
    void connectorAcceptancePathsMatchServiceAndGatewayContracts() throws IOException {
        String runner = Files.readString(ROOT.resolve("tools/connector-acceptance/acceptance_runner.py"),
                StandardCharsets.UTF_8);
        assertTrue(Pattern.compile("--connectors-path'\\s*,\\s*default='/spapi/connectors'")
                        .matcher(runner).find(),
                "验收 runner 直连服务时默认必须走真实映射 /spapi/connectors，不能默认走网关别名");
        assertTrue(Pattern.compile("--outbox-path'\\s*,\\s*default='/spapi/connectors/outbox'")
                        .matcher(runner).find(),
                "验收 runner 的 Outbox 默认路径必须直连服务真实映射，不能默认走网关别名");

        String stub = Files.readString(ROOT.resolve("tools/connector-acceptance/fake-service.py"),
                StandardCharsets.UTF_8);
        assertTrue(stub.contains("path in ('/spapi/connectors', '/api/connectors')"),
                "验收桩必须同时实现服务真实路径和网关对外别名，避免 runner 与桩互相掩盖路径错误");
        assertTrue(stub.contains("path in ('/spapi/connectors/outbox', '/api/connectors/outbox')"),
                "验收桩必须同时实现 Outbox 直连路径和网关别名");
        assertTrue(stub.contains("if not self.server.outbox_ok:")
                        && stub.contains("if not (self.server.outbox_ok and self.server.outbox_replay_ok):"),
                "Outbox 列表和重放必须由显式开关控制，默认不得成功");
        assertTrue(stub.contains("--outbox-ok") && stub.contains("--outbox-replay-ok"),
                "验收桩必须暴露显式 Outbox 列表/重放开关");
    }

    @Test
    void kubernetesReferencesResolveExactlyToDeclaredConfigAndSecretKeys() throws IOException {
        Set<String> configRefs = new HashSet<>();
        Set<String> secretRefs = new HashSet<>();
        try (Stream<Path> files = Files.list(ROOT.resolve("k8s/services"))) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".yaml")).toList()) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    for (Object document : new Yaml().loadAll(reader)) {
                        collectReferences(document, configRefs, secretRefs);
                    }
                }
            }
        }
        Set<String> configData = yamlDataKeys(ROOT.resolve("k8s/configmap.yaml"));
        Set<String> secretData = yamlDataKeys(ROOT.resolve("k8s/secret.yaml"));
        assertEquals(configData, configRefs,
                "ConfigMap 引用与 data 必须双向一致；未引用=" + difference(configData, configRefs)
                        + "，缺失=" + difference(configRefs, configData));
        assertEquals(secretData, secretRefs,
                "Secret 引用与 data 必须双向一致；未引用=" + difference(secretData, secretRefs)
                        + "，缺失=" + difference(secretRefs, secretData));
    }

    @Test
    void deploymentSecretsAreStructurallyValidPlaceholders() throws IOException {
        Map<String, String> secret = yamlData(ROOT.resolve("k8s/secret.yaml"));
        byte[] cryptoKey = Base64.getDecoder().decode(secret.get("AMZ_CRYPTO_KEY"));
        byte[] jwtKey = Base64.getDecoder().decode(secret.get("JWT_SECRET_KEY"));
        assertEquals(32, cryptoKey.length, "AMZ_CRYPTO_KEY base64 解码后必须恰好 32 字节");
        assertTrue(jwtKey.length >= 32, "JWT_SECRET_KEY 解码后至少 32 字节");
        for (String key : MANDATORY_SECRET_PLACEHOLDERS) {
            byte[] value = Base64.getDecoder().decode(secret.get(key));
            assertTrue(new String(value, StandardCharsets.UTF_8).startsWith("CHANGE_ME_"),
                    key + " 必须保持不可直接用于生产的 CHANGE_ME_ 占位");
        }
        assertTrue(secret.containsKey("AWS_ACCESS_KEY"));
        assertTrue(secret.containsKey("DEEPSEEK_API_KEY"));
        assertTrue(secret.containsKey("KEEPA_API_KEY"));
    }

    @Test
    void allDeploymentsSelectProdProfileExplicitly() throws IOException {
        Map<String, String> configMap = yamlData(ROOT.resolve("k8s/configmap.yaml"));
        assertEquals("prod", configMap.get("SPRING_PROFILES_ACTIVE"));

        Map<String, Map<String, Object>> k8s = kubernetesMainEnvironments();
        for (Map.Entry<String, Map<String, Object>> entry : k8s.entrySet()) {
            Map<String, Object> env = entry.getValue().get("SPRING_PROFILES_ACTIVE") instanceof Map<?, ?>
                    ? castMap(entry.getValue().get("SPRING_PROFILES_ACTIVE")) : Map.of();
            Map<String, Object> valueFrom = castMap(env.get("valueFrom"));
            Map<String, Object> ref = castMap(valueFrom.get("configMapKeyRef"));
            assertNotNull(ref, entry.getKey() + " 必须显式引用 SPRING_PROFILES_ACTIVE ConfigMap 键");
            assertEquals("amz-erp-config", ref.get("name"));
            assertEquals("SPRING_PROFILES_ACTIVE", ref.get("key"));
        }

        Map<String, Object> compose = loadYaml(ROOT.resolve("docker-compose.yml"));
        Map<String, Object> services = castMap(compose.get("services"));
        int checked = 0;
        for (Object value : services.values()) {
            Map<String, Object> body = castMap(value);
            Map<String, String> env = environmentValues(body.get("environment"));
            if (env.containsKey("SPRING_PROFILES_ACTIVE")) {
                assertTrue(env.get("SPRING_PROFILES_ACTIVE").contains("prod"),
                        "Compose 业务服务的 profile 必须显式落到 prod");
                checked++;
            }
        }
        assertEquals(16, checked, "Compose 必须覆盖 gateway + 15 个业务服务的显式 profile");
    }

    private static Map<String, Map<String, Object>> kubernetesMainEnvironments() throws IOException {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(ROOT.resolve("k8s/services"))) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".yaml")).toList()) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    for (Object document : new Yaml().loadAll(reader)) {
                        if (!(document instanceof Map<?, ?> map) || !"Deployment".equals(map.get("kind"))) {
                            continue;
                        }
                        Map<String, Object> deployment = castMap(map);
                        Map<String, Object> metadata = castMap(deployment.get("metadata"));
                        Map<String, Object> spec = castMap(deployment.get("spec"));
                        Map<String, Object> template = castMap(spec.get("template"));
                        Map<String, Object> podSpec = castMap(template.get("spec"));
                        List<Object> containers = castList(podSpec.get("containers"));
                        result.put(String.valueOf(metadata.get("name")), environmentMap(castMap(containers.get(0)).get("env")));
                    }
                }
            }
        }
        return result;
    }

    private static Set<String> composeVariables() throws IOException {
        Matcher matcher = COMPOSE_VARIABLE.matcher(Files.readString(
                ROOT.resolve("docker-compose.yml"), StandardCharsets.UTF_8));
        Set<String> variables = new HashSet<>();
        while (matcher.find()) {
            variables.add(matcher.group(1));
        }
        return variables;
    }

    private static Set<String> envKeys(Path path) throws IOException {
        return envValues(path).keySet();
    }

    private static Map<String, String> envValues(Path path) throws IOException {
        Matcher matcher = ENV_LINE.matcher(Files.readString(path, StandardCharsets.UTF_8));
        Map<String, String> values = new LinkedHashMap<>();
        while (matcher.find()) {
            String line = matcher.group();
            int equals = line.indexOf('=');
            String key = line.substring(0, equals);
            String remainder = Files.readString(path, StandardCharsets.UTF_8);
            int absolute = matcher.start() + equals + 1;
            int end = remainder.indexOf('\n', absolute);
            values.put(key, (end < 0 ? remainder.substring(absolute) : remainder.substring(absolute, end)).trim());
        }
        return values;
    }

    private static Set<String> yamlDataKeys(Path path) throws IOException {
        return yamlData(path).keySet();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> yamlData(Path path) throws IOException {
        Map<String, Object> root = loadYaml(path);
        Map<String, Object> data = (Map<String, Object>) root.get("data");
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            result.put(entry.getKey(), entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return new Yaml().load(reader);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadFirstYamlDocument(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            for (Object document : new Yaml().loadAll(reader)) {
                if (document instanceof Map<?, ?> map) {
                    return (Map<String, Object>) map;
                }
            }
            throw new IllegalStateException("YAML 无可解析文档：" + path);
        }
    }

    private static Map<String, String> environmentValues(Object environment) {
        Map<String, String> values = new LinkedHashMap<>();
        if (environment instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map && map.get("name") != null) {
                    values.put(String.valueOf(map.get("name")), String.valueOf(map.get("value") == null ? "" : map.get("value")));
                } else if (item != null) {
                    String raw = String.valueOf(item);
                    int equals = raw.indexOf('=');
                    if (equals >= 0) {
                        values.put(raw.substring(0, equals), raw.substring(equals + 1));
                    }
                }
            }
        } else if (environment instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                values.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        return values;
    }

    private static Map<String, Object> environmentMap(Object environment) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (environment instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> map = castMap(item);
                values.put(String.valueOf(map.get("name")), map);
            }
        }
        return values;
    }

    private static void collectReferences(Object value, Set<String> configRefs, Set<String> secretRefs) {
        if (value instanceof Map<?, ?> map) {
            Object configRef = map.get("configMapKeyRef");
            if (configRef instanceof Map<?, ?> ref && ref.get("key") != null) {
                configRefs.add(String.valueOf(ref.get("key")));
            }
            Object secretRef = map.get("secretKeyRef");
            if (secretRef instanceof Map<?, ?> ref && ref.get("key") != null) {
                secretRefs.add(String.valueOf(ref.get("key")));
            }
            for (Object child : map.values()) {
                collectReferences(child, configRefs, secretRefs);
            }
        } else if (value instanceof List<?> list) {
            for (Object child : list) {
                collectReferences(child, configRefs, secretRefs);
            }
        }
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new HashSet<>(left);
        result.removeAll(right);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : new HashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object value) {
        return value instanceof List<?> ? (List<Object>) value : List.of();
    }

    private static Path findRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getParent()) {
            if (Files.isRegularFile(current.resolve("docker-compose.yml"))
                    && Files.isDirectory(current.resolve("k8s/services"))) {
                return current;
            }
        }
        throw new IllegalStateException("无法从 user.dir 定位仓库根目录：" + System.getProperty("user.dir"));
    }
}
