package com.amz.result;

import lombok.Data;

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

