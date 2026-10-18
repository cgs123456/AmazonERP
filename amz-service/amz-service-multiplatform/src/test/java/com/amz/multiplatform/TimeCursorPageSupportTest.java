package com.amz.multiplatform;

import com.amz.exception.InvalidParamException;
import com.amz.model.PlatformMessage;
import com.amz.result.PageRequest;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TimeCursorPageSupport} 的契约测试（§S33.98）。
 *
 * <p>该组件从 {@code MultiplatformServiceImpl} 与 {@code WebhookEventService}
 * 两份逐行相同的私有副本收敛而来，服务侧既有测试只<b>间接</b>覆盖它
 * （验证列表行为）。这里补直接覆盖：三条不变量一旦被改坏，
 * 服务侧测试未必全红，而它们分别对应「翻页静默漏行/重复」和「脏游标穿透到 SQL」。
 *
 * <ol>
 *   <li>游标必须是 {@code 时间|id} 两段；格式不对要点名是哪个列表坏了；</li>
 *   <li>排序键缺失时<b>不能</b>生成游标——否则调用方拿着一个假游标翻页；</li>
 *   <li>{@code before} 必须同时约束时间与 id，同一秒内不能重复或漏行。</li>
 * </ol>
 */
@DisplayName("时间复合游标：格式、载荷、键集条件")
class TimeCursorPageSupportTest {

    @BeforeAll
    static void initTableInfo() {
        // LambdaQueryWrapper 解析实体列依赖 mapper 注册时初始化的 TableInfo，
        // 单测里没有 mapper，手动注册（与既有分页契约测试同款做法）。
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PlatformMessage.class);
    }

    @Test
    @DisplayName("无游标时返回 null（第一页），不去解析空载荷")
    void nullWhenNoCursor() {
        assertNull(TimeCursorPageSupport.parse(PageRequest.first(10), "receive_time"));
        assertNull(TimeCursorPageSupport.parse(null, "receive_time"));
    }

    @Test
    @DisplayName("合法游标按 时间|id 解析")
    void parsesTimeAndId() {
        // 游标回传给客户端前要走 encodeCursor（base64 + 版本前缀），
        // payload() 才是解码后的载荷 —— 直接拿裸 payload 当 cursor 会被拒。
        PageRequest page = PageRequest.of(10, PageRequest.encodeCursor(
                TimeCursorPageSupport.payload(LocalDateTime.parse("2026-10-18T10:15:30"), 4242L)));

        TimeCursorPageSupport.TimeCursor cursor = TimeCursorPageSupport.parse(page, "receive_time");

        assertEquals(LocalDateTime.parse("2026-10-18T10:15:30"), cursor.time());
        assertEquals(4242L, cursor.id());
    }

    @Test
    @DisplayName("游标段数不对、时间或 id 无法解析时点名拒绝，不静默当成第一页")
    void malformedCursorRejected() {
        assertThrows(InvalidParamException.class,
                () -> TimeCursorPageSupport.parse(PageRequest.of(10, PageRequest.encodeCursor("only-one-part")), "receive_time"),
                "缺 | 分隔的游标不能被当成第一页——那会让调用方从头再翻一遍");
        assertThrows(InvalidParamException.class,
                () -> TimeCursorPageSupport.parse(PageRequest.of(10, PageRequest.encodeCursor("not-a-time|12")), "receive_time"));
        assertThrows(InvalidParamException.class,
                () -> TimeCursorPageSupport.parse(PageRequest.of(10, PageRequest.encodeCursor("2026-10-18T10:15:30|abc")), "receive_time"));

        // 错必须说清是哪个列表的游标，否则三个列表共用同一组件时无从定位
        InvalidParamException e = assertThrows(InvalidParamException.class,
                () -> TimeCursorPageSupport.parse(PageRequest.of(10, PageRequest.encodeCursor("broken")), "snapshot_time"));
        assertTrue(e.getMessage().contains("snapshot_time"),
                "拒绝理由要能看出是哪个列表，实际=" + e.getMessage());
    }

    @Test
    @DisplayName("排序键缺失时拒绝生成游标，不返回能翻页的假载荷")
    void payloadRequiresBothKeys() {
        assertThrows(InvalidParamException.class,
                () -> TimeCursorPageSupport.payload(LocalDateTime.now(), null),
                "缺 id 的游标会让同一秒内的行在翻页时重复出现");
        assertThrows(InvalidParamException.class,
                () -> TimeCursorPageSupport.payload(null, 1L));
    }

    /**
     * 键集条件必须同时约束时间与 id。
     * <p>只按 {@code time < cursor} 会在游标所在那一秒漏掉与游标同秒的多行；
     * 少了 {@code OR (time = cursor AND id < cursorId)} 这一支，同秒行就整秒消失。
     */
    @Test
    @DisplayName("before 生成 (time < ?) OR (time = ? AND id < ?)，同秒行不丢不重")
    void beforeConstrainsBothTimeAndId() {
        LambdaQueryWrapper<PlatformMessage> wrapper = new LambdaQueryWrapper<>();
        TimeCursorPageSupport.TimeCursor cursor =
                new TimeCursorPageSupport.TimeCursor(LocalDateTime.parse("2026-10-18T10:15:30"), 4242L);

        TimeCursorPageSupport.before(wrapper,
                PlatformMessage::getReceiveTime, PlatformMessage::getId, cursor);

        // 真实形态（实测）：WHERE ((receive_time < #{..}
        //   OR (receive_time = #{..} AND id < #{..})))
        //
        // 断言写法（§S33.100 修，有实测背书）：把 id 支换成 shop_id 列后 SQL 为
        // `... AND shop_id < #{..}`，它含子串 "id <"（旧断言蒙混过关），
        // 但不含 " AND id < "（shop_id 前是 "_" 不是空格）。故 id 支必须带空格写。
        String sql = wrapper.getCustomSqlSegment();
        assertTrue(sql.contains("receive_time <"), "必须约束早于游标时间的行，实际=" + sql);
        assertTrue(sql.contains("receive_time ="), "必须保留与游标同秒的行，实际=" + sql);
        assertTrue(sql.contains(" AND id < "),
                "同秒内必须再按 id 往前推；只写 id < 会被 shop_id < 这类同后缀列蒙混，实际=" + sql);
        assertTrue(sql.contains(" OR "), "同秒行靠 OR 分支兜住，实际=" + sql);
        // 三支齐备才构成完整的键集条件：少 OR、少任一支都等于只按时间翻页
        assertEquals(3, countClauses(sql), "应有 3 个子句，实际=" + sql);
    }

    /** 数 SQL 里的比较子句数量：< 或 = 出现一次算一支。 */
    private static int countClauses(String sql) {
        int n = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '<' || c == '=') {
                n++;
            }
        }
        return n;
    }
}
