package com.amz.ratelimit;

/**
 * SP-API 限流异常。
 * <p>
 * 当 {@link SpiRateLimiter#acquire} 在等待限流窗口释放过程中被中断、
 * 或限流窗口长期无法满足时抛出，使调用方可选择性地熔断而非无限阻塞。
 */
public class RateLimitException extends RuntimeException {

    public RateLimitException(String message) {
        super(message);
    }

    public RateLimitException(String message, Throwable cause) {
        super(message, cause);
    }
}
