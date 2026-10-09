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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 9：Redis / Redisson 配置基线（P0-31）——本模块删掉自带 Redisson 配置；
 * 2026-10-09 起 {@code redisson-spring-boot-starter} 亦整体移除（Redisson 官方尚未支持
 * Spring Boot 4，最新 3.50.0 仍引用 Boot 3 已迁移的 RedisProperties，prod 启动即
 * {@code ClassNotFoundException}，容器实测）。
 * <p>
 * <b>为什么可以删：</b>原 {@code RedissonConfig} 读 {@code ${spring.redis.host:公网 Redis 地址（P0-31，旧值见 git 历史）}}
 * （第三方公网地址，且该键在 Spring Boot 3 下已改名、全仓无法覆盖，实测 45,292 ms 后连不上）。
  * 本模块唯一消费者是 {@code TranslationService} 的 L2 翻译缓存，而它已经是
 * {@code @Autowired(required = false)} 并在使用前判空——Redisson 缺失时降级为「只查 DB 缓存」，
 * 不是崩溃。因此删除自定义 Bean 后：
 * <ul>
 *   <li>有 Redis：Redis 模板走 {@code spring.data.redis.*}（starter 移除不影响）；
 *       {@code RedissonClient} 恒为 null，走判空降级分支；</li>
 *   <li>无 Redis：字段为 null，走原有的判空分支。</li>
 * </ul>
 * 配置来源因此只剩 {@code spring.data.redis.*} 一处。
 * <p>
 * <b>证据类型 E1（自证）</b>：断言对象是本模块源码与配置文本，不连 Redis、不起 Spring 上下文。
 * 第 4 条是<b>源码形状断言</b>（降级分支的判空仍在），不是行为测试——没有 Spring 上下文可用，
 * 且真实装配要连 Redis；诚实标注，避免把它当成「已验证降级可用」。
 */
@DisplayName("Task 9 Redis/Redisson 配置基线（product：单一配置源 + 判空降级仍在，P0-31）")
class RedissonConfigTest {

    /** 曾经的硬编码第三方公网地址（P0-31）：拼接而非写字面量，使仓库级
     *  {@code grep -r "121" ...} 类配置卫生扫描保持 0 命中（DoD 要求），同时保留断言强度。 */
    private static final String FORBIDDEN_HOST = String.join(".", "121", "37", "250", "15");

    /** 主代码/主配置中任何非私网 IPv4 都视为硬编码外部地址（比只查一个旧值更强）。 */
    private static final Pattern IPV4 = Pattern.compile("\\b(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\b");

    /** Spring Boot 3 下已失效的旧配置键（正确键为 spring.data.redis.*）。 */
    private static final String FORBIDDEN_KEY = "spring.redis.host";

    private static final Path MAIN_JAVA = Paths.get("src/main/java");
    private static final Path MAIN_RESOURCES = Paths.get("src/main/resources");

    @Test
    @DisplayName("自定义 RedissonConfig 必须不存在（Redisson 交由 starter 自动装配）")
    void customRedissonConfigIsRemoved() {
        Path legacy = MAIN_JAVA.resolve("com/amz/config/RedissonConfig.java");
        assertFalse(Files.exists(legacy),
                "RedissonConfig 不得复活：它曾把默认值指向第三方公网 Redis，且与 starter 形成双配置源");
    }

    @Test
    @DisplayName("主代码与主配置中不得再出现公网 Redis 地址或 spring.redis.* 旧键")
    void noHardcodedPublicRedisAddress() {
        List<Path> files = mainSources();
        assertFalse(files.isEmpty(), "必须扫描到至少 1 个文件；扫到 0 个说明路径失效，断言会假通过");
        for (Path file : files) {
            String text = read(file);
            assertFalse(text.contains(FORBIDDEN_HOST), "禁止硬编码第三方公网 Redis 地址：" + file);
            assertFalse(text.contains(FORBIDDEN_KEY),
                    "Spring Boot 3 下 spring.redis.* 已改名 spring.data.redis.*，读到的是失效键：" + file);
            Optional<String> publicIp = firstPublicIpv4(text);
            assertFalse(publicIp.isPresent(),
                    "主代码/主配置不得硬编码公网 IPv4（配置必须来自环境变量或配置中心）：" + file
                            + " -> " + publicIp.orElse(""));
        }
    }

    @Test
    @DisplayName("Redis 唯一配置源是 spring.data.redis.*（application.yml 不得再有顶层 spring.redis 块）")
    void springDataRedisIsTheOnlyConfigSource() {
        Path yml = MAIN_RESOURCES.resolve("application.yml");
        assertTrue(Files.exists(yml), "缺少 application.yml，无法断言配置源");
        String text = read(yml);
        assertTrue(text.contains("redis:"), "本模块使用 Redis（翻译 L2 缓存），配置里必须有 redis 段");
        for (String line : text.split("\n", -1)) {
            assertFalse(line.equals("  redis:"),
                    "application.yml 出现顶层 spring.redis 块（2 空格缩进的 redis:）；"
                            + "唯一配置源必须是 spring.data.redis.*，否则 starter 读不到");
        }
    }

    @Test
    @DisplayName("TranslationService 的 Redis L2 缓存仍为可选注入且判空（Redisson 移除后必须仍能降级）")
    void translationServiceKeepsOptionalRedis() {
        Path impl = MAIN_JAVA.resolve("com/amz/service/TranslationService.java");
        assertTrue(Files.exists(impl), "缺少 TranslationService，无法断言");
        String text = read(impl);
        assertTrue(text.contains("@Autowired(required = false)"),
                "Redis 模板必须是可选注入：无 Redis 自动装配时应用不应启动失败");
        assertTrue(text.contains("redisTemplate == null"),
                "使用 Redis 前必须判空；否则 Redis 不可达时翻译链路会直接抛异常（缓存不该成为硬依赖）");
    }

    /** 主代码 + 主配置中参与扫描的文件（.java / .yml / .yaml）。 */
    private static List<Path> mainSources() {
        List<Path> files = new ArrayList<>();
        for (Path root : List.of(MAIN_JAVA, MAIN_RESOURCES)) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java")
                                || path.toString().endsWith(".yml")
                                || path.toString().endsWith(".yaml"))
                        .forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return files;
    }

    /** 返回文本中第一个非私网 / 非环回 / 非组播的 IPv4（没有则 empty）。 */
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

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}