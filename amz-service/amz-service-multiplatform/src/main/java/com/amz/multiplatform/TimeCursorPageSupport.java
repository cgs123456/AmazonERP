package com.amz.multiplatform;

import com.amz.exception.InvalidParamException;
import com.amz.result.PageRequest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * 时间列 + 主键的复合游标分页（keyset）。
 *
 * <p>存在的理由：本模块三处列表都是同一形态——消息（{@code receive_time}）、
 * 库存快照（{@code snapshot_time}）、Webhook 事件（{@code create_time}）。
 * §S33.90 拆出 {@code WebhookEventService} 后，这套工具第一次出现<b>跨类</b>重复
 * （两份逐行相同的私有副本），按「抽象延迟到第二个真实用例」才在此收敛。
 *
 * <p><b>为什么必须是 {@code (time, id)} 复合游标</b>：时间列不唯一。
 * 同一秒内多行时，只按时间翻页会漏行或重复——消息与库存快照正是这种密度。
 * 只按 {@code id} 翻页也不行：那需要「按 id 排序」与「按时间排序」一致，
 * 而这三张表的时间都可能被批量回填成同一值。
 *
 * <p><b>为什么放在模块内而不是 {@code amz-common}</b>：{@link #before} 依赖
 * MyBatis-Plus 的 {@link LambdaQueryWrapper}，而 {@code amz-common} 对 mybatis
 * 是 optional 依赖且仓内无一处使用它——提升上去等于让 common 接受它一直
 * 刻意回避的依赖。消费方（两个 service）同在本模块，留在模块内即可。
 */
public final class TimeCursorPageSupport {

    private TimeCursorPageSupport() {
    }

    /** 时间列不唯一，所以游标必须带 id。 */
    public record TimeCursor(LocalDateTime time, long id) {
    }

    /**
     * 解析分页游标；格式必须是 {@code 时间|id}。
     *
     * @param field 字段名，只用于把「哪个列表的游标坏了」说清楚
     */
    public static TimeCursor parse(PageRequest page, String field) {
        if (page == null || !page.hasCursor()) {
            return null;
        }
        String[] parts = page.payload().split("\\|", -1);
        if (parts.length != 2) {
            throw new InvalidParamException("分页游标非法：" + field + " 游标格式应为 时间|id");
        }
        try {
            return new TimeCursor(LocalDateTime.parse(parts[0]), Long.parseLong(parts[1]));
        } catch (DateTimeParseException | NumberFormatException e) {
            throw new InvalidParamException("分页游标非法：" + field + " 时间或 id 无法解析");
        }
    }

    /** 生成游标载荷；排序键缺失说明列表查询本身有问题，不允许生成一个能翻页的假游标。 */
    public static String payload(LocalDateTime time, Long id) {
        if (time == null || id == null) {
            throw new InvalidParamException("分页游标生成失败：排序键缺失（time=" + time + ", id=" + id + "）");
        }
        return time.toString() + "|" + id;
    }

    /**
     * {@code (time, id) < (cursorTime, cursorId)}：先比较时间，同一时间戳内再按 id 往前推。
     */
    public static <T> void before(LambdaQueryWrapper<T> wrapper,
                                  SFunction<T, LocalDateTime> timeGet,
                                  SFunction<T, Long> idGet,
                                  TimeCursor cursor) {
        wrapper.and(q -> q.lt(timeGet, cursor.time())
                .or(inner -> inner.eq(timeGet, cursor.time()).lt(idGet, cursor.id())));
    }
}
