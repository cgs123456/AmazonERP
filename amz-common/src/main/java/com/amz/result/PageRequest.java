package com.amz.result;

import com.amz.exception.InvalidParamException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 列表接口统一分页参数：游标（keyset）分页。
 *
 * <p>为什么不用 {@code LIMIT n OFFSET m}：凭证、订单、邮件这类表在翻页期间持续写入，
 * OFFSET 会随新行插入整体右移，于是「下一页」会重复上一页已读的行，或整页跳过未读的行。
 * keyset 以排序列的值作游标，翻页与写入互不干扰，满足「翻页无重复、无遗漏、顺序稳定」的验收口径。
 *
 * <p>为什么游标要 Base64URL 编码而不是直接传裸值：游标是服务端内部结构。
 * 一旦客户端开始自己拼裸 id，将来排序键变更就无法演进。编码之后客户端只能原样回传；
 * 任何不可解析的游标按参数错误处理（fail-closed），绝不静默退化成「从第一页开始」——
 * 那会返回看起来正常、实际是错误的页。
 *
 * <p>游标载荷有两种形态：
 * <ul>
 *   <li>单列：{@link #encodeCursor(Long)}，用于按 id 排序的列表；</li>
 *   <li>复合：{@link #encodeCursor(String)}，用于按 (业务时间, id) 排序的列表
 *       （{@code statTime|id} 这类写法），单列游标无法保证并列时间下的稳定顺序。</li>
 * </ul>
 *
 * <p>不变量：{@link #MAX_SIZE} 是硬上限，超出即报错，不静默收敛，
 * 否则调用方以为拿到 5000 行，实际只有 500 行。
 */
public final class PageRequest {

    /** 未指定 size 时的默认页大小。 */
    public static final int DEFAULT_SIZE = 50;

    /** 单页硬上限：超过即参数错误。 */
    public static final int MAX_SIZE = 500;

    private static final String CURSOR_PREFIX = "v1:";

    private final int size;

    /** 原样回传的游标串；首页为 null。 */
    private final String cursor;

    /** 解码后的游标载荷（去掉版本前缀）；首页为 null。 */
    private final String payload;

    private PageRequest(int size, String cursor, String payload) {
        this.size = size;
        this.cursor = cursor;
        this.payload = payload;
    }

    /**
     * 从 HTTP 查询参数构造分页请求。
     *
     * @param size   期望页大小；null 用 {@link #DEFAULT_SIZE}；&lt;1 或 &gt;{@link #MAX_SIZE} 抛参数错误
     * @param cursor 上一页返回的 {@code nextCursor}；null 或空白表示首页
     * @throws InvalidParamException size 越界或游标不可解析
     */
    public static PageRequest of(Integer size, String cursor) {
        int resolved = size == null ? DEFAULT_SIZE : size;
        if (resolved < 1) {
            throw new InvalidParamException("分页参数非法：size 必须 >= 1，实际 " + resolved);
        }
        if (resolved > MAX_SIZE) {
            throw new InvalidParamException("分页参数非法：size 超过上限 " + MAX_SIZE + "，实际 " + resolved);
        }
        String decoded = decodeCursor(cursor);
        return new PageRequest(resolved, decoded == null ? null : cursor.trim(), decoded);
    }

    /** 首页便捷构造（调度器、内部调用）。 */
    public static PageRequest first(int size) {
        return of(size, null);
    }

    /**
     * 单列游标：把排序键（通常是自增 id）编码成可回传的游标。
     *
     * @throws InvalidParamException id 为 null 或非正数（说明调用方用错了排序键）
     */
    public static String encodeCursor(Long id) {
        if (id == null || id <= 0) {
            throw new InvalidParamException("分页游标非法：游标值缺失或必须 > 0，实际 " + id);
        }
        return encodeCursor(String.valueOf(id));
    }

    /**
     * 复合游标：把调用方自己拼好的排序键载荷编码成可回传的游标。
     * 载荷里不要放未脱敏的业务数据——游标会回传到客户端。
     */
    public static String encodeCursor(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new InvalidParamException("分页游标非法：游标载荷为空");
        }
        String raw = CURSOR_PREFIX + payload;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(cursor.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new InvalidParamException("分页游标无法解析：" + cursor);
        }
        if (!raw.startsWith(CURSOR_PREFIX)) {
            throw new InvalidParamException("分页游标版本不匹配：" + cursor);
        }
        String payload = raw.substring(CURSOR_PREFIX.length());
        if (payload.isBlank()) {
            throw new InvalidParamException("分页游标载荷为空：" + cursor);
        }
        return payload;
    }

    public int size() {
        return size;
    }

    /**
     * 探测行数：多取 1 行，只为判断 {@code hasMore}，避免为每页多跑一次 COUNT(*)。
     * 多出来的那一行不会进入返回数据。
     */
    public int probeSize() {
        return size + 1;
    }

    public String cursor() {
        return cursor;
    }

    /** 解码后的游标载荷（单列时就是 id 的字符串形式）。 */
    public String payload() {
        return payload;
    }

    /**
     * 单列游标下的排序键值。
     *
     * @throws InvalidParamException 载荷不是合法正数（复合游标或篡改过的游标）
     */
    public Long cursorId() {
        if (payload == null) {
            return null;
        }
        try {
            long id = Long.parseLong(payload);
            if (id <= 0) {
                throw new InvalidParamException("分页游标非法：游标值必须 > 0，实际 " + id);
            }
            return id;
        } catch (NumberFormatException e) {
            throw new InvalidParamException("分页游标不是单列 id 游标：" + payload);
        }
    }

    public boolean hasCursor() {
        return payload != null;
    }

    @Override
    public String toString() {
        return "PageRequest{size=" + size + ", hasCursor=" + (payload != null) + "}";
    }
}
