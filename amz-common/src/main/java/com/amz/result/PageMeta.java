package com.amz.result;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 响应体 {@code _page} 段：分页元数据。
 *
 * <p>刻意放在 {@code data} 之外：既有用例直接读 {@code data} 数组，
 * 把分页元数据塞进 {@code data}（例如包一层 {@code {items:[]}}）会一次性打断所有列表页。
 * 分开放之后，老客户端零改造继续工作，新客户端读 {@code _page} 就能判断截断。
 *
 * <p>{@code total} 默认 null（未知）：不做 COUNT(*) 是性能取舍，
 * 大表上 COUNT 往往比取一页还贵。调用方应依赖 {@code hasMore} + {@code nextCursor} 翻页，
 * 不要依赖 {@code total} 算页数。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PageMeta {

    /** 本次请求的页大小（已校验，不会超过 PageRequest.MAX_SIZE）。 */
    private int size;

    /** 本页实际返回行数。 */
    private int returned;

    /** 是否还有下一页。 */
    private boolean hasMore;

    /**
     * 本页是否被截断。与 {@code hasMore} 同源：只要还有下一页，本页就不完整。
     * 单独出一个字段是为了让调用方一眼看到语义，而不是去推导 hasMore 的含义。
     */
    private boolean truncated;

    /** 下一页游标；{@code hasMore=false} 时为 null。 */
    private String nextCursor;

    /** 符合条件的总行数；未统计时为 null。 */
    private Long total;
}
