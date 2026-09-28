package com.amz.result;

import com.amz.exception.InvalidParamException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 列表接口统一分页结果：数据 + 截断事实。
 *
 * <p>核心约定（对应 P1-01 验收）：结果被截断时必须留下机器可读证据。
 * 此前各处 {@code .last("LIMIT 500")} 直接返回 {@code List}，HTTP 200、
 * 结构与「确实只有 500 条」完全一致，调用方无从分辨。财务对账、采购审批、
 * 利润快照这类场景会因此漏单而不自知。
 *
 * <p>构造走「探测行」模式：调用方按 {@code size + 1} 行查询，
 * 第 {@code size + 1} 行只用于判定 {@code hasMore}，随后被丢弃，不进入返回数据。
 * 这样既不用 COUNT(*)，也不会把探测行泄漏给调用方。
 */
public final class PageResult<T> {

    private final List<T> items;
    private final int size;
    private final boolean hasMore;
    private final String nextCursor;
    private final Long total;

    private PageResult(List<T> items, int size, boolean hasMore, String nextCursor, Long total) {
        this.items = items;
        this.size = size;
        this.hasMore = hasMore;
        this.nextCursor = nextCursor;
        this.total = total;
    }

    /**
     * 由探测行构造分页结果。
     *
     * @param probedRows 查询返回的行数，可以是 {@code size} 或 {@code size + 1}
     * @param size       本页期望大小
     * @param cursorOf   由最后一行算出下一页游标的函数
     */
    public static <T> PageResult<T> of(List<T> probedRows, int size, Function<T, String> cursorOf) {
        Objects.requireNonNull(probedRows, "probedRows");
        Objects.requireNonNull(cursorOf, "cursorOf");
        if (size < 1) {
            throw new InvalidParamException("分页结果非法：size 必须 >= 1，实际 " + size);
        }
        boolean more = probedRows.size() > size;
        List<T> items = more
                ? new ArrayList<>(probedRows.subList(0, size))
                : new ArrayList<>(probedRows);
        String next = (more && !items.isEmpty())
                ? cursorOf.apply(items.get(items.size() - 1))
                : null;
        return new PageResult<>(Collections.unmodifiableList(items), size, more, next, null);
    }

    /** 空结果。 */
    public static <T> PageResult<T> empty(int size) {
        return new PageResult<>(Collections.emptyList(), size, false, null, null);
    }

    /**
     * 保持分页边界和游标不变，仅把当前页元素映射成另一种类型。
     */
    public <R> PageResult<R> map(Function<T, R> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        List<R> mapped = new ArrayList<>(items.size());
        for (T item : items) {
            mapped.add(mapper.apply(item));
        }
        return new PageResult<>(Collections.unmodifiableList(mapped), size, hasMore, nextCursor, total);
    }

    public List<T> items() {
        return items;
    }

    public int size() {
        return size;
    }

    public boolean hasMore() {
        return hasMore;
    }

    /**
     * 本页是否被截断。与 {@link #hasMore()} 同源：还有下一页 == 本页不完整。
     */
    public boolean truncated() {
        return hasMore;
    }

    public String nextCursor() {
        return nextCursor;
    }

    /**
     * 符合条件的总行数；未做 COUNT(*) 时为 null（未知），
     * 调用方不得把 null 当成 0。
     */
    public Long total() {
        return total;
    }

    /** 投影成响应体里的 {@code _page}（不含 items，避免与 {@code data} 重复传输一份数据）。 */
    public PageMeta meta() {
        PageMeta meta = new PageMeta();
        meta.setSize(size);
        meta.setReturned(items.size());
        meta.setHasMore(hasMore);
        meta.setTruncated(hasMore);
        meta.setNextCursor(nextCursor);
        meta.setTotal(total);
        return meta;
    }
}
