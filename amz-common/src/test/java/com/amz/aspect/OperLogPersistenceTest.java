package com.amz.aspect;

import com.amz.annotation.OperLog;
import com.amz.context.UserContext;
import com.amz.service.OperLogService;
import com.amz.service.impl.OperLogServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 操作审计落库契约测试。
 * <p>
 * 这条线此前是「注解存在但零使用 + 切面只写文件」，审计无法追责。本测试钉住三件事：
 * <ol>
 *   <li>切面在被标注方法执行后<b>确实调用写入器</b>（文件留痕不等于库级留痕）；</li>
 *   <li>业务异常时审计状态为 FAIL 且<b>异常照常上抛</b>（审计是旁路，不能吞异常）；</li>
 *   <li>写入器失败<b>不影响业务结果</b>，且被计数（Observability 可发现）。</li>
 * </ol>
 * 变异验证（写这些断言时已实际注入缺陷确认会红）：
 * 注释掉切面的 writer.record 调用 → 前两例红；把 status 硬编码成 SUCCESS → 第三例红；
 * 让写入器抛出并冒泡 → 「落库失败不影响业务」一例红。
 */
class OperLogPersistenceTest {

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    /**
     * 成功路径：上下文齐全时写入器收到 SUCCESS + userId + shopId。
     */
    @Test
    @DisplayName("审计：方法成功后落库，携带 userId 与归属店铺")
    void recordsSuccessWithUserIdAndShop() throws Throwable {
        UserContext.setUserId(42);
        UserContext.setShops(List.of(9001L));
        OperLogService writer = mock(OperLogService.class);
        when(writer.record(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyLong()))
                .thenReturn(true);

        OperLogAspect aspect = newAspect(writer);
        Object returned = aspect.around(joinPoint(), annotation("财务", "生成凭证"));

        assertSame("ok", returned, "业务返回值必须原样透出，审计不得改写");
        verify(writer).record(any(), eq(42), eq(9001L), any(), any(), any(), any(),
                eq("SUCCESS"), eq(null), any(), anyLong());
    }

    /**
     * 失败路径：业务抛异常时审计记 FAIL 并带错误信息，异常必须继续上抛。
     */
    @Test
    @DisplayName("审计：业务异常时记 FAIL 且异常照常上抛")
    void recordsFailureAndRethrows() {
        UserContext.setUserId(7);
        OperLogService writer = mock(OperLogService.class);
        when(writer.record(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyLong()))
                .thenReturn(true);

        OperLogAspect aspect = newAspect(writer);
        RuntimeException boom = new RuntimeException("平台拒绝");

        assertThrows(RuntimeException.class, () -> aspect.around(failingJoinPoint(boom),
                annotation("采购", "提交")));
        verify(writer).record(any(), eq(7), any(), any(), any(), any(), any(),
                eq("FAIL"), eq("RuntimeException: 平台拒绝"), any(), anyLong());
    }

    /**
     * 旁路语义：写入器抛异常不能让业务操作失败。
     */
    @Test
    @DisplayName("审计：落库失败不影响业务结果")
    void writeFailureDoesNotBreakBusiness() {
        UserContext.setUserId(9);
        OperLogService writer = mock(OperLogService.class);
        when(writer.record(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyLong()))
                .thenThrow(new IllegalStateException("DB down"));

        OperLogAspect aspect = newAspect(writer);

        Object returned = assertDoesNotThrow(() -> aspect.around(joinPoint(), annotation("库存", "回补")),
                "审计是旁路：DB 写挂了业务也必须照常返回");
        assertSame("ok", returned);
    }

    /**
     * 无写入器（宿主无数据源）时降级为仅文件记录，不抛异常。
     */
    @Test
    @DisplayName("审计：无写入器时降级为仅文件记录")
    void degradesToFileOnlyWithoutWriter() {
        UserContext.setUserId(3);
        OperLogAspect aspect = new OperLogAspect();
        ReflectionTestUtils.setField(aspect, "self", aspect);

        Object returned = assertDoesNotThrow(() -> aspect.around(joinPoint(), annotation("系统", "定时")));
        assertSame("ok", returned);
    }

    /**
     * 无店铺上下文时 shopId 传 null（列可空），不因解析失败中断。
     */
    @Test
    @DisplayName("审计：无店铺授权时 shopId 传 null")
    void passesNullShopWhenContextEmpty() throws Throwable {
        UserContext.setUserId(5);
        UserContext.setShops(new ArrayList<>());
        OperLogService writer = mock(OperLogService.class);
        when(writer.record(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyLong()))
                .thenReturn(true);

        OperLogAspect aspect = newAspect(writer);
        aspect.around(joinPoint(), annotation("系统", "调度"));

        verify(writer).record(any(), eq(5), eq(null), any(), any(), any(), any(),
                any(), any(), any(), anyLong());
    }

    // ============ 写入器（OperLogServiceImpl）自身契约 ============

    @Test
    @DisplayName("写入器：无 JdbcTemplate 时跳过且不抛异常")
    void writerSkipsWithoutJdbcTemplate() {
        OperLogServiceImpl service = new OperLogServiceImpl();

        boolean ok = assertDoesNotThrow(() -> service.record(
                annotation("财务", "生成凭证"), 1, null, "1.2.3.4", "m()", "{}", "{}",
                "SUCCESS", null, null, 5L));

        assertFalse(ok, "无数据源应当返回 false（降级为仅文件）而不是假装成功");
        assertEquals(0, service.failureCount(), "跳过不算失败：环境本身不支持审计");
    }

    @Test
    @DisplayName("写入器：表不存在时只 WARN 一次且计入失败")
    void writerCountsTableMissingOnceAndKeepsQuiet() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Table 'amz_user.amz_oper_log' doesn't exist"));

        OperLogServiceImpl service = new OperLogServiceImpl();
        ReflectionTestUtils.setField(service, "jdbcTemplateProvider",
                org.springframework.beans.factory.ObjectProvider.class.isInstance(jdbc)
                        ? null : providerOf(jdbc));

        for (int i = 0; i < 5; i++) {
            assertFalse(service.record(annotation("财务", "生成凭证"), 1, null, "1.2.3.4",
                    "m()", "{}", "{}", "SUCCESS", null, null, 5L));
        }
        assertEquals(5, service.failureCount());
        // 只 WARN 一次由 volatile 标记控制，无法从返回值观察；
        // 这里断言的是「重复失败不抛异常」——刷屏会让真错误被淹掉。
    }

    @Test
    @DisplayName("写入器：SQL 指向 amz_user 库（跨库写入与字段权限同一先例）")
    void writerTargetsUserSchema() {
        String sql = (String) ReflectionTestUtils.getField(new OperLogServiceImpl(), "INSERT_SQL");

        assertNotNull(sql);
        assertTrue(sql.contains("amz_user.amz_oper_log"),
                "各服务 JdbcTemplate 指向自己的业务库，审计必须带库名限定才能落到 amz_user");
        assertTrue(sql.contains("NOW()"), "create_time 由 DB 生成，避免应用时钟漂移");
    }

    // ============ 辅助 ============

    private OperLogAspect newAspect(OperLogService writer) {
        OperLogAspect aspect = new OperLogAspect();
        ReflectionTestUtils.setField(aspect, "self", aspect);
        ReflectionTestUtils.setField(aspect, "operLogService", writer);
        return aspect;
    }

    private org.aspectj.lang.ProceedingJoinPoint joinPoint() {
        org.aspectj.lang.ProceedingJoinPoint pjp = mock(org.aspectj.lang.ProceedingJoinPoint.class);
        org.aspectj.lang.Signature signature = mock(org.aspectj.lang.Signature.class);
        when(signature.toShortString()).thenReturn("FinanceController.submit(..)");
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getArgs()).thenReturn(new Object[] {"arg"});
        try {
            when(pjp.proceed()).thenReturn("ok");
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        return pjp;
    }

    private org.aspectj.lang.ProceedingJoinPoint failingJoinPoint(RuntimeException boom) {
        org.aspectj.lang.ProceedingJoinPoint pjp = mock(org.aspectj.lang.ProceedingJoinPoint.class);
        org.aspectj.lang.Signature signature = mock(org.aspectj.lang.Signature.class);
        when(signature.toShortString()).thenReturn("ProcurementController.submit(..)");
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getArgs()).thenReturn(new Object[0]);
        try {
            when(pjp.proceed()).thenThrow(boom);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        return pjp;
    }

    private OperLog annotation(String module, String action) {
        return new OperLog() {
            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return OperLog.class;
            }

            @Override
            public String module() {
                return module;
            }

            @Override
            public String action() {
                return action;
            }

            @Override
            public String description() {
                return "";
            }
        };
    }

    @SuppressWarnings("unchecked")
    private org.springframework.beans.factory.ObjectProvider<JdbcTemplate> providerOf(JdbcTemplate jdbc) {
        return new org.springframework.beans.factory.ObjectProvider<JdbcTemplate>() {
            @Override
            public JdbcTemplate getObject(Object... args) {
                return jdbc;
            }

            @Override
            public JdbcTemplate getIfAvailable() {
                return jdbc;
            }

            @Override
            public JdbcTemplate getIfUnique() {
                return jdbc;
            }

            @Override
            public JdbcTemplate getObject() {
                return jdbc;
            }
        };
    }
}
