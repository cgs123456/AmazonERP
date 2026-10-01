package com.amz.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 店铺越权校验去重后的行为与防再复制门禁。
 * <p>
 * <b>实测发现</b>：物流模块 6 个 Service 各有一份私有
 * {@code requireShopAllowed}，其中 5 份只抛错不留日志 —— 「有人越权访问别家店铺」
 * 这个安全事件在 6 个入口里有 5 个查不到，且口径调整必须同步改 6 份。
 * 现收敛为 {@link LogisticsShopGuard} 单一实现，行为沿用原共同部分 + 那份唯一的 warn。
 */
@DisplayName("物流店铺越权校验：单一实现、拒绝可见、不得再复制")
class LogisticsShopGuardTest {

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("跨店铺访问被拒，并且一定留下 warn（此前 6 份里有 5 份是静默的）")
    void crossShopAccessIsDeniedAndLogged() {
        UserContext.setUserId(42);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(1L));

        Logger logger = (Logger) LoggerFactory.getLogger(LogisticsShopGuard.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            CodeErrorException ex = assertThrows(CodeErrorException.class,
                    () -> LogisticsShopGuard.requireShopAllowed(2L, "入库单"));
            assertEquals("入库单不属于当前账号可操作的店铺", ex.getMessage());

            List<ILoggingEvent> warns = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .filter(e -> e.getFormattedMessage().contains("入库单"))
                    .toList();
            assertEquals(1, warns.size(), "越权拒绝必须可归因（含业务对象、用户、目标店铺）：" + warns);
            String message = warns.get(0).getFormattedMessage();
            // 逐字段按 "键=值" 断言：只查 contains("2") 是假绿 —— userId=42 里就带 2，
            // 把「目标店铺=2」整段删掉这条断言照样过。
            assertTrue(message.contains("userId=42") && message.contains("role=OPERATOR")
                            && message.contains("授权店铺=[1]") && message.contains("目标店铺=2"),
                    "日志要能一眼定位是谁、什么角色、授权了哪些店、碰了哪个店：" + message);
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("授权范围内与 ADMIN 放行；shopId 为 null 单独报「缺少店铺 ID」")
    void allowedAndMissingShopId() {
        UserContext.setUserId(42);
        UserContext.setShops(List.of(1L));
        assertDoesNotThrow(() -> LogisticsShopGuard.requireShopAllowed(1L, "出库单"));

        assertEquals("出库单缺少店铺 ID",
                assertThrows(CodeErrorException.class,
                        () -> LogisticsShopGuard.requireShopAllowed(null, "出库单")).getMessage());

        UserContext.clear();
        UserContext.setUserId(7);
        UserContext.setRole("ADMIN");
        assertDoesNotThrow(() -> LogisticsShopGuard.requireShopAllowed(999L, "仓库"));
    }

    @Test
    @DisplayName("防再复制：模块内不得再出现第二份 requireShopAllowed 实现")
    void noSecondImplementationMayReappear() {
        Path root = repoRoot().resolve("amz-service/amz-service-logistics/src/main/java");
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> offenders = walk
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> read(p).contains("private void requireShopAllowed("))
                    .toList();
            assertEquals(List.of(), offenders,
                    "又出现了私有的 requireShopAllowed 副本，请改用 LogisticsShopGuard");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
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
        throw new IllegalStateException("无法定位仓库根目录：" + System.getProperty("user.dir"));
    }
}
