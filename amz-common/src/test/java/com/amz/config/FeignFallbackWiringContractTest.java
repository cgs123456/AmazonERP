package com.amz.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feign 兜底必须真的接线：声明了 {@code fallbackFactory} 就得有能触发它的机制与依赖，
 * 并显式给出超时。
 * <p>
 * <b>实测发现的缺陷（2026-09-30 代码审查）</b>：全仓 20 个 {@code @FeignClient} 接口都声明了
 * {@code fallbackFactory}，但装配机制是逐模块配的，于是出现漂移——
 * {@code amz-service-finance} 既没有 {@code spring.cloud.openfeign.circuitbreaker.enabled}，
 * 也没有 Sentinel 的 {@code feign.sentinel.enabled}，pom 里连熔断实现依赖都没有。
 * Spring Cloud OpenFeign 在没有熔断装配器时<b>静默忽略</b> {@code fallbackFactory}：
 * 兜底工厂成为死代码，下游故障时异常直接抛给调用方，而不是它声称的降级值；
 * 同一模块的 Feign 超时也没配，走的是缺省 connect 10s / read 60s，而其余 6 个模块统一 3s/10s。
 * <p>
 * 这条漂移为什么危险：今天刚把 20 个 FallbackFactory 的日志改成带异常本体，
 * 但对 finance 来说那些日志永远不会打印——"我们有降级"是纸面事实。
 * 因此本契约把"声明兜底 ⇒ 必须有触发机制 + 有熔断实现依赖 + 有显式超时"钉住。
 */
@DisplayName("Feign 契约：声明 fallbackFactory 就必须接线熔断并显式配超时")
class FeignFallbackWiringContractTest {

    private static final Path ROOT = findRepoRoot();

    @Test
    @DisplayName("扫描确实覆盖到声明 fallbackFactory 的模块（防空扫假绿）")
    void scanFindsModulesWithFallbackFactories() throws IOException {
        assertEquals(7, modulesWithFallbackDeclared().size(),
                "声明 fallbackFactory 的模块数变了，需逐模块复核熔断装配是否齐全");
    }

    @Test
    @DisplayName("每个声明 fallbackFactory 的模块都启用了熔断或 Sentinel Feign，并具备实现依赖")
    void everyFallbackDeclarationHasAWorkingTrigger() throws IOException {
        for (String module : modulesWithFallbackDeclared()) {
            Path moduleDir = ROOT.resolve("amz-service").resolve(module);
            List<Map<String, Object>> configs = loadConfigs(moduleDir);
            String pom = Files.readString(moduleDir.resolve("pom.xml"), StandardCharsets.UTF_8);

            boolean sentinelFeign = pathEquals(configs, List.of("feign", "sentinel", "enabled"), "true");
            boolean circuitBreakerFlag =
                    pathEquals(configs, List.of("spring", "cloud", "openfeign", "circuitbreaker", "enabled"), "true");
            boolean hasCircuitBreakerImpl = pom.contains("spring-cloud-starter-circuitbreaker-")
                    || pom.contains("spring-cloud-starter-alibaba-sentinel");

            assertTrue(sentinelFeign || circuitBreakerFlag,
                    module + " 声明了 fallbackFactory 但没打开任何触发开关：Spring Cloud OpenFeign "
                            + "在没有熔断装配器时会静默忽略 fallbackFactory，兜底是死代码。"
                            + "需要 feign.sentinel.enabled=true 或 spring.cloud.openfeign.circuitbreaker.enabled=true");
            assertTrue(hasCircuitBreakerImpl,
                    module + " 打开了熔断开关但 classpath 没有熔断实现依赖，装配器同样建不出来");
        }
    }

    @Test
    @DisplayName("每个有 Feign 客户端的模块都显式给出连接与读取超时")
    void everyFeignModuleConfiguresTimeouts() throws IOException {
        for (String module : modulesWithFeignClients()) {
            List<Map<String, Object>> configs = loadConfigs(ROOT.resolve("amz-service").resolve(module));
            List<String> timeoutPath = List.of("feign", "client", "config", "default");
            Map<String, Object> defaults = dig(configs, timeoutPath);

            assertTrue(defaults.containsKey("connect-timeout") && defaults.containsKey("read-timeout"),
                    module + " 未配置 feign.client.config.default 超时，将退回缺省 connect 10s / read 60s："
                            + defaults);
        }
    }

    @Test
    @DisplayName("读取超时不得超过网关侧口径（10s），否则调用方早已返回错误而降级仍未触发")
    void readTimeoutStaysWithinGatewayBudget() throws IOException {
        for (String module : modulesWithFeignClients()) {
            Map<String, Object> defaults = dig(loadConfigs(ROOT.resolve("amz-service").resolve(module)),
                    List.of("feign", "client", "config", "default"));
            Object readTimeout = defaults.get("read-timeout");
            if (readTimeout instanceof Number) {
                assertTrue(((Number) readTimeout).intValue() <= 10000,
                        module + " 的 read-timeout=" + readTimeout + "ms 超过 10000ms 口径");
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> modulesWithFeignClients() throws IOException {
        List<String> modules = new ArrayList<>();
        for (Path module : listServiceModules()) {
            if (anyFileContains(module, "@FeignClient")) {
                modules.add(module.getFileName().toString());
            }
        }
        return modules;
    }

    private static List<String> modulesWithFallbackDeclared() throws IOException {
        List<String> modules = new ArrayList<>();
        for (Path module : listServiceModules()) {
            if (anyFileContains(module, "fallbackFactory")) {
                modules.add(module.getFileName().toString());
            }
        }
        return modules;
    }

    private static List<Path> listServiceModules() throws IOException {
        try (Stream<Path> children = Files.list(ROOT.resolve("amz-service"))) {
            return children.filter(Files::isDirectory).sorted().toList();
        }
    }

    private static boolean anyFileContains(Path moduleDir, String needle) throws IOException {
        Path src = moduleDir.resolve("src/main/java");
        if (!Files.isDirectory(src)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(src)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .anyMatch(p -> read(p).contains(needle));
        }
    }

    /** 合并模块 resources 下所有 yml（多文档按顺序合并），供属性路径查询。 */
    private static List<Map<String, Object>> loadConfigs(Path moduleDir) throws IOException {
        Path resources = moduleDir.resolve("src/main/resources");
        List<Map<String, Object>> docs = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(resources, 2)) {
            for (Path file : walk.filter(p -> p.getFileName().toString().endsWith(".yml")).sorted().toList()) {
                for (Object doc : new Yaml().loadAll(read(file))) {
                    if (doc instanceof Map) {
                        docs.add(castMap(doc));
                    }
                }
            }
        }
        assertTrue(!docs.isEmpty(), "模块没有任何 yml 配置可解析：" + moduleDir);
        return docs;
    }

    private static boolean pathEquals(List<Map<String, Object>> docs, List<String> path, String expected) {
        for (Map<String, Object> doc : docs) {
            Object value = digValue(doc, path);
            if (value != null && expected.equals(String.valueOf(value))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> dig(List<Map<String, Object>> docs, List<String> path) {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (Map<String, Object> doc : docs) {
            Object value = digValue(doc, path);
            if (value instanceof Map) {
                merged.putAll(castMap(value));
            }
        }
        return merged;
    }

    private static Object digValue(Map<String, Object> root, List<String> path) {
        Object current = root;
        for (String key : path) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = castMap(current).get(key);
        }
        return current;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static Path findRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getParent()) {
            if (Files.isDirectory(current.resolve("amz-service"))
                    && Files.isRegularFile(current.resolve("docker-compose.yml"))) {
                return current;
            }
        }
        throw new IllegalStateException("无法定位仓库根目录：" + System.getProperty("user.dir"));
    }
}
