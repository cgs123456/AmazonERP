package com.amz.client;

import lombok.Getter;

/**
 * Amazon Advertising API 调用异常。
 * <p>
 * 连接器必须 fail-closed：缺少店铺凭证、认证失败、限流、上游错误和传输失败
 * 都通过本异常显式暴露，禁止把异常伪装成空列表或 false。
 */
@Getter
public class AdvertisingApiException extends RuntimeException {

    private final String code;
    private final Integer statusCode;
    private final boolean retryable;

    public AdvertisingApiException(String code, String message, Integer statusCode, boolean retryable) {
        super(message);
        this.code = code;
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public AdvertisingApiException(String code, String message, Integer statusCode, boolean retryable,
                                   Throwable cause) {
        super(message, cause);
        this.code = code;
        this.statusCode = statusCode;
        this.retryable = retryable;
    }
}
