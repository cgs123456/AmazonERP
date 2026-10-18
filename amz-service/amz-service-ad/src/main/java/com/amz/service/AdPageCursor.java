package com.amz.service;

import com.amz.exception.InvalidParamException;
import com.amz.result.PageRequest;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 本模块的分页游标解析（§S33.101）。
 *
 * <p>收敛三处同形私有副本：
 * <ul>
 *   <li>{@code AdAutoRuleServiceImpl}：{@code parseCompositeCursor}，键为 int（priority）</li>
 *   <li>{@code SearchTermServiceImpl.parseIntCursor}，键为 int —— 与前者<b>逐字节同构</b>
 *       （同样的 {@code id > 0} 守卫、同样的两条异常消息），只是记录类型名不同</li>
 *   <li>{@code SearchTermServiceImpl.parseDateCursor}，键为 {@link LocalDate}（reportDate）</li>
 * </ul>
 * 三处共用同一套骨架：{@code hasCursor} 提前返回、{@code split("\\|", -1)}、两段校验、
 * {@code id > 0} 守卫、点名字段的异常。差异只在键的解析，故拆成两个入口而不做泛型——
 * 泛型化要为「类型不同」付一层间接成本，而这里的差异用两个方法就够了
 * （CLAUDE.md：抽象延迟到第二个真实用例，出现后再按需增加，不预先铺）。
 *
 * <p>与本模块外 {@code com.amz.multiplatform.TimeCursorPageSupport} 的关系：
 * 两者解决的问题相同（时间/数值列不唯一 → 复合游标），但键类型与模块不同，
 * 不合并 —— 合并要把两个模块的分页口径绑在一起，且 multiplatform 那份依赖
 * MyBatis-Plus 的 LambdaQueryWrapper 构造键集条件，本模块的键集条件由各调用方
 * 按自己的排序列拼（priority / reportDate / id 的顺序各不相同）。
 */
public final class AdPageCursor {

    private AdPageCursor() {
    }

    /** 数值列 + 主键的复合游标。 */
    public record IntCursor(int key, long id) {
    }

    /** 日期列 + 主键的复合游标。 */
    public record DateCursor(LocalDate key, long id) {
    }

    /**
     * 解析 {@code 值|id} 形态的游标。
     *
     * @param field 字段名，只用于把「哪个列表的游标坏了」说清楚
     */
    public static IntCursor parseInt(PageRequest page, String field) {
        if (page == null || !page.hasCursor()) {
            return null;
        }
        String[] parts = page.payload().split("\\|", -1);
        if (parts.length != 2) {
            throw new InvalidParamException("分页游标非法：" + field + " 游标格式应为 值|id");
        }
        try {
            int key = Integer.parseInt(parts[0]);
            long id = Long.parseLong(parts[1]);
            if (id <= 0) {
                throw new InvalidParamException("分页游标非法：id 必须 > 0，实际 " + id);
            }
            return new IntCursor(key, id);
        } catch (NumberFormatException e) {
            throw new InvalidParamException("分页游标非法：" + field + " 或 id 不是整数");
        }
    }

    /**
     * 解析 {@code yyyy-MM-dd|id} 形态的游标。
     *
     * <p>异常消息与原 {@code SearchTermServiceImpl.parseDateCursor} 逐字节一致
     * （含写死的 {@code reportDate} 字面量）——调用方只有一处，保持原文不做参数化，
     * 避免给这次重构叠加行为变更。
     */
    public static DateCursor parseDate(PageRequest page) {
        if (page == null || !page.hasCursor()) {
            return null;
        }
        String[] parts = page.payload().split("\\|", -1);
        if (parts.length != 2) {
            throw new InvalidParamException("分页游标非法：reportDate 游标格式应为 yyyy-MM-dd|id");
        }
        try {
            LocalDate key = LocalDate.parse(parts[0]);
            long id = Long.parseLong(parts[1]);
            if (id <= 0) {
                throw new InvalidParamException("分页游标非法：id 必须 > 0，实际 " + id);
            }
            return new DateCursor(key, id);
        } catch (DateTimeParseException | NumberFormatException e) {
            throw new InvalidParamException("分页游标非法：reportDate 或 id 格式错误");
        }
    }
}
