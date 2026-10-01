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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重复 RedisConfig 的漂移门禁。
 * <p>
 * <b>实测发现（2026-10-01 审查轮 Low）</b>：4 个服务各持有一份<b>字节完全相同</b>的
 * {@code com.amz.config.RedisConfig}。真正该做的是把它上收成 amz-common 的自动配置，
 * 但那会改变 16 个服务的 Bean 装配顺序，而本仓库当前环境无法逐个启动验证
 * （Nacos/MySQL 由另一套在跑的栈持有，不应写入）—— 因此这里不做合并，
 * 只把"改漏一份就悄悄分叉"这个真实风险变成构建期失败。
 * <p>
 * 一旦能在本环境跑通 4 个服务的启动冒烟，就把本测试连同 4 份副本一起删掉，
 * 换成 amz-common 里的单一自动配置。
 */
@DisplayName("RedisConfig 副本：要么完全相同，要么显式承认差异")
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
        String first = digests.get(0);
        for (int i = 1; i < digests.size(); i++) {
            assertEquals(first, digests.get(i),
                    "RedisConfig 副本分叉：" + copies.get(0) + " 与 " + copies.get(i)
                            + " 不一致 —— 要么同步改，要么把差异做成可配置项并说明为什么");
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
