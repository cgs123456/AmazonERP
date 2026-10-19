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
 * 宽松店铺校验（{@code isShopAllowed}）的放行口径。
 * <p>
 * <b>历史（§S33.114 §3.2 之前）</b>：该方法的「无店铺列表即放行」分支同时被三种情况命中——
 * 定时任务、白名单内部调用、以及"上下文根本没建起来"。三者的安全含义完全不同，
 * 但行为上无从区分，所以公网端点一旦走到这里就等于对所有店铺放行。
 * <p>
 * <b>§S33.121 收紧（本节锁定新口径）</b>：实测确认定时任务全部显式
 * {@code setUserId(SYSTEM_USER_ID)} + {@code setShops(List.of(shopId))}，
 * 走的本来就是"有 userId"那一支，**没有一个是靠"谁都不是"放行的**。
 * 于是把无身份那一支从"放行"改为"拒绝"：
 * <ul>
 *   <li>有 userId → 委托严格校验（不变）</li>
 *   <li>无 userId 但带内部服务身份 → 放行（不变，白名单内部调用仍通）</li>
 *   <li><b>谁都不是 → 拒绝（本次改的行为）</b></li>
 * </ul>
 * 收益不是修一个漏洞（公网路径本来就到不了这里），而是让"鉴权链路有洞"从
 * 静默放行变成 fail-closed——将来任何新增的无上下文调用方会立刻失败，
 * 而不是悄悄获得全店访问权。
 */
@DisplayName("UserContext 宽松放行：无身份不再放行（§S33.121）")
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
    @DisplayName("完全无身份：不再放行（§S33.121 收紧），且不再计入放行命中")
    void noIdentityIsRejected() {
        assertFalse(UserContext.isShopAllowed(321L),
                "既无用户身份也无内部服务身份时，不得解释为「可访问所有店铺」");
        assertFalse(UserContext.isShopAllowed(null),
                "null shopId 在无身份时更不该放行");

        assertEquals(0L, UserContext.noListGrantHits("no-identity"),
                "收紧后不再走「无列表即放行」分支，该键不应再被累加");
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

    @Test
    @DisplayName("定时任务形态（有 userId 无 shops）仍放行——收紧不得让定时任务 403")
    void scheduledTaskShapeStillGranted() {
        // AmazonReturnSyncScheduler / SettlementSyncScheduler 的真实形态：
        // setUserId(SYSTEM_USER_ID) + setShops(List.of(shopId))。
        // 这里只验「有 userId 且 shops 含目标店」这一形状仍然通过，
        // 因为收紧改的是「谁都不是」那一支，不是这一支。
        UserContext.setUserId(0);
        UserContext.setShops(List.of(42L));
        assertTrue(UserContext.isShopAllowed(42L));
        assertFalse(UserContext.isShopAllowed(43L));
    }
}
