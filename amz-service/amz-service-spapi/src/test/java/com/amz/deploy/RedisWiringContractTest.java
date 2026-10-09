package com.amz.deploy;

import org.junit.jupiter.api.DisplayName;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「声明了 Redis starter 就必须把它接到真实 Redis」的部署契约。
 * <p>
 * <b>存在动因（2026-10-09 复核）</b>：{@code spring-boot-starter-data-redis} 在 classpath 上时，
 * Boot 的 {@code DataRedisAutoConfiguration} 会无条件建一个 {@code LettuceConnectionFactory}
 * （默认 {@code localhost:6379}、无密码），{@code DataRedisHealthContributorAutoConfiguration}
 * 再据此注册 redis 健康指示器。于是「只加依赖、不写配置」的服务在容器里会连容器自身的
 * 6379（那里什么都没有）→ {@code /actuator/health} 聚合为 DOWN → compose 的
 * {@code healthcheck}（`grep -q "status":"UP"`）恒判不健康。它不像启动失败那样响亮，
 * 所以容易一直没人发现。
 * <p>
 * 契约内容：凡 pom 声明了 starter 的业务模块，必须同时
 * ① 在 {@code application.yml} 里把 {@code spring.data.redis.host} 指向 {@code ${REDIS_HOST}}；
 * ② 在 compose 与 k8s 两条部署路径上都拿到 {@code REDIS_HOST}/{@code REDIS_PORT}/{@code REDIS_PASSWORD}。
 * 只声明类库（{@code spring-data-redis}，如 message）的模块不在此列——那种写法刻意不触发自动配置。
 * <p>
 * 供给侧的「有没有」由本测试把关；{@code PlaceholderCoverageContractTest} 另行把关
 * 「注入的键集合与 yml 占位符精确相等」，两条互补。
 */
@DisplayName("部署契约：声明 Redis starter 的模块必须接上真实 Redis")
class RedisWiringContractTest {

    private static final Path ROOT = findRepoRoot();

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

    /** starter 是触发自动配置的那种依赖；只依赖 spring-data-redis 类库的模块刻意排除。 */
    private static final String STARTER_ARTIFACT = "spring-boot-starter-data-redis";

    @Test
    @DisplayName("每个声明了 Redis starter 的模块都在 yml 里指向 ${REDIS_HOST}")
    void starterImpliesRedisHostPlaceholder() throws IOException {
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        for (String module : BUSINESS_MODULES) {
            if (!declaresStarter(module)) {
                continue;
            }
            checked++;
            Path yml = ROOT.resolve("amz-service").resolve(module)
                    .resolve("src/main/resources/application.yml");
            String text = Files.readString(yml, StandardCharsets.UTF_8);
            if (!text.contains("${REDIS_HOST")) {
                offenders.add(module);
            }
        }
        assertEquals(15 - 1, checked,
                "只声明 starter 的模块数变了：message 走的是类库（spring-data-redis），应被排除；"
                        + "模块增减时本契约的期望集合要一起复核");
        assertEquals(List.of(), offenders,
                "这些模块声明了 Redis starter 却没把 host 指向 ${REDIS_HOST}：容器里会连 localhost:6379，"
                        + "/actuator/health 恒 DOWN、compose healthcheck 恒不健康（见类注释）");
    }

    @Test
    @DisplayName("这些模块在 compose 与 k8s 两条路径上都拿到 REDIS_HOST/PORT/PASSWORD")
    void bothDeploymentPathsSupplyRedis() throws IOException {
        Map<String, Set<String>> compose = composeEnvironments();
        Map<String, Set<String>> k8s = kubernetesEnvironments();
        Set<String> required = Set.of("REDIS_HOST", "REDIS_PORT", "REDIS_PASSWORD");
        List<String> offenders = new ArrayList<>();
        for (String module : BUSINESS_MODULES) {
            if (!declaresStarter(module)) {
                continue;
            }
            Set<String> composeEnv = compose.get(module);
            Set<String> k8sEnv = k8s.get(module);
            assertTrue(composeEnv != null, "Compose 缺少服务段：" + module);
            assertTrue(k8sEnv != null, "K8s 缺少 Deployment：" + module);
            if (!composeEnv.containsAll(required)) {
                offenders.add(module + "（compose 缺 " + difference(required, composeEnv) + "）");
            }
            if (!k8sEnv.containsAll(required)) {
                offenders.add(module + "（k8s 缺 " + difference(required, k8sEnv) + "）");
            }
        }
        assertEquals(List.of(), offenders, "部署面没有供给 Redis 连接信息：" + offenders);
    }

    // ------------------------------------------------------------------ helpers

    private static boolean declaresStarter(String module) throws IOException {
        Path pom = ROOT.resolve("amz-service").resolve(module).resolve("pom.xml");
        return Files.readString(pom, StandardCharsets.UTF_8).contains("<artifactId>" + STARTER_ARTIFACT + "</artifactId>");
    }

    private static Set<String> difference(Set<String> expected, Set<String> actual) {
        Set<String> missing = new java.util.TreeSet<>(expected);
        missing.removeAll(actual);
        return missing;
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

    private static Set<String> environmentKeys(Object environment) {
        Set<String> keys = new java.util.TreeSet<>();
        if (!(environment instanceof List<?> list)) {
            return keys;
        }
        for (Object item : list) {
            String entry = String.valueOf(item);
            int eq = entry.indexOf('=');
            keys.add(eq < 0 ? entry : entry.substring(0, eq));
        }
        return keys;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Set<String>> kubernetesEnvironments() throws IOException {
        Path dir = ROOT.resolve("k8s/services");
        Map<String, Set<String>> result = new LinkedHashMap<>();
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

    @SuppressWarnings("unchecked")
    private static Set<String> deploymentEnvironmentKeys(Map<String, Object> deployment) {
        Set<String> keys = new java.util.TreeSet<>();
        Map<String, Object> spec = (Map<String, Object>) deployment.get("spec");
        Map<String, Object> template = (Map<String, Object>) spec.get("template");
        Map<String, Object> podSpec = (Map<String, Object>) template.get("spec");
        for (Object container : (List<Object>) podSpec.getOrDefault("containers", List.of())) {
            Map<String, Object> c = (Map<String, Object>) container;
            Object env = c.get("env");
            if (env instanceof List<?> list) {
                for (Object item : list) {
                    Map<String, Object> entry = (Map<String, Object>) item;
                    keys.add(String.valueOf(entry.get("name")));
                }
            }
        }
        return keys;
    }

    private static Path findRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getParent()) {
            if (Files.isRegularFile(current.resolve("docker-compose.yml"))
                    && Files.isDirectory(current.resolve("k8s"))) {
                return current;
            }
        }
        throw new IllegalStateException("无法定位仓库根目录：" + System.getProperty("user.dir"));
    }
}