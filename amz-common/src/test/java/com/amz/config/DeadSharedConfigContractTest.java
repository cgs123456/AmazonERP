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
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 死共享配置的清理契约（2026-10-09 清理）。
 * <p>
 * 与 Seata 那份 {@code seata-default.yml} 同源的缺陷类型：<b>名字像 Spring 配置、但从来不会被加载</b>。
 * 这类文件比没有更危险——读配置的人会以为某项能力已经接上。
 * <ul>
 *   <li>{@code amz-common/src/main/resources/jwt-config.yml}：既不是 {@code application*.yml}，
 *       全仓也没有 {@code spring.config.import} 引用它，引用点 0。它想提供的默认值
 *       （secret-key 空 / issuer {@code amz-erp} / audience {@code amz-erp-client} /
 *       expire-time 86400000）在 {@code JwtUtil} 的 {@code @Value} 默认值里逐字存在，
 *       属于纯冗余 → 删除。</li>
 *   <li>16 份 {@code bootstrap.yml}（1 网关 + 15 服务）：依赖树里<b>没有</b>
 *       {@code spring-cloud-starter-bootstrap}，Spring Cloud 2020+ 默认不再读 {@code bootstrap.yml}，
 *       所以 {@code spring.cloud.nacos.config.*} 从未生效（P0-25(b)）；
 *       其中的 {@code spring.cloud.nacos.discovery.server-addr} 在各自 {@code application.yml} 里
 *       同样有一份 → 删除。</li>
 * </ul>
 * 本测试是"防回潮"：删掉的文件不能悄悄回来，删掉的能力也不能跟着丢。E1 自证（扫文本，不起上下文）。
 */
@DisplayName("死共享配置契约（jwt-config.yml / bootstrap.yml 已删除且能力未丢）")
class DeadSharedConfigContractTest {

    @Test
    @DisplayName("不得再存在任何 bootstrap.yml（没有 bootstrap starter 时它从不被加载）")
    void noBootstrapYmlAnywhere() throws IOException {
        List<Path> found = new ArrayList<>();
        Path root = repoRoot();
        for (Path module : List.of(root.resolve("amz-gateway"), root.resolve("amz-service"))) {
            if (!Files.isDirectory(module)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(module)) {
                walk.filter(Files::isRegularFile)
                        .filter(DeadSharedConfigContractTest::notBuildOutput)
                        .filter(p -> p.getFileName().toString().equals("bootstrap.yml"))
                        .forEach(found::add);
            }
        }
        assertTrue(found.isEmpty(),
                "bootstrap.yml 又出现了：" + found + "。它只在 spring-cloud-starter-bootstrap 存在时才有意义；"
                        + "当前依赖树里没有这个 starter（见 noBootstrapStarterOnAnyPom），所以它只是死配置。"
                        + "要恢复配置中心接入，请先加 starter，再同步本测试与 NacosAddressContractTest。");
    }

    @Test
    @DisplayName("bootstrap starter 仍然不在依赖里（它一旦被加入，bootstrap.yml 就该恢复）")
    void noBootstrapStarterOnAnyPom() throws IOException {
        List<Path> poms = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(repoRoot())) {
            walk.filter(Files::isRegularFile)
                    .filter(DeadSharedConfigContractTest::notBuildOutput)
                    .filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .forEach(poms::add);
        }
        assertFalse(poms.isEmpty(), "一个 pom.xml 都没扫到，扫描路径失效");
        for (Path pom : poms) {
            String text = read(pom);
            assertFalse(text.contains("spring-cloud-starter-bootstrap"),
                    "检测到 spring-cloud-starter-bootstrap（" + pom
                            + "）：bootstrap.yml 从此会生效，删掉的那 16 份必须按 Nacos 配置中心的真实需求重建，"
                            + "而不是让本测试继续绿。");
        }
    }

    @Test
    @DisplayName("jwt-config.yml 已删除（它的默认值在 JwtUtil 的 @Value 里逐字存在）")
    void jwtConfigYmlIsGoneAndDefaultsLiveInJwtUtil() throws IOException {
        Path dead = repoRoot().resolve("amz-common/src/main/resources/jwt-config.yml");
        assertFalse(Files.exists(dead),
                "jwt-config.yml 又出现了：" + dead + "。它不是 application*.yml、也没有被 spring.config.import 引用，"
                        + "从不参与属性解析；它的默认值与 JwtUtil 的 @Value 默认值重复，请二选一，别留双份事实源。");

        String jwtUtil = read(repoRoot().resolve(
                "amz-common/src/main/java/com/amz/util/JwtUtil.java"));
        for (String expected : List.of(
                "@Value(\"${jwt.secret-key:}\")",
                "@Value(\"${jwt.issuer:amz-erp}\")",
                "@Value(\"${jwt.audience:amz-erp-client}\")",
                "@Value(\"${jwt.expire-time:86400000}\")")) {
            assertTrue(jwtUtil.contains(expected),
                    "删掉 jwt-config.yml 的前提是 JwtUtil 自带默认值，但没找到 " + expected
                            + "；默认值断了，启动会直接失败。");
        }
    }

    @Test
    @DisplayName("Nacos 注册地址仍在真正生效的 application.yml 里（删除 bootstrap.yml 没把能力删掉）")
    void nacosDiscoveryAddressStillDeclaredInEveryApplicationYml() throws IOException {
        List<Path> apps = new ArrayList<>();
        apps.add(repoRoot().resolve("amz-gateway/src/main/resources/application.yml"));
        Path services = repoRoot().resolve("amz-service");
        try (Stream<Path> walk = Files.walk(services)) {
            walk.filter(Files::isRegularFile)
                    .filter(DeadSharedConfigContractTest::notBuildOutput)
                    .filter(p -> p.getFileName().toString().equals("application.yml"))
                    .forEach(apps::add);
        }
        assertTrue(apps.size() >= 16,
                "生效的 application.yml 少于 16 份（实际 " + apps.size() + "），扫描路径可能失效");
        for (Path app : apps) {
            assertTrue(read(app).contains("${NACOS_ADDR:"),
                    "application.yml 里没有 ${NACOS_ADDR:...}（" + app
                            + "）：删掉 bootstrap.yml 之后，Nacos 注册地址就只剩部署侧环境变量，"
                            + "未注入时会静默落到 Spring 默认值，而不是我们选的本地回环。");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static boolean notBuildOutput(Path path) {
        return !path.toString().replace('\\', '/').contains("/target/");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 从模块目录向上找仓库根（以 docker-compose.yml 为标志），避免依赖 CWD。 */
    private static Path repoRoot() {
        Path cursor = Paths.get("").toAbsolutePath();
        while (cursor != null) {
            if (Files.exists(cursor.resolve("docker-compose.yml"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("向上遍历未找到含 docker-compose.yml 的仓库根");
    }
}
