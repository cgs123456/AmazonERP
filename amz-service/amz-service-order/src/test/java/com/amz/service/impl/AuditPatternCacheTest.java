package com.amz.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 审单正则的有界编译缓存。
 * <p>
 * <b>实测发现（2026-10-01 审查轮 Low）</b>：{@code safeRegexMatch} 每匹配一次就
 * {@code Pattern.compile} 一次 —— 批量审单时同一规则正则会反复解析。缓存在这里
 * 是净收益，但规则由店铺管理员提供，<b>无界</b>缓存等于给出一条内存放大通道，
 * 所以必须同时钉住"有界"和"失败不进缓存"两件事。
 */
@DisplayName("审单正则编译缓存：命中复用、上限封顶、语法错误不缓存")
class AuditPatternCacheTest {

    @Test
    @DisplayName("同一正则第二次拿到同一个 Pattern 实例（说明真的复用了编译结果）")
    void repeatedPatternIsReused() {
        Pattern first = OrderAuditServiceImpl.compiledPattern("\\d{3}-\\d{7}-\\d{7}");
        Pattern second = OrderAuditServiceImpl.compiledPattern("\\d{3}-\\d{7}-\\d{7}");
        assertSame(first, second);
        assertSame(second, OrderAuditServiceImpl.compiledPattern("\\d{3}-\\d{7}-\\d{7}"));
    }

    @Test
    @DisplayName("缓存有界：写入远超上限的不同正则后，规模停在上限")
    void cacheIsBounded() {
        for (int i = 0; i < 200; i++) {
            assertNotNull(OrderAuditServiceImpl.compiledPattern("bounded-" + i + "\\d*"));
        }
        assertEquals(64, OrderAuditServiceImpl.patternCacheSize(),
                "无界缓存会把管理员输入的每条规则都留在内存里");
    }

    @Test
    @DisplayName("语法错误仍然抛出（由调用方折成「无法判定」），且不会被缓存")
    void invalidPatternStillThrowsAndIsNotCached() {
        int before = OrderAuditServiceImpl.patternCacheSize();
        assertThrows(PatternSyntaxException.class,
                () -> OrderAuditServiceImpl.compiledPattern("[unclosed"));
        assertThrows(PatternSyntaxException.class,
                () -> OrderAuditServiceImpl.compiledPattern("[unclosed"));
        assertEquals(before, OrderAuditServiceImpl.patternCacheSize(),
                "失败的编译一旦进缓存，后续调用就再也拿不到正确异常了");
    }
}
