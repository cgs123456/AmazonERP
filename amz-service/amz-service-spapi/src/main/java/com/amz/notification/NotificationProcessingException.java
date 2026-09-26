package com.amz.notification;

/**
 * 通知处理失败。
 * <p>
 * 区分可重试 / 不可重试的意义：订单类 Handler 最常见的失败是下游服务暂时不可用，
 * 重试能自愈；而参数非法、通知类型不支持这类失败重试一万次也还是坏的，
 * 让它继续占位会拖慢整条队列，直接进 DLQ 等人工处置才是对的。
 */
public class NotificationProcessingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean retryable;
    private final String errorCode;

    public NotificationProcessingException(String errorCode, String message, boolean retryable) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public static NotificationProcessingException retryable(String errorCode, String message) {
        return new NotificationProcessingException(errorCode, message, true);
    }

    public static NotificationProcessingException fatal(String errorCode, String message) {
        return new NotificationProcessingException(errorCode, message, false);
    }

    /** 是否值得重试。 */
    public boolean isRetryable() {
        return retryable;
    }

    /** 结构化错误码，用于指标与告警；不携带 PII。 */
    public String getErrorCode() {
        return errorCode;
    }
}