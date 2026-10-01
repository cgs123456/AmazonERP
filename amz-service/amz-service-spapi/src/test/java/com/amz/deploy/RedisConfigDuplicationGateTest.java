package com.amz.deploy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重复 RedisConfig 的漂移门禁。
 * <p>
 * <b>实测发现</b>：4 个服务各持有一份<b>字节完全相同</b>的
 * {@code com.amz.config.RedisConfig}。
 * <p>
 * 为什么不是"合并成一份公共 @Configuration"：amz-common 位于扫描根包 {@code com.amz} 下，
 * 公共配置会被 16 个服务全部扫到 —— 等于给网关（WebFlux）和刻意沿用 Boot 默认模板的
 * procurement / spapi 凭空加或改 bean；换掉 value 序列化器还会让既有缓存读不出来。
 * 那不是去重，是跨服务改 bean 拓扑。
 * <p>
 * 所以合并的是重复的<b>配方</b>（{@code com.amz.redis.RedisTemplates}，唯一实现），
 * bean 声明仍留在各服务里，名字/类型/生效范围与合并前逐字节一致。
 * 本门禁守两件事：配方不得在副本里被重新实现；副本之间不得分叉。
 */
@DisplayName("RedisConfig 副本：配方只有一份实现，副本之间不得分叉")
class RedisConfigDuplicationGateTest {

    private static final Path ROOT = repoRoot();

    @Test
    @DisplayName("各服务的 RedisConfig 必须字节一致，且副本数量变化必须被看到")
    void duplicatesMustStayIdentical() throws IOException {
        List<Path> copies = new ArrayList<>();
        // 不早设浅 depth：src/main/java/com/amz/config 已有 5 层，depth 小了会扫到 0 个文件
        // 而 0 命中在本测试里是显式失败（见下），不是"通过"。
        try (var walk = Files.walk(ROOT.resolve("amz-service"))) {
            walk.filter(p -> p.getFileName().toString().equals("RedisConfig.java"))
                    .filter(p -> p.toString().replace('\\', '/').contains("/src/main/java/"))
                    .sorted()
                    .forEach(copies::add);
        }

        assertTrue(copies.size() >= 2,
                "扫描到 " + copies.size() + " 份 RedisConfig：若已完成上收，请连同本门禁一并删除");

        List<String> digests = copies.stream().map(RedisConfigDuplicationGateTest::sha256).toList();
        for (Path copy : copies) {
            String body = read(copy);
            assertTrue(body.contains("RedisTemplates.jsonStringValues("),
                    "副本没有复用共享配方，序列化细节又开始各写各的：" + copy);
            assertFalse(body.contains("GenericJackson2JsonRedisSerializer")
                            || body.contains("setValueSerializer"),
                    "配方只能在 amz-common 的 RedisTemplates 里出现一次，副本里不得重新实现：" + copy);
        }

        String first = digests.get(0);
        for (int i = 1; i < digests.size(); i++) {
            assertEquals(first, digests.get(i),
                    "RedisConfig 副本分叉：" + copies.get(0) + " 与 " + copies.get(i)
                            + " 不一致 —— 要么同步改，要么把差异做成可配置项并说明为什么");
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256(Path path) {
        try {
            // 归一化行尾，避免 CRLF/LF 造成假分叉
            String normalized = Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        for (int depth = 0; current != null && depth < 8; depth++, current = current.getParent()) {
            if (Files.isRegularFile(current.resolve("docker-compose.yml"))
                    && Files.isDirectory(current.resolve("amz-service"))) {
                return current;
            }
        }
        throw new IllegalStateException("无法定位仓库根目录：" + System.getProperty("user.dir"));
    }
}
