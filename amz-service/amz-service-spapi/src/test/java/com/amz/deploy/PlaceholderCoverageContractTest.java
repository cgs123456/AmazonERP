package com.amz.deploy;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 代码占位符与两条部署路径的双向契约。
 *
 * <p>application-local.yml 是开发者本机覆盖文件，application-bootstrap.yml 是独立的一次性
 * 部署路径，两者显式排除；其余 main/resources YAML 中的 UPPER_SNAKE 占位符必须在 Compose
 * 与 K8s Deployment 中逐模块精确覆盖。bootstrap 路径由 BootstrapDeploymentContractTest 单独约束。</p>
 */
class PlaceholderCoverageContractTest {

    private static final Path ROOT = findRepoRoot();
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)");

    private static final List<String> BUSINESS_MODULES = List.of(
            "amz-service-ad",
            "amz-service-ai",
            "amz-service-customer",
            "amz-service-finance",
            "amz-service-logistics",
            "amz-service-message",
            "amz-service-multiplatform",
            "amz-service-ops",
            "amz-service-order",
            "amz-service-procurement",
            "amz-service-product",
            "amz-service-report",
            "amz-service-search",
            "amz-service-spapi",
            "amz-service-user"
    );

    @Test
    void composeAndKubernetesInjectEveryNonLocalPlaceholderAndNothingElse() throws IOException {
        Map<String, Set<String>> composeEnvironments = composeEnvironments();
        Map<String, Set<String>> k8sEnvironments = kubernetesEnvironments();

        for (String module : allModules()) {
            Set<String> expected = sourcePlaceholders(module);
            assertTrue(!expected.isEmpty(), module + " 未扫描到任何运行时占位符，扫描路径可能失效");

            Set<String> composeAllowed = new HashSet<>(expected);
            composeAllowed.add("SPRING_PROFILES_ACTIVE");
            composeAllowed.add("TZ");
            composeAllowed.add("JAVA_OPTS");
            // SkyWalking 服务名只被 java agent 读取，代码里不存在 ${SW_AGENT_NAME} 占位符，
            // 因此它不会出现在 sourcePlaceholders 的推导集里。取值正确性由
            // SkyWalkingIdentityContractTest 逐服务约束，这里只声明"允许注入"。
            composeAllowed.add("SW_AGENT_NAME");
            // CRYPTO_KEY 也不以 ${CRYPTO_KEY} 形式出现在代码里：CryptoUtil 用
            // @Value("${crypto.key:}") 查属性，Spring 宽松绑定把环境变量 CRYPTO_KEY 落到该属性。
            // 只有 spapi/user 的 application.yml 显式写了 crypto.key: ${AMZ_CRYPTO_KEY:}，
            // 其余 14 个服务全靠这个名字；缺它会启动即拒（见 CryptoKeyProvisioningContractTest 的 A/B 实测）。
            composeAllowed.add("CRYPTO_KEY");
            Set<String> composeActual = composeEnvironments.get(module);
            assertTrue(composeActual != null, "Compose 缺少服务段：" + module);
            assertEquals(composeAllowed, composeActual,
                    module + " 的 Compose 注入与代码占位符不一致；缺少="
                            + difference(composeAllowed, composeActual) + "，多余="
                            + difference(composeActual, composeAllowed));

            Set<String> k8sAllowed = new HashSet<>(expected);
            k8sAllowed.add("SPRING_PROFILES_ACTIVE");
            k8sAllowed.add("TZ");
            k8sAllowed.add(module.equals("amz-gateway") ? "JAVA_OPTS_GATEWAY" : "JAVA_OPTS");
            k8sAllowed.add("SW_AGENT_NAME");
            // collector 地址是 agent 侧变量，代码里不读它；k8s 需要 FQDN 而 compose 用镜像默认的服务名，
            // 故只加进 k8s 允许集。取值与 OAP Service 的一致性由 SkyWalkingIdentityContractTest 核对。
            k8sAllowed.add("SW_AGENT_COLLECTOR_BACKEND_SERVICES");
            // 同 compose：CRYPTO_KEY 是 crypto.key 的宽松绑定别名，代码里没有同名占位符；
            // 值从既有 secret 键 AMZ_CRYPTO_KEY 取，不新增一份密钥。
            // 逐服务是否真的供给到由 CryptoKeyProvisioningContractTest 负责。
            k8sAllowed.add("CRYPTO_KEY");
            Set<String> k8sActual = k8sEnvironments.get(module);
            assertTrue(k8sActual != null, "K8s 缺少 Deployment：" + module);
            assertEquals(k8sAllowed, k8sActual,
                    module + " 的 K8s 注入与代码占位符不一致；缺少="
                            + difference(k8sAllowed, k8sActual) + "，多余="
                            + difference(k8sActual, k8sAllowed));
        }
    }

    @Test
    void reportModuleKeepsItsDatabaseContractInBothDeploymentPaths() throws IOException {
        String yml = Files.readString(ROOT.resolve(
                "amz-service/amz-service-report/src/main/resources/application.yml"), StandardCharsets.UTF_8);
        assertTrue(yml.contains("jdbc:mysql://${MYSQL_HOST:localhost}:${MYSQL_PORT:3306}/amz_report?"),
                "report 有 6 个 MyBatis 实体，必须显式连接 amz_report");
        assertTrue(yml.contains("username: ${DB_USERNAME:root}"));
        assertTrue(yml.contains("password: ${DB_PASSWORD:}"));

        long tableNames = countOccurrences(ROOT.resolve("amz-service/amz-service-report/src/main/java"), "@TableName");
        long baseMappers = countOccurrences(ROOT.resolve("amz-service/amz-service-report/src/main/java"), "extends BaseMapper");
        assertEquals(6, tableNames, "report 的 @TableName 数量变化时必须重新核对数据源契约");
        assertEquals(6, baseMappers, "report 的 BaseMapper 数量变化时必须重新核对数据源契约");

        Set<String> required = Set.of("MYSQL_HOST", "MYSQL_PORT", "DB_USERNAME", "DB_PASSWORD");
        assertTrue(composeEnvironments().get("amz-service-report").containsAll(required));
        assertTrue(kubernetesEnvironments().get("amz-service-report").containsAll(required));
    }

    private static List<String> allModules() {
        List<String> modules = new ArrayList<>();
        modules.add("amz-gateway");
        modules.addAll(BUSINESS_MODULES);
        return modules;
    }

    private static Set<String> sourcePlaceholders(String module) throws IOException {
        Path resources = module.equals("amz-gateway")
                ? ROOT.resolve("amz-gateway/src/main/resources")
                : ROOT.resolve("amz-service").resolve(module).resolve("src/main/resources");
        Set<String> keys = new HashSet<>();
        try (Stream<Path> files = Files.walk(resources)) {
            List<Path> yamlFiles = files.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return !name.equals("application-local.yml")
                                && !name.equals("application-bootstrap.yml")
                                && (name.endsWith(".yml") || name.endsWith(".yaml"));
                    })
                    .toList();
            for (Path file : yamlFiles) {
                Matcher matcher = PLACEHOLDER.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    keys.add(matcher.group(1));
                }
            }
        }
        return keys;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Set<String>> composeEnvironments() throws IOException {
        Map<String, Object> root;
        try (Reader reader = Files.newBufferedReader(ROOT.resolve("docker-compose.yml"), StandardCharsets.UTF_8)) {
            root = new Yaml().load(reader);
        }
        Map<String, Object> services = (Map<String, Object>) root.get("services");
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : services.entrySet()) {
            String service = entry.getKey();
            if (!service.startsWith("amz-")) {
                continue;
            }
            Map<String, Object> body = (Map<String, Object>) entry.getValue();
            result.put(service, environmentKeys(body.get("environment")));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Set<String>> kubernetesEnvironments() throws IOException {
        Path dir = ROOT.resolve("k8s/services");
        Map<String, Set<String>> result = new HashMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".yaml")).toList()) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    for (Object document : new Yaml().loadAll(reader)) {
                        if (!(document instanceof Map<?, ?> map) || !"Deployment".equals(map.get("kind"))) {
                            continue;
                        }
                        Map<String, Object> deployment = (Map<String, Object>) map;
                        Map<String, Object> metadata = (Map<String, Object>) deployment.get("metadata");
                        String name = String.valueOf(metadata.get("name"));
                        result.put(name, deploymentEnvironmentKeys(deployment));
                    }
                }
            }
        }
        return result;
    }

    private static Set<String> deploymentEnvironmentKeys(Map<String, Object> deployment) {
        Map<String, Object> spec = castMap(deployment.get("spec"));
        Map<String, Object> template = castMap(spec.get("template"));
        Map<String, Object> podSpec = castMap(template.get("spec"));
        List<Object> containers = castList(podSpec.get("containers"));
        Set<String> keys = new HashSet<>();
        for (Object value : containers) {
            Map<String, Object> container = castMap(value);
            keys.addAll(environmentKeys(container.get("env")));
        }
        return keys;
    }

    private static Set<String> environmentKeys(Object environment) {
        Set<String> keys = new HashSet<>();
        if (environment instanceof Map<?, ?> map) {
            for (Object key : map.keySet()) {
                keys.add(String.valueOf(key));
            }
        } else if (environment instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map && map.get("name") != null) {
                    keys.add(String.valueOf(map.get("name")));
                } else if (item != null) {
                    String raw = String.valueOf(item);
                    int equals = raw.indexOf('=');
                    keys.add(equals >= 0 ? raw.substring(0, equals) : raw);
                }
            }
        }
        return keys;
    }

    private static long countOccurrences(Path root, String needle) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .mapToLong(path -> {
                        try {
                            String text = Files.readString(path, StandardCharsets.UTF_8);
                            int count = 0;
                            int index = 0;
                            while ((index = text.indexOf(needle, index)) >= 0) {
                                count++;
                                index += needle.length();
                            }
                            return count;
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .sum();
        }
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new HashSet<>(left);
        result.removeAll(right);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object value) {
        return (List<Object>) value;
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