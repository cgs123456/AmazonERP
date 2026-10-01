package com.amz.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审单正则的有界编译缓存。
 * <p>
 * <b>实测发现</b>：{@code safeRegexMatch} 原先每匹配一次就 {@code Pattern.compile} 一次
 * —— 批量审单时同一规则正则会反复解析。缓存在这里是净收益，但规则由店铺管理员提供，
 * <b>无界</b>缓存等于给出一条内存放大通道，所以必须同时钉住三件事：
 * 有界、失败不进缓存、淘汰按访问序（否则热用的规则会被新规则挤掉，缓存形同虚设）。
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
        int max = OrderAuditServiceImpl.patternCacheMax();
        assertTrue(max <= 256, "上限本身就是这道防线的全部价值，实际：" + max);
        for (int i = 0; i < max * 3 + 8; i++) {
            assertNotNull(OrderAuditServiceImpl.compiledPattern("bounded-" + i + "\\d*"));
        }
        assertEquals(max, OrderAuditServiceImpl.patternCacheSize(),
                "无界缓存会把管理员输入的每条规则都留在内存里");
    }

    @Test
    @DisplayName("淘汰按访问序：反复使用的正则不会被一批新正则挤掉（插入序 FIFO 会在这里露馅）")
    void hotPatternSurvivesStreamOfNewPatterns() {
        int max = OrderAuditServiceImpl.patternCacheMax();
        String hot = "hot-(\\d+)";
        Pattern compiled = OrderAuditServiceImpl.compiledPattern(hot);
        // 灌到刚好满：hot 此时是"最早插入"的那一条
        for (int i = 0; i < max - 1; i++) {
            OrderAuditServiceImpl.compiledPattern("filler-" + i);
        }
        assertSame(compiled, OrderAuditServiceImpl.compiledPattern(hot),
                "填满之后 hot 仍在缓存里");
        // 这一句 get 才是判别点：访问序下 hot 变成"最近用过"，插入序下它仍是最老的。
        // 接下来每塞一条新的，访问序淘汰的是 filler，插入序淘汰的就是 hot。
        for (int round = 0; round < max; round++) {
            OrderAuditServiceImpl.compiledPattern("pusher-" + round);
            assertSame(compiled, OrderAuditServiceImpl.compiledPattern(hot),
                    "第 " + round + " 轮：批量审单里热用的规则必须留在缓存，"
                            + "被挤掉就会重编译出新实例（FIFO 正是在这里红）");
        }
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
