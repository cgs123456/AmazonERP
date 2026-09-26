package com.amz.notification;

/**
 * 通知处理失败。
 * <p>
 * 区分可重试 / 不可重试的意义：订单类 Handler 最常见的失败是下游服务暂时不可用，
 * 重试能自愈；而参数非法、通知类型不支持这类失败重试一万次也还是坏的，
 * 让它继续占位会拖慢整条队列，直接进终态等人工处置才是对的。
 */
public class NotificationProcessingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final boolean retryable;
    private final String errorCode;
    private final String terminalStatus;

    public NotificationProcessingException(String errorCode, String message, boolean retryable) {
        this(errorCode, message, retryable, null);
    }

    public NotificationProcessingException(String errorCode, String message,
                                           boolean retryable, String terminalStatus) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
        this.terminalStatus = terminalStatus;
    }

    /** 可重试：下游暂时不可用等自愈型失败。 */
    public static NotificationProcessingException retryable(String errorCode, String message) {
        return new NotificationProcessingException(errorCode, message, true, null);
    }

    /** 不可重试：默认进 DLQ。 */
    public static NotificationProcessingException fatal(String errorCode, String message) {
        return new NotificationProcessingException(errorCode, message, false, null);
    }

    /**
     * 不可重试且落到指定终态（如 UNSUPPORTED / UNRESOLVED_SUBSCRIPTION）。
     * <p>
     * 与 DLQ 的别：DLQ 表示「这条事件坏了」，UNSUPPORTED 表示「我们还没实现它的处理逻辑」。
     * 混为一谈的后果是补上 Handler 后无法批量重放——因为没人知道哪些死信其实只是当时没实现。
     */
    public static NotificationProcessingException terminal(String terminalStatus,
                                                           String errorCode,
                                                           String message) {
        return new NotificationProcessingException(errorCode, message, false, terminalStatus);
    }

    /** 是否值得重试。 */
    public boolean isRetryable() {
        return retryable;
    }

    /** 结构化错误码，用于指标与告警；不携带 PII。 */
    public String getErrorCode() {
        return errorCode;
    }

    /** 指定的终态；null 表示按默认规则（不可重试 -> DLQ）。 */
    public String getTerminalStatus() {
        return terminalStatus;
    }
}