package com.amz.notification;

/**
 * 通知载荷结构非法。
 * <p>
 * 这类异常代表「Amazon 发的消息不符合我们订阅时登记的结构」，处理方式必须是
 * 保留原始事件 + 隔离告警，而不是丢弃或重试：重试只会拿到同一条坏消息。
 */
public class InvalidNotificationException extends RuntimeException {

    /** 结构化错误码，用于指标与日志（不含 PII）。 */
    private final String errorCode;

    public InvalidNotificationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
