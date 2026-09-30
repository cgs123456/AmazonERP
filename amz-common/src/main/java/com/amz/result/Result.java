package com.amz.result;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Objects;

/**
 * 统一响应体。
 * <p>
 * {@code @NoArgsConstructor} 不是便利性的加法，而是服务间调用的硬要求：本类有两个带参构造器
 * 且都未标注 {@code @JsonCreator}，Jackson 无法在多个候选之间选定 creator，又没有默认构造器兜底，
 * 于是 {@code Result} 作为响应体可以被写出、却不能被读回
 * （InvalidDefinitionException: no Creators, like default constructor, exist）。
 * 17 个 Feign 客户端里 38 个返回 {@code Result<...>} 的方法因此全部解码失败，
 * 被各自的 fallbackFactory 静默降级。删除该注解会立刻重现故障，
 * 由 {@code ResultJsonDecodeContractTest} 守这条读方向。
 */
@Data
@NoArgsConstructor
public class Result<T> {

    /**
     * 结果数据
     */
    private T data;

    /**
     * 操作消息
     */
    private String message;

    /**
     * 状态码
     */
    private int code;

    /**
     * 被字段级权限切面置空的字段名列表，前端据此显示 {@code ***}。
     * 无字段过滤时为 null（不输出到 JSON）。
     */
    @JsonProperty("_hiddenFields")
    private List<String> hiddenFields;

    /**
     * 机器可读错误详情。成功响应为 null，且不输出该字段。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private ApiError error;

    /**
     * 分页元数据，序列化为 {@code _page}。非分页响应为 null，且不输出该字段。
     * <p>
     * 与 {@code _hiddenFields} 同一套约定：下划线前缀表示「响应元信息」，
     * 不占用业务字段命名空间。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("_page")
    private PageMeta page;

    public Result(String message, int code, T data) {
        this.message = message;
        this.code = code;
        this.data = data;
    }

    public Result(String message, int code, T data, ApiError error) {
        this(message, code, data);
        this.error = error;
    }

    public static <T> Result<T> success(T data) {
        return new Result<>("操作成功", 200, data);
    }

    public static <T> Result<T> failure(String message) {
        return new Result<>(message, 400, null);
    }

    public static <T> Result<T> failure(String message, ApiError error) {
        return new Result<>(message, 400, null, error);
    }
    /**
     * 分页响应被截断时的 message。
     * <p>
     * 刻意不复用「操作成功」：截断不是完整成功，日志、前端和自动化脚本
     * 都要能一眼看出这一页不完整。
     */
    public static final String MSG_TRUNCATED = "操作成功（结果已截断，请携带 nextCursor 继续翻页）";

    /**
     * 分页成功响应。
     * <p>
     * {@code data} 仍然直接是行数组，老客户端零改造继续工作；
     * 分页与截断事实放在 {@code _page}，新客户端据此翻页、据此告警。
     *
     * @param pageResult 服务层返回的分页结果
     */
    public static <T> Result<List<T>> paged(PageResult<T> pageResult) {
        Objects.requireNonNull(pageResult, "pageResult");
        Result<List<T>> result = new Result<>(
                pageResult.truncated() ? MSG_TRUNCATED : "操作成功",
                200,
                pageResult.items());
        result.setPage(pageResult.meta());
        return result;
    }
}
