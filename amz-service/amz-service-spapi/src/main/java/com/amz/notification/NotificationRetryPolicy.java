package com.amz.notification;

/**
 * 通知处理重试策略：指数退避 + 上限封顶。
 * <p>
 * 封顶是必须的：基础 30 秒、5 次重试最坏只会退到 4 分钟，
 * 但如果将来把 max-attempts 调大而没有封顶，单条毒消息会把重试拉到几小时之后，
 * 表现为「事件隔很久才被处理一次」，比直接失败更难发现。
 */
public final class NotificationRetryPolicy {

    /** 单次退避上限（秒）：超过后不再翻倍。 */
    public static final long MAX_DELAY_SECONDS = 3600L;

    private final long baseDelaySeconds;

    public NotificationRetryPolicy(long baseDelaySeconds) {
        if (baseDelaySeconds <= 0) {
            throw new IllegalArgumentException("baseDelaySeconds 必须 > 0，实际为 " + baseDelaySeconds);
        }
        this.baseDelaySeconds = Math.min(baseDelaySeconds, MAX_DELAY_SECONDS);
    }

    /**
     * 第 attempt 次处理失败后应等待的秒数。
     *
     * @param attempt 已尝试次数（从 1 开始）
     * @return 退避秒数，不超过 {@link #MAX_DELAY_SECONDS}
     */
    public long nextDelaySeconds(int attempt) {
        int normalized = Math.max(1, attempt);
        long delay = baseDelaySeconds;
        for (int i = 1; i < normalized && delay < MAX_DELAY_SECONDS; i++) {
            long doubled = delay * 2;
            delay = doubled < 0 || doubled > MAX_DELAY_SECONDS ? MAX_DELAY_SECONDS : doubled;
        }
        return delay;
    }

    /** 是否应转入 DLQ：已尝试次数达到上限即停止重试。 */
    public boolean shouldDeadLetter(int attempt, int maxAttempts) {
        return Math.max(1, attempt) >= Math.max(1, maxAttempts);
    }
}