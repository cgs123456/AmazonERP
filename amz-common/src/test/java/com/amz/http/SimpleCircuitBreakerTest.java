package com.amz.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 熔断状态机单元测试。
 * <p>
 * 通过注入可控时钟（{@link LongSupplier}）验证静默期与半开转换，
 * 避免真实 {@code Thread.sleep} 导致测试变慢或不稳定。
 */
class SimpleCircuitBreakerTest {

    /** 可控时钟：测试内手动推进"当前时间"。 */
    private final long[] now = {1_000L};

    private final boolean[] openCallbackFired = {false};

    private SimpleCircuitBreaker build(int threshold, long openDurationMs, int halfOpenMaxCalls) {
        return new SimpleCircuitBreaker("test-target", threshold, openDurationMs, halfOpenMaxCalls,
                () -> now[0], () -> openCallbackFired[0] = true);
    }

    @Test
    @DisplayName("CLOSED：正常放行且不抛异常")
    void closedAllowsCalls() {
        SimpleCircuitBreaker cb = build(3, 10_000L, 1);
        assertEquals(SimpleCircuitBreaker.State.CLOSED, cb.getState());
        assertDoesNotThrow(cb::acquirePermission);
    }

    @Test
    @DisplayName("连续失败达阈值后打开熔断，并触发 onOpen 回调（用于指标埋点）")
    void opensAfterThreshold() {
        SimpleCircuitBreaker cb = build(3, 10_000L, 1);
        cb.onFailure();
        cb.onFailure();
        assertEquals(SimpleCircuitBreaker.State.CLOSED, cb.getState(), "未达阈值不应打开");
        cb.onFailure();
        assertEquals(SimpleCircuitBreaker.State.OPEN, cb.getState());
        assertTrue(openCallbackFired[0], "打开熔断应触发 onOpen 回调");
    }

    @Test
    @DisplayName("成功调用清零失败计数，避免零散失败累积误触发熔断")
    void successResetsFailureCount() {
        SimpleCircuitBreaker cb = build(3, 10_000L, 1);
        cb.onFailure();
        cb.onFailure();
        cb.onSuccess();
        assertEquals(0, cb.getConsecutiveFailures());
        cb.onFailure();
        cb.onFailure();
        assertEquals(SimpleCircuitBreaker.State.CLOSED, cb.getState(),
                "计数已清零，再失败两次仍不应达到阈值 3");
    }

    @Test
    @DisplayName("OPEN：静默期内快速失败，异常携带剩余等待时长")
    void openRejectsDuringSilencePeriod() {
        SimpleCircuitBreaker cb = build(1, 10_000L, 1);
        cb.onFailure();
        assertEquals(SimpleCircuitBreaker.State.OPEN, cb.getState());

        now[0] += 5_000L; // 静默期未满
        CircuitBreakerOpenException e =
                assertThrows(CircuitBreakerOpenException.class, cb::acquirePermission);
        assertEquals("test-target", e.getTarget());
        assertEquals(5_000L, e.getRetryAfterMs());
    }

    @Test
    @DisplayName("OPEN → HALF_OPEN：静默期满后放行探测请求")
    void transitionsToHalfOpenAfterSilencePeriod() {
        SimpleCircuitBreaker cb = build(1, 10_000L, 1);
        cb.onFailure();

        now[0] += 10_000L; // 静默期刚满
        assertDoesNotThrow(cb::acquirePermission);
        assertEquals(SimpleCircuitBreaker.State.HALF_OPEN, cb.getState());
    }

    @Test
    @DisplayName("HALF_OPEN：探测成功则闭合熔断，恢复正常放行")
    void halfOpenSuccessClosesCircuit() {
        SimpleCircuitBreaker cb = build(1, 10_000L, 1);
        cb.onFailure();
        now[0] += 10_000L;
        cb.acquirePermission();

        cb.onSuccess();
        assertEquals(SimpleCircuitBreaker.State.CLOSED, cb.getState());
        assertDoesNotThrow(cb::acquirePermission);
    }

    @Test
    @DisplayName("HALF_OPEN：探测失败则重新打开并重置静默期")
    void halfOpenFailureReopens() {
        SimpleCircuitBreaker cb = build(1, 10_000L, 1);
        cb.onFailure();
        now[0] += 10_000L;
        cb.acquirePermission();

        cb.onFailure();
        assertEquals(SimpleCircuitBreaker.State.OPEN, cb.getState());
        // 静默期从探测失败时刻重新计时
        now[0] += 9_999L;
        assertThrows(CircuitBreakerOpenException.class, cb::acquirePermission);
        now[0] += 1L;
        assertDoesNotThrow(cb::acquirePermission);
    }

    @Test
    @DisplayName("HALF_OPEN：探测名额已满时其余请求继续快速失败，避免半开期打爆对端")
    void halfOpenLimitsConcurrentProbes() {
        SimpleCircuitBreaker cb = build(1, 10_000L, 1);
        cb.onFailure();
        now[0] += 10_000L;

        assertDoesNotThrow(cb::acquirePermission, "第一个探测请求应放行");
        CircuitBreakerOpenException e =
                assertThrows(CircuitBreakerOpenException.class, cb::acquirePermission);
        assertEquals(0L, e.getRetryAfterMs(), "名额占满场景 retryAfterMs 为 0");
    }

    @Test
    @DisplayName("参数下界保护：阈值/名额小于 1 时归一为 1，不会永不熔断")
    void guardsAgainstInvalidConfig() {
        SimpleCircuitBreaker cb = build(0, -1L, 0);
        cb.onFailure();
        assertEquals(SimpleCircuitBreaker.State.OPEN, cb.getState(),
                "阈值 0 应归一为 1，单次失败即熔断");
    }
}
