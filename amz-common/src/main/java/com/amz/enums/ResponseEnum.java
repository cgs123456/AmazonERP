package com.amz.enums;

import lombok.Getter;

/**
 * 结果响应状态码枚举
 */
@Getter
public enum ResponseEnum {
    SUCCESS(200, "成功"),
    FAIL(400, "失败"),
    UNAUTHORIZED(401, "未授权"),
    INTERNAL_SERVER_ERROR(500, "服务器内部错误");

    private final int code;
    private final String message;
    ResponseEnum(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
