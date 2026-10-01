package com.amz.context;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 宽松店铺校验（{@code isShopAllowed}）的「无列表即放行」分支必须可观测。
 * <p>
 * <b>实测发现的口径分裂</b>：同一语义在 ad 模块走
 * {@code isShopAllowedStrict}（fail-closed），在 customer / finance / logistics / ai 走
 * {@code isShopAllowed}（无身份 + 无店铺列表 → 放行）。定时任务依赖后者，所以行为不能改；
 * 但"因为没有上下文所以放行"这件事此前完全无声，越权面扩大时没有任何信号。
 * <p>
 * 本测试锁住的是<b>可见性</b>与<b>行为不变</b>两件事。
 */
@DisplayName("UserContext 宽松放行：行为不变，但必须留痕")
class UserContextLenientGrantTest {

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    @Test
    @DisplayName("带可信服务身份的放行：首次告警一次，计数逐次累加")
    void internalServiceGrantIsLoggedOnceAndCounted() {
        String key = "internal-service:probe-" + UUID.randomUUID();
        Logger logger = (Logger) LoggerFactory.getLogger(UserContext.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            UserContext.setInternalService(key.substring("internal-service:".length()));
            assertTrue(UserContext.isShopAllowed(123L), "定时任务/内部调用仍须放行（行为不变）");
            assertTrue(UserContext.isShopAllowed(456L));
            assertTrue(UserContext.isShopAllowed(789L));

            List<ILoggingEvent> warns = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .filter(e -> e.getFormattedMessage().contains(key))
                    .toList();
            assertEquals(1, warns.size(), "同一来源只告警一次：" + warns);
            assertEquals(3L, UserContext.noListGrantHits(key), "告警收敛，但命中次数必须可查");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("完全无身份也放行（不改行为），但同样计入命中次数")
    void noIdentityGrantIsCounted() {
        long before = UserContext.noListGrantHits("no-identity");

        assertTrue(UserContext.isShopAllowed(321L));
        assertTrue(UserContext.isShopAllowed(null),
                "无身份分支此前连 null shopId 都放行——本次只加可见性，不改行为");

        assertEquals(before + 2L, UserContext.noListGrantHits("no-identity"));
    }

    @Test
    @DisplayName("有用户身份时走严格口径：无 shops 一律拒绝（与宽松分支的差别就在这里）")
    void userIdentityUsesStrictPath() {
        UserContext.setUserId(7);
        assertFalse(UserContext.isShopAllowed(1L),
                "合法 JWT 缺 shops claim 不应被解释为「可访问所有店铺」");

        UserContext.setShops(List.of(2L));
        assertTrue(UserContext.isShopAllowed(2L));
        assertFalse(UserContext.isShopAllowed(1L));
    }
}
