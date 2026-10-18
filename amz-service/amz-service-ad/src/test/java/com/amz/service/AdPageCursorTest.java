package com.amz.service;

import com.amz.exception.InvalidParamException;
import com.amz.result.PageRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AdPageCursor} 的契约测试（§S33.101）。
 *
 * <p>这个组件收敛了三处原本各自零测试的私有副本
 * （{@code AdAutoRuleServiceImpl.parseCompositeCursor}、
 * {@code SearchTermServiceImpl.parseIntCursor}、{@code parseDateCursor}）。
 * 合并让守卫逻辑从「一份没人守」变成「三处共用」，所以必须把它欠的测试补上，
 * 否则合并只是把一个缺口放大三倍。
 *
 * <p>实测缺口：删掉 {@code id > 0} 守卫后，模块内 42 个相关测试全绿——没有一条
 * 钉住它。本测试即为此而写。
 */
@DisplayName("ad 分页游标：键与 id 的解析、拒绝与载荷")
class AdPageCursorTest {

    // ==================== parseInt ====================

    @Test
    @DisplayName("无游标返回 null（第一页）")
    void nullWhenNoCursor() {
        assertNull(AdPageCursor.parseInt(PageRequest.first(10), "priority"));
        assertNull(AdPageCursor.parseInt(null, "priority"));
    }

    @Test
    @DisplayName("合法 值|id 解析出键与 id")
    void parsesIntCursor() {
        PageRequest page = PageRequest.of(10, PageRequest.encodeCursor("7|4242"));

        AdPageCursor.IntCursor cursor = AdPageCursor.parseInt(page, "priority");

        assertEquals(7, cursor.key());
        assertEquals(4242L, cursor.id());
    }

    /**
     * {@code id > 0} 守卫：主键从 1 起，0 或负值只能是坏游标。
     * 放过它会让翻页条件退化成 {@code id < 0}（永远不命中），
     * 表现为「第二页开始永远是空的」——而调用方只会看到「没有更多数据」。
     */
    @Test
    @DisplayName("id 为 0 或负数时拒绝，不退化成永远空页")
    void rejectsNonPositiveId() {
        // 只列 id 段的坏值。键段（如 0|5 里的 priority=0）原实现不校验范围，
        // 这里不为它虚构一条守卫——测试要比实现宽，不能比实现严。
        for (String bad : new String[]{"7|0", "7|-1"}) {
            InvalidParamException e = assertThrows(InvalidParamException.class,
                    () -> AdPageCursor.parseInt(
                            PageRequest.of(10, PageRequest.encodeCursor(bad)), "priority"),
                    "应拒绝 " + bad);
            assertTrue(e.getMessage().contains("id"),
                    "理由要能看出是 id 不合法，实际=" + e.getMessage());
        }
    }

    @Test
    @DisplayName("段数不对、键或 id 非整数时点名拒绝，不静默当成第一页")
    void rejectsMalformedIntCursor() {
        assertThrows(InvalidParamException.class,
                () -> AdPageCursor.parseInt(PageRequest.of(10, PageRequest.encodeCursor("only-one")), "priority"),
                "缺 | 的游标不能被当成第一页");
        assertThrows(InvalidParamException.class,
                () -> AdPageCursor.parseInt(PageRequest.of(10, PageRequest.encodeCursor("abc|1")), "priority"));
        assertThrows(InvalidParamException.class,
                () -> AdPageCursor.parseInt(PageRequest.of(10, PageRequest.encodeCursor("7|xyz")), "priority"));
        // 拒绝要能看出是哪个列表（两个调用方共用：totalOrders / organicRank / priority）
        InvalidParamException e = assertThrows(InvalidParamException.class,
                () -> AdPageCursor.parseInt(PageRequest.of(10, PageRequest.encodeCursor("one")), "totalOrders"));
        assertTrue(e.getMessage().contains("totalOrders"),
                "理由要点名列表，实际=" + e.getMessage());
    }

    // ==================== parseDate ====================

    @Test
    @DisplayName("合法 yyyy-MM-dd|id 解析出日期与 id")
    void parsesDateCursor() {
        PageRequest page = PageRequest.of(10, PageRequest.encodeCursor("2026-10-18|4242"));

        AdPageCursor.DateCursor cursor = AdPageCursor.parseDate(page);

        assertEquals(LocalDate.of(2026, 10, 18), cursor.key());
        assertEquals(4242L, cursor.id());
    }

    @Test
    @DisplayName("日期游标的 id 同样必须 > 0")
    void rejectsNonPositiveIdInDateCursor() {
        assertThrows(InvalidParamException.class,
                () -> AdPageCursor.parseDate(PageRequest.of(10, PageRequest.encodeCursor("2026-10-18|0"))));
        assertThrows(InvalidParamException.class,
                () -> AdPageCursor.parseDate(PageRequest.of(10, PageRequest.encodeCursor("2026-10-18|-3"))));
    }

    @Test
    @DisplayName("日期格式错误时点名是 reportDate")
    void rejectsMalformedDateCursor() {
        InvalidParamException e = assertThrows(InvalidParamException.class,
                () -> AdPageCursor.parseDate(PageRequest.of(10, PageRequest.encodeCursor("2026/10/18|1"))));
        assertTrue(e.getMessage().contains("reportDate"),
                "理由要能看出是 reportDate 格式问题，实际=" + e.getMessage());

        assertThrows(InvalidParamException.class,
                () -> AdPageCursor.parseDate(PageRequest.of(10, PageRequest.encodeCursor("one"))));
        assertNull(AdPageCursor.parseDate(PageRequest.first(10)));
    }
}
