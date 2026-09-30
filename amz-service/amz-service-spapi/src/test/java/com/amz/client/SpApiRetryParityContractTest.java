package com.amz.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SP-API 退避重试的四处实现必须行为一致：429 与 5xx 都要退避重试。
 * <p>
 * <b>实测发现的缺陷（2026-09-30 代码审查 High）</b>：同一套 retry 循环在四个类里各抄了一份，
 * 只有 {@code OrdersClient} 带 {@code 5xx} 分支。SP-API 的 503 抖动是常态，于是
 * 同一次数据同步里<b>订单侧自愈、库存/Feeds/网关路径侧把 5xx 当终态返回</b>——
 * 表现为"订单好了但库存是空的"这类难以归因的半成功。
 * <p>
 * 这里用源码级契约而不是 mock 一次 HTTP：四条路径共用 1s/2s/4s 的真实退避，
 * 逐条做行为测试会把套件拖慢几十秒；而漂移点恰恰是"某个类少了这条分支"，
 * 源码级检查直接命中且不受退避时长影响。
 */
@DisplayName("SP-API 重试契约：429 与 5xx 在四处实现里都要退避重试")
class SpApiRetryParityContractTest {

    private static final Path CLIENT_DIR = findRepoRoot()
            .resolve("amz-service/amz-service-spapi/src/main/java/com/amz/client");

    private static final List<String> RETRY_LOOPS = List.of(
            "OrdersClient.java", "FbaInventoryClient.java", "FeedsClient.java", "SpApiGateway.java");

    /**
     * 429 → 按指数退避 sleep 后 continue。
     * 允许两种取值写法（直接 statusCode() 或先取 int code），并要求窗口内确实出现
     * {@code 1L << attempt} 的退避量——否则可能匹配到相邻分支的 sleep 而假绿。
     */
    private static final Pattern RATE_LIMIT_RETRY = Pattern.compile(
            "(?:statusCode\\(\\)|code)\\s*==\\s*429\\s*\\)[\\s\\S]{0,1200}?"
                    + "1L << attempt[\\s\\S]{0,400}?sleep\\([\\s\\S]{0,80}?\\);\\s*continue;");

    /** 5xx → 退避 + continue（变量名可能是 code/status，分支条件必须覆盖 500..599）。 */
    private static final Pattern SERVER_ERROR_RETRY =
            Pattern.compile(">=\\s*500\\s*&&\\s*\\w+\\s*<\\s*600\\s*\\)[\\s\\S]{0,400}?sleep\\([\\s\\S]{0,80}?\\);\\s*continue;");

    @Test
    @DisplayName("四处重试循环都在扫描范围内（空扫不算通过）")
    void allRetryLoopsArePresent() {
        List<String> missingFiles = RETRY_LOOPS.stream()
                .filter(name -> !Files.isRegularFile(CLIENT_DIR.resolve(name)))
                .toList();
        assertEquals(List.of(), missingFiles, "重试循环所在文件被移动或重命名，本契约需要同步更新");
    }

    @Test
    @DisplayName("四处实现都对 429 与 5xx 退避重试")
    void everyRetryLoopHandlesBoth429And5xx() throws IOException {
        for (String name : RETRY_LOOPS) {
            String source = Files.readString(CLIENT_DIR.resolve(name), StandardCharsets.UTF_8);
            assertTrue(RATE_LIMIT_RETRY.matcher(source).find(),
                    name + " 的 429 处理不再是「退避后重试」，可能把限流当终态");
            assertTrue(SERVER_ERROR_RETRY.matcher(source).find(),
                    name + " 缺少 5xx 退避重试分支：SP-API 503 抖动时该路径不会自愈，"
                            + "而同一次同步里的订单侧会 —— 半成功最难排查");
        }
    }

    @Test
    @DisplayName("四处都仍受同一重试上限约束，没有偷偷改成无限重试")
    void retryBoundsStayUniform() throws IOException {
        for (String name : RETRY_LOOPS) {
            String source = Files.readString(CLIENT_DIR.resolve(name), StandardCharsets.UTF_8);
            assertTrue(source.contains("attempt <= MAX_RETRIES"),
                    name + " 的重试上界写法变了：需确认仍然有界（无界退避会拖死调度线程）");
            assertTrue(source.contains("MAX_RETRIES = 3"),
                    name + " 的 MAX_RETRIES 不再是 3，需同步复核四处是否仍然一致");
        }
    }

    private static Path findRepoRoot() {
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
