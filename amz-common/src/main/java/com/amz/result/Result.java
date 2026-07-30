package com.amz.result;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
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

    public Result(String message, int code, T data) {
        this.message = message;
        this.code = code;
        this.data = data;
    }

    public static <T> Result<T> success(T data) {
        return new Result<>("操作成功", 200, data);
    }

    public static <T> Result<T> failure(String message) {
        return new Result<>(message, 400, null);
    }
}
