package com.amz.http;

import java.util.function.LongSupplier;

/**
 * 轻量级熔断器（连续失败计数 + 半开探测），零第三方依赖。
 * <p>
 * <b>为何自研而非引入 Resilience4j：</b>本项目已使用 Sentinel
 * （{@code spring-cloud-starter-alibaba-sentinel} + {@code @SentinelResource}）
 * 作为服务级熔断降级框架。再引入 Resilience4j 会形成两套并行熔断栈，
 * 造成规则分散、指标口径不一致、运维认知负担翻倍。
 * 本类只解决「出站 HTTP 调用按目标系统维度快速失败」这一窄场景，
 * 与 Sentinel 的入站资源治理互补而非重叠。
 * <p>
 * 状态机：
 * <pre>
 *   CLOSED --连续失败达阈值--> OPEN --静默期到--> HALF_OPEN --探测成功--> CLOSED
 *                                 ^                          |
 *                                 +------探测失败-------------+
 * </pre>
 * 线程安全：全部状态变更方法 {@code synchronized}，粒度为单个 target 实例，
 * 竞争范围小，不构成吞吐瓶颈。
 */
public class SimpleCircuitBreaker {

    /** 熔断状态。 */
    public enum State {
        /** 关闭：正常放行。 */
        CLOSED,
        /** 打开：快速失败，不发出请求。 */
        OPEN,
        /** 半开：放行有限探测请求以试探对端是否恢复。 */
        HALF_OPEN
    }

    private final String target;
    private final int failureThreshold;
    private final long openDurationMs;
    private final int halfOpenMaxCalls;

    /** 时钟来源，测试可注入以避免真实 sleep。 */
    private final LongSupplier clock;

    /** 状态转为 OPEN 时的回调（用于指标埋点），可为 null。 */
    private final Runnable onOpen;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private long openedAtMs;
    private int halfOpenInFlight;

    public SimpleCircuitBreaker(String target, int failureThreshold, long openDurationMs,
                                int halfOpenMaxCalls) {
        this(target, failureThreshold, openDurationMs, halfOpenMaxCalls,
                System::currentTimeMillis, null);
    }

    public SimpleCircuitBreaker(String target, int failureThreshold, long openDurationMs,
                                int halfOpenMaxCalls, LongSupplier clock, Runnable onOpen) {
        this.target = target;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openDurationMs = Math.max(0L, openDurationMs);
        this.halfOpenMaxCalls = Math.max(1, halfOpenMaxCalls);
        this.clock = clock != null ? clock : System::currentTimeMillis;
        this.onOpen = onOpen;
    }

    /**
     * 申请调用许可。
     *
     * @throws CircuitBreakerOpenException 熔断打开或半开名额已满，请求不应发出
     */
    public synchronized void acquirePermission() {
        switch (state) {
            case CLOSED:
                return;
            case OPEN:
                long elapsed = clock.getAsLong() - openedAtMs;
                if (elapsed >= openDurationMs) {
                    // 静默期结束：转半开，放行第一个探测请求
                    state = State.HALF_OPEN;
                    halfOpenInFlight = 1;
                    return;
                }
                throw new CircuitBreakerOpenException(target, openDurationMs - elapsed);
            case HALF_OPEN:
                if (halfOpenInFlight < halfOpenMaxCalls) {
                    halfOpenInFlight++;
                    return;
                }
                // 探测名额已满：其余请求继续快速失败，避免半开期打爆对端
                throw new CircuitBreakerOpenException(target, 0L);
            default:
                return;
        }
    }

    /**
     * 记录一次成功调用：半开探测成功则闭合熔断，关闭态则清零失败计数。
     */
    public synchronized void onSuccess() {
        if (state == State.HALF_OPEN) {
            state = State.CLOSED;
            halfOpenInFlight = 0;
        }
        consecutiveFailures = 0;
    }

    /**
     * 记录一次失败调用（仅传入应计入熔断的失败，如超时 / 5xx / 429；
     * 4xx 参数类错误不应计入，否则会因调用方 bug 误伤对端可用性判定）。
     */
    public synchronized void onFailure() {
        if (state == State.HALF_OPEN) {
            // 探测失败：重新打开并重置静默期
            trip();
            return;
        }
        consecutiveFailures++;
        if (state == State.CLOSED && consecutiveFailures >= failureThreshold) {
            trip();
        }
    }

    private void trip() {
        state = State.OPEN;
        openedAtMs = clock.getAsLong();
        halfOpenInFlight = 0;
        consecutiveFailures = 0;
        if (onOpen != null) {
            onOpen.run();
        }
    }

    public synchronized State getState() {
        return state;
    }

    public synchronized int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public String getTarget() {
        return target;
    }
}
