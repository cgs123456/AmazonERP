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
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-55 / P0-56：Nacos 地址的<b>双向契约</b>（代码侧默认值 × 部署侧变量名）。
 * <p>
 * <b>实测发现的缺陷链（2026-09-24 第 53 轮）</b>：
 * <ol>
 *   <li>16 份 {@code bootstrap.yml} + {@code amz-common/seata-default.yml} 把默认值写成
 *       {@code ${NACOS_ADDR:<第三方公网地址>:8848}}——服务在<b>没有注入该变量时</b>会去连一台不属于使用者的机器；
 *       <b>2026-10-09 更新</b>：那 16 份 {@code bootstrap.yml} 已确认是死配置（依赖树里没有
 *       {@code spring-cloud-starter-bootstrap}，Spring Cloud 2020+ 默认不读该文件），已全部删除；
 *       本测试改为盯<b>真正生效</b>的 16 份 {@code application.yml}，覆盖面不变、指向变准。</li>
 *   <li>{@code docker-compose.yml} 与 15 份 k8s service 清单注入的变量名是 {@code NACOS_SERVER_ADDR}，
 *       而代码读的是 {@code NACOS_ADDR}——<b>注入了也读不到</b>，于是 16 个服务全部走默认值；</li>
 *   <li>{@code .env.example} 根本没有 {@code NACOS_ADDR} 这一项——按模板填出来的 {@code .env}
 *       不会注入，Compose 路径于是必然触发第 1 条。</li>
 * </ol>
 * 三条叠加的后果：<b>两条部署路径都拿不到正确的注册中心地址</b>，且失败方式最坏——不是报错，
 * 而是静默连向陌生主机。本测试把三条逐一钉死。
 * <p>
 * <b>证据类型 E1（自证）</b>：断言对象是本仓库的配置文本，不起 Spring 上下文、不连 Nacos。
 * 它证明的是「配置一致 + 默认不指向公网」，<b>不</b>证明 Nacos 可达（那需要 A5 联调）。
 */
@DisplayName("P0-55/P0-56 Nacos 地址契约（默认不指向公网 + 部署清单变量名与代码一致）")
class NacosAddressContractTest {

    /** 1 个网关 + 15 个业务服务 = 16 个需要注册中心的进程。 */
    private static final int SERVICE_COUNT = 16;

    /** 16 份 application.yml（生效的那份）+ amz-common 的 seata-default.yml。 */
    private static final int NACOS_CONFIG_FILES = SERVICE_COUNT + 1;

    private static final Pattern IPV4 = Pattern.compile("\\b(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\b");

    private static final String LEGACY_VAR = "NACOS_SERVER_ADDR";
    private static final String CODE_VAR = "NACOS_ADDR";

    @Test
    @DisplayName("Spring 配置里 Nacos 的默认地址不得是公网 IP，且必须读 NACOS_ADDR")
    void nacosDefaultsAreLocalAndReadTheCodeVariable() {
        List<Path> files = nacosConfigFiles();
        assertEquals(NACOS_CONFIG_FILES, files.size(),
                "Nacos 配置文件数量变化：新增服务必须同步补 application.yml，且本断言需同步更新");
        for (Path file : files) {
            String text = read(file);
            assertTrue(text.contains("${" + CODE_VAR + ":"),
                    "Nacos 地址必须由 " + CODE_VAR + " 注入（读到别的键等于永远读不到）：" + file);
            Optional<String> publicIp = firstPublicIpv4(text);
            assertFalse(publicIp.isPresent(),
                    "默认值不得指向公网 IP（未注入时会连向不属于使用者的主机）：" + file
                            + " -> " + publicIp.orElse(""));
        }
    }

    @Test
    @DisplayName("部署清单不得再出现 NACOS_SERVER_ADDR（代码读的是 NACOS_ADDR，注入错名等于没注入）")
    void manifestsDoNotUseLegacyVariableName() {
        for (Path file : deploymentManifests()) {
            String text = read(file);
            assertFalse(text.contains(LEGACY_VAR),
                    "部署清单仍在用 " + LEGACY_VAR + "，而代码读 " + CODE_VAR + "——注入无效且静默回落默认值：" + file);
        }
    }

    @Test
    @DisplayName("docker-compose 必须为 16 个服务逐段注入 NACOS_ADDR")
    void composeInjectsNacosAddrPerService() {
        Path compose = repoRoot().resolve("docker-compose.yml");
        assertTrue(Files.exists(compose), "缺少 docker-compose.yml");
        String text = read(compose);
        int injected = countOccurrences(text, "- " + CODE_VAR + "=");
        assertEquals(SERVICE_COUNT, injected,
                "Compose 注入 " + CODE_VAR + " 的服务段数不符预期（漏一个服务就回落默认地址）");
        assertTrue(text.contains(CODE_VAR + "=nacos:8848"),
                "Compose 应把 Nacos 指向 compose 网络内的服务名 nacos:8848");
    }

    @Test
    @DisplayName("k8s 的每一份 service 清单都要引用 NACOS_ADDR，且 ConfigMap 必须定义该键")
    void k8sServicesReferenceNacosAddr() throws IOException {
        Path services = repoRoot().resolve("k8s").resolve("services");
        assertTrue(Files.isDirectory(services), "缺少 k8s/services 目录");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(services)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".yaml"))
                    .forEach(files::add);
        }
        assertEquals(SERVICE_COUNT, files.size(), "k8s service 清单数量变化，需同步更新本断言");
        for (Path file : files) {
            String text = read(file);
            assertTrue(text.contains(CODE_VAR),
                    "k8s 清单未注入 " + CODE_VAR + "，该服务会回落默认地址：" + file.getFileName());
        }
        String configMap = read(repoRoot().resolve("k8s").resolve("configmap.yaml"));
        assertTrue(configMap.contains(CODE_VAR + ":"),
                "ConfigMap 必须定义 " + CODE_VAR + " 键，否则 Pod 启动即取不到值");
    }

    @Test
    @DisplayName(".env.example 必须声明 NACOS_ADDR（否则按模板填的 .env 不会注入）")
    void envTemplateDeclaresNacosAddr() {
        Path env = repoRoot().resolve(".env.example");
        assertTrue(Files.exists(env), "缺少 .env.example");
        String text = read(env);
        assertTrue(text.contains(CODE_VAR + "="),
                ".env.example 缺少 " + CODE_VAR + "：用户复制模板后不会注入，Compose 路径必然回落默认地址");
    }

    // ------------------------------------------------------------------ helpers

    private static List<Path> nacosConfigFiles() {
        List<Path> files = new ArrayList<>();
        Path root = repoRoot();
        for (String relative : List.of(
                "amz-gateway/src/main/resources/application.yml",
                "amz-common/src/main/resources/seata-default.yml")) {
            Path p = root.resolve(relative);
            assertTrue(Files.exists(p), "缺少配置文件（路径失效会让断言假通过）：" + relative);
            files.add(p);
        }
        Path services = root.resolve("amz-service");
        try (Stream<Path> walk = Files.walk(services)) {
            walk.filter(Files::isRegularFile)
                    .filter(NacosAddressContractTest::notBuildOutput)
                    .filter(p -> p.getFileName().toString().equals("application.yml"))
                    .forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }

    private static List<Path> deploymentManifests() {
        List<Path> files = new ArrayList<>();
        Path root = repoRoot();
        for (String relative : List.of("docker-compose.yml", ".env.example", "k8s/configmap.yaml")) {
            Path p = root.resolve(relative);
            assertTrue(Files.exists(p), "缺少部署清单：" + relative);
            files.add(p);
        }
        try (Stream<Path> walk = Files.walk(root.resolve("k8s"))) {
            walk.filter(Files::isRegularFile)
                    .filter(NacosAddressContractTest::notBuildOutput)
                    .filter(p -> p.toString().endsWith(".yaml"))
                    .forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }

    /** 排除构建产物目录：target 下的资源副本会让「文件数」断言失真。 */
    private static boolean notBuildOutput(Path path) {
        return !path.toString().replace('\\', '/').contains("/target/");
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

    private static Optional<String> firstPublicIpv4(String text) {
        Matcher m = IPV4.matcher(text);
        while (m.find()) {
            int a = Integer.parseInt(m.group(1));
            int b = Integer.parseInt(m.group(2));
            if (a == 10 || a == 127 || a == 0 || a >= 224) {
                continue; // 私网 10/8、环回、0.0.0.0、组播与保留段
            }
            if (a == 172 && b >= 16 && b <= 31) {
                continue; // 私网 172.16/12
            }
            if (a == 192 && b == 168) {
                continue; // 私网 192.168/16
            }
            if (a == 169 && b == 254) {
                continue; // link-local
            }
            return Optional.of(m.group());
        }
        return Optional.empty();
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
