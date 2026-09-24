package com.amz.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-57：<b>运行模式</b>的双向契约（代码侧默认值 × 部署侧注入）。
 * <p>
 * <b>实测发现的缺陷（2026-09-25）</b>：部署清单（{@code docker-compose.yml} 与 16 份 k8s service 清单）
 * <b>完全没有</b> {@code SPRING_PROFILES_ACTIVE}（0 命中），而 8 个模块的 {@code application.yml}
 * 把默认值写成 {@code ${SPRING_PROFILES_ACTIVE:mock}}。两条叠加的真实后果：按现有清单部署，
 * <b>14 个 {@code @Profile("!mock")} 真实客户端被禁用</b>，改由 10 个模块里的 Mock 客户端返回
 * 样例数据，<b>且不报任何错</b>——这是本项目最危险的失败模式：ERP 会安静地给出假订单、
 * 假财务事件、假物流轨迹，而所有健康检查都是绿的。
 * <p>
 * 修复口径是 <b>fail-closed</b>：默认值改为 {@code prod}（缺凭证时显式失败，而不是给假数据），
 * 部署清单逐段显式注入。本测试把两侧钉死，使其不可回退。
 * <p>
 * <b>证据类型 E1（自证）</b>：断言对象是本仓库的配置文本与部署清单，不起 Spring 上下文、不连中间件。
 * 它证明「默认不落到 mock + 清单显式注入」，<b>不</b>证明真实客户端可用（那需要 A5 联调）。
 */
@DisplayName("P0-57 profile contract: default must not fall back to mock")
class ProfileActivationContractTest {

    /** 1 gateway + 15 business services = 16 Spring processes. */
    private static final int SERVICE_COUNT = 16;

    /** modules containing @Profile("mock"). */
    private static final int MOCK_CAPABLE_MODULES = 10;

    /** of those, modules declaring a spring.profiles.active default. */
    private static final int MODULES_WITH_ACTIVE_DEFAULT = 8;

    private static final String VAR = "SPRING_PROFILES_ACTIVE";

    private static final Pattern MOCK_PROFILE = Pattern.compile("@Profile\\(\"mock\"\\)");
    private static final Pattern ACTIVE_LINE = Pattern.compile("(?m)^\\s*active:\\s*(\\S+)\\s*$");

    @Test
    @DisplayName("modules with mock clients must not default to mock")
    void modulesWithMockClientsDoNotDefaultToMock() {
        Set<String> modules = mockCapableModules();
        assertEquals(MOCK_CAPABLE_MODULES, modules.size(),
                "module count with @Profile(\"mock\") changed: review this assertion");

        int withActive = 0;
        for (String module : modules) {
            Path yml = repoRoot().resolve("amz-service").resolve(module)
                    .resolve("src/main/resources/application.yml");
            assertTrue(Files.exists(yml), "module missing application.yml: " + module);
            String text = read(yml);
            Matcher m = ACTIVE_LINE.matcher(text);
            if (!m.find()) {
                continue; // no active default -> empty profile -> mock never activates
            }
            withActive++;
            String value = m.group(1);
            assertTrue(value.contains("${" + VAR + ":prod}"),
                    "module " + module + " profile default is not prod (" + value + ")");
            assertFalse(value.contains(":mock}"),
                    "module " + module + " profile default still falls back to mock: " + value);
        }
        assertEquals(MODULES_WITH_ACTIVE_DEFAULT, withActive,
                "count of modules declaring an active default changed: confirm new ones default to prod");
    }

    @Test
    @DisplayName("docker-compose must inject SPRING_PROFILES_ACTIVE for all 16 Spring services, defaulting to prod")
    void composeDeclaresProfilePerService() {
        Path compose = repoRoot().resolve("docker-compose.yml");
        assertTrue(Files.exists(compose), "missing docker-compose.yml");
        String text = read(compose);
        int injected = countOccurrences(text, "- " + VAR + "=");
        assertEquals(SERVICE_COUNT, injected,
                "compose service count injecting " + VAR + " is not 16: a missing one may silently run mock data");
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("- " + VAR + "=")) {
                continue;
            }
            assertTrue(trimmed.contains("${" + VAR + ":-prod}"),
                    "compose entry must default to prod: " + trimmed);
            assertFalse(trimmed.contains("mock"), "compose entry mentions mock: " + trimmed);
        }
    }

    @Test
    @DisplayName("all 16 k8s manifests must read SPRING_PROFILES_ACTIVE from ConfigMap, and ConfigMap must be prod")
    void k8sManifestsDeclareProfile() throws IOException {
        List<Path> files = k8sServiceManifests();
        assertEquals(SERVICE_COUNT, files.size(),
                "k8s service manifest count changed: add manifests and update this assertion");
        for (Path file : files) {
            String text = read(file);
            assertTrue(text.contains(VAR), "k8s manifest lacks " + VAR + ": " + file.getFileName());
            assertTrue(text.contains("configMapKeyRef"),
                    "k8s manifest should read " + VAR + " via configMapKeyRef: " + file.getFileName());
            assertFalse(text.contains(VAR + ": mock") || text.contains(VAR + "=mock"),
                    "k8s manifest hardcodes " + VAR + " to mock: " + file.getFileName());
        }
        String configMap = read(repoRoot().resolve("k8s").resolve("configmap.yaml"));
        assertTrue(configMap.contains(VAR + ": \"prod\""),
                "ConfigMap must define " + VAR + " as \"prod\"");
    }

    @Test
    @DisplayName(".env.example must declare SPRING_PROFILES_ACTIVE=prod")
    void envTemplateDeclaresProfile() {
        Path env = repoRoot().resolve(".env.example");
        assertTrue(Files.exists(env), "missing .env.example");
        String text = read(env);
        assertTrue(text.contains(VAR + "=prod"),
                ".env.example lacks " + VAR + "=prod");
    }

    // ------------------------------------------------------------------ helpers

    private static Set<String> mockCapableModules() {
        Set<String> modules = new LinkedHashSet<>();
        Path services = repoRoot().resolve("amz-service");
        try (Stream<Path> walk = Files.walk(services)) {
            walk.filter(Files::isRegularFile)
                    .filter(ProfileActivationContractTest::notBuildOutput)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> p.toString().replace('\\', '/').contains("/src/main/"))
                    .filter(p -> MOCK_PROFILE.matcher(read(p)).find())
                    .forEach(p -> modules.add(moduleOf(p)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return modules;
    }

    private static String moduleOf(Path javaFile) {
        String normalized = javaFile.toString().replace('\\', '/');
        int idx = normalized.indexOf("/amz-service/");
        String rest = normalized.substring(idx + "/amz-service/".length());
        return rest.substring(0, rest.indexOf('/'));
    }

    private static List<Path> k8sServiceManifests() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(repoRoot().resolve("k8s").resolve("services"))) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".yaml"))
                    .forEach(files::add);
        }
        return files;
    }

    /** exclude build output: copies under target/ distort file-count assertions. */
    private static boolean notBuildOutput(Path path) {
        return !path.toString().replace('\\', '/').contains("/target/");
    }

    private static Path repoRoot() {
        Path cursor = Paths.get("").toAbsolutePath();
        while (cursor != null) {
            if (Files.exists(cursor.resolve("docker-compose.yml"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("repo root containing docker-compose.yml not found");
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int idx = text.indexOf(needle, from);
            if (idx < 0) {
                return count;
            }
            count++;
            from = idx + needle.length();
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
