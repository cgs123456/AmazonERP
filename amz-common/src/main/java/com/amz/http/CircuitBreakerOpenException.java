package com.amz.http;

/**
 * 熔断器处于打开（或半开且探测名额已满）状态时快速失败抛出的异常。
 * <p>
 * 抛出该异常表示请求<b>未真正发出</b>，调用方应据此走降级分支（返回缓存 / 占位值 / 标记待重试），
 * 而不是当作对端业务错误处理。
 */
public class CircuitBreakerOpenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 被熔断的目标标识（如 TEMU / 1688 / exchange-rate）。 */
    private final String target;

    /** 距离下次允许探测的剩余毫秒数（半开名额已满时为 0）。 */
    private final long retryAfterMs;

    public CircuitBreakerOpenException(String target, long retryAfterMs) {
        super("熔断已开启，请求被拒绝（未发出）：target=" + target + "，约 " + retryAfterMs + "ms 后进入半开探测");
        this.target = target;
        this.retryAfterMs = retryAfterMs;
    }

    public String getTarget() {
        return target;
    }

    public long getRetryAfterMs() {
        return retryAfterMs;
    }
}
