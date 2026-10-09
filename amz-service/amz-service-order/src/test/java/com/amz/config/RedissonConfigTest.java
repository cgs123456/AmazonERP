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
 * Task 9：Redis / Redisson 配置基线（P0-31）——本模块不再自带 Redisson 配置。
 * <p>
 * <b>为什么删而不是改：</b>原 {@code RedissonConfig} 读的是 {@code ${spring.redis.host:公网 Redis 地址（P0-31，旧值见 git 历史）}}——
 * 一个<b>第三方公网地址</b>，且该键在 Spring Boot 3 下已改名为 {@code spring.data.redis.*}，
 * 全仓无任何 yml / 环境变量能覆盖它（实测 45,292 ms 后抛 {@code RedisConnectionException}）。
 * 更关键的是：本模块 {@code RedissonClient} <b>自始至终没有被任何代码使用</b>
 * （{@code OrderServiceImpl} 只有 import 与字段），却让每次启动都去连一次不可达的公网 Redis。
 * 零消费者的依赖不该留，因此处置是<b>删除</b>而非「改成读 spring.data.redis」。
 * <p>
 * 幂等去重真正依赖的是 {@code RedisTemplate}（{@code OrderServiceImpl} 的 {@code setIfAbsent}），
 * 走 {@code spring.data.redis.*}。2026-10-09 起 {@code redisson-spring-boot-starter} 已从本模块
 * 整体移除：Redisson 官方尚未支持 Spring Boot 4（最新 3.50.0 仍引用 Boot 3 已迁移的
 * {@code org.springframework.boot.autoconfigure.data.redis.RedisProperties}，prod 启动即
 * {@code ClassNotFoundException}，容器实测），且本模块零消费者——配置来源只剩
 * {@code spring.data.redis.*} 一处。
 * <p>
 * <b>证据类型 E1（自证）</b>：断言对象是本模块源码与配置文本，不连 Redis、不起 Spring 上下文。
 * 它防的是复发，不是「证明线上 Redis 通」。
 */
@DisplayName("Task 9 Redis/Redisson 配置基线（order：删除零消费者 Redisson 配置，P0-31）")
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
                "RedissonConfig 不得复活：它曾把默认值指向第三方公网 Redis，且与 starter 的自动装配形成双配置源");
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
        assertTrue(text.contains("redis:"), "本模块依赖 Redis（幂等去重），配置里必须有 redis 段");
        for (String line : text.split("\n", -1)) {
            assertFalse(line.equals("  redis:"),
                    "application.yml 出现顶层 spring.redis 块（2 空格缩进的 redis:）；"
                            + "唯一配置源必须是 spring.data.redis.*，否则 starter 读不到");
        }
    }

    @Test
    @DisplayName("OrderServiceImpl 不再持有未使用的 RedissonClient（零消费者依赖不得回潮）")
    void orderServiceHasNoUnusedRedissonDependency() {
        Path impl = MAIN_JAVA.resolve("com/amz/service/impl/OrderServiceImpl.java");
        assertTrue(Files.exists(impl), "缺少 OrderServiceImpl，无法断言");
        String text = read(impl);
        assertFalse(text.contains("RedissonClient"),
                "OrderServiceImpl 曾 import 并注入 RedissonClient 却从未使用；"
                        + "这种零消费者依赖会把不可达的公网 Redis 变成启动期故障，不得回潮");
        assertTrue(text.contains("RedisTemplate"), "幂等去重依赖 RedisTemplate，删除需显式评估");
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