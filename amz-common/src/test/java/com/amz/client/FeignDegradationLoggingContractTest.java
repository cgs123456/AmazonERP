package com.amz.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Feign 降级必须可归因：fallback 记日志时要把异常本体交给 logger。
 * <p>
 * <b>实测发现的缺陷（2026-09-30）</b>：20 个 {@code *FallbackFactory} 的
 * {@code create(Throwable cause)} 一律写成
 * {@code log.warn("... degraded: cause={}", cause.getMessage())}。
 * 只有 message 意味着：
 * <ul>
 *   <li>丢失异常类型——{@code feign.codec.DecodeException}（契约不兼容）与
 *       {@code SocketTimeoutException}（对端慢）、401（凭据没透传）在日志里长得一样；</li>
 *   <li>message 可能为 null（NPE 类），日志退化成 {@code cause=null}；</li>
 *   <li>没有栈，定位不到是哪一个 Feign 方法、哪一行解码失败。</li>
 * </ul>
 * 正是这三条叠加，让 {@code Result} 反序列化失败在 20 个客户端 38 个方法上<b>全量</b>发生，
 * 却在日志里表现为"对端返回空数据"，两周无人察觉（见
 * {@code docs/superpowers/evidence/2026-09-30-p1-feign-result-decode-fix.md}）。
 * <p>
 * 本测试守的是可归因性，不改降级取值：返回值仍是各自的兜底值。
 */
@DisplayName("Feign fallback：降级日志必须带上异常本体，不能只留一句 message")
class FeignDegradationLoggingContractTest {

    /** 要求的写法：异常本体作为最后一个参数交给 logger（SLF4J 会打印类型与栈）。 */
    private static final Pattern LOG_WITH_THROWABLE = Pattern.compile(
            "log\\.\\w+\\([^;]*,\\s*cause\\s*\\)\\s*;");

    private static final Path ROOT = repoRoot();

    @Test
    @DisplayName("每个 FallbackFactory 都把 cause 交给 logger")
    void everyFallbackFactoryLogsTheThrowableItself() throws IOException {
        List<Path> factories = fallbackFactories();
        // 2026-10-03：ProductClientFallbackFactory 随其唯一调用方一起删除（订单金额改由消息本身
        // 携带，不再跨服务查一张已漂移的旧表），所以这里的基线从 20 降到 19。
        // 数量仍需钉死：新增 factory 不纳入本契约要红，无谓删除也要红。
        assertEquals(19, factories.size(),
                "FallbackFactory 数量变了（当前应为 19）：新增的要同步纳入本契约，删除的要更新断言");
        for (Path factory : factories) {
            String text = read(factory);
            assertTrue(LOG_WITH_THROWABLE.matcher(text).find(),
                    () -> factory + " 没有把异常本体交给 logger，应为 log.warn(\"...\", cause)");
        }
    }

    @Test
    @DisplayName("禁止回退成只记 cause.getMessage() 的日志语句")
    void noLogStatementCarriesOnlyTheCauseMessage() throws IOException {
        for (Path factory : fallbackFactories()) {
            List<String> lines = readLines(factory);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                boolean isLogStatement = line.startsWith("log.");
                if (isLogStatement && line.contains("cause.getMessage()") && !line.contains(", cause)")) {
                    fail(factory + ":" + (i + 1) + " 的降级日志只留了 message，异常类型与栈都会丢：" + line);
                }
            }
        }
    }

    @Test
    @DisplayName("降级文案点名被调用的服务，便于定位是哪条链路断了")
    void degradationMessageNamesTheTargetService() throws IOException {
        for (Path factory : fallbackFactories()) {
            Matcher logStatement = LOG_WITH_THROWABLE.matcher(read(factory));
            assertTrue(logStatement.find(), () -> factory + " 缺少带异常本体的降级日志");
            String statement = logStatement.group();
            assertTrue(statement.contains("amz-service-"),
                    () -> factory + " 的降级日志必须点名目标服务，实际：" + statement);
        }
    }

    private static List<Path> fallbackFactories() throws IOException {
        try (Stream<Path> walk = Files.walk(ROOT.resolve("amz-service"))) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith("FallbackFactory.java"))
                    .filter(path -> path.toString().replace('\\', '/').contains("/src/main/"))
                    .sorted()
                    .toList();
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> readLines(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
        throw new IllegalStateException("无法从 user.dir 定位仓库根目录：" + System.getProperty("user.dir"));
    }
}
