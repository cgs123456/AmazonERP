package com.amz.http;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 弹性 HTTP 客户端单元测试。
 * <p>
 * 通过 {@link ResilientHttpClient#execute(String, java.util.function.Supplier)} 注入可控的
 * 失败序列，覆盖：重试分类（哪些异常重试、哪些不重试）、重试次数上限、
 * 熔断联动（5xx 计入 / 4xx 不计入）、以及 {@code Retry-After} 上限截断。
 * <p>
 * 退避参数被压到毫秒级（{@code initialBackoffMs=1}、{@code jitterRatio=0}），
 * 保证测试快速且结果确定。
 */
class ResilientHttpClientTest {

    /** 构造退避极小的配置，避免测试因真实等待变慢。 */
    private ResilientHttpProperties fastProps(int maxAttempts, int failureThreshold) {
        ResilientHttpProperties p = new ResilientHttpProperties();
        p.setMaxAttempts(maxAttempts);
        p.setInitialBackoffMs(1L);
        p.setMaxBackoffMs(2L);
        p.setJitterRatio(0d);
        p.getCircuit().setFailureThreshold(failureThreshold);
        p.getCircuit().setOpenDurationMs(60_000L);
        return p;
    }

    private ResilientHttpClient client(ResilientHttpProperties props) {
        // restTemplate 传 null：本测试只走 execute(target, supplier) 路径，不触达 RestTemplate
        return new ResilientHttpClient(null, props, new SimpleMeterRegistry());
    }

    private HttpClientErrorException tooManyRequests(String retryAfter) {
        HttpHeaders headers = new HttpHeaders();
        if (retryAfter != null) {
            headers.add(HttpHeaders.RETRY_AFTER, retryAfter);
        }
        return new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests", headers, null, null);
    }

    @Test
    @DisplayName("成功调用：不重试，仅执行一次")
    void successExecutesOnce() {
        AtomicInteger calls = new AtomicInteger();
        ResilientHttpClient c = client(fastProps(3, 5));

        String result = c.execute("t", () -> {
            calls.incrementAndGet();
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("网络异常：重试后成功（请求可能未达对端，重试安全）")
    void retriesOnIoThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        ResilientHttpClient c = client(fastProps(3, 5));

        String result = c.execute("t", () -> {
            if (calls.incrementAndGet() < 3) {
                throw new ResourceAccessException("connect timed out");
            }
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(3, calls.get(), "应在第 3 次尝试成功");
    }

    @Test
    @DisplayName("5xx 服务端错误：重试")
    void retriesOnServerError() {
        AtomicInteger calls = new AtomicInteger();
        ResilientHttpClient c = client(fastProps(3, 5));

        String result = c.execute("t", () -> {
            if (calls.incrementAndGet() < 2) {
                throw new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR);
            }
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("429 限流：重试")
    void retriesOnTooManyRequests() {
        AtomicInteger calls = new AtomicInteger();
        ResilientHttpClient c = client(fastProps(3, 5));

        String result = c.execute("t", () -> {
            if (calls.incrementAndGet() < 2) {
                throw tooManyRequests(null);
            }
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("4xx 参数/鉴权错误：不重试（重试无意义且放大错误日志）")
    void doesNotRetryOnClientError() {
        AtomicInteger calls = new AtomicInteger();
        ResilientHttpClient c = client(fastProps(3, 5));

        assertThrows(HttpClientErrorException.class, () -> c.execute("t", () -> {
            calls.incrementAndGet();
            throw new HttpClientErrorException(HttpStatus.BAD_REQUEST);
        }));

        assertEquals(1, calls.get(), "4xx 不应重试");
    }

    @Test
    @DisplayName("持续失败：按 maxAttempts 上限终止并抛出最后一次异常")
    void exhaustsRetries() {
        AtomicInteger calls = new AtomicInteger();
        ResilientHttpClient c = client(fastProps(3, 5));

        assertThrows(ResourceAccessException.class, () -> c.execute("t", () -> {
            calls.incrementAndGet();
            throw new ResourceAccessException("down");
        }));

        assertEquals(3, calls.get(), "尝试次数应等于 maxAttempts");
    }

    @Test
    @DisplayName("熔断联动：连续失败达阈值后快速失败，业务动作不再被调用")
    void circuitOpensAndShortCircuits() {
        AtomicInteger calls = new AtomicInteger();
        // maxAttempts=1：每次 execute 恰好产生一次失败，便于精确计数
        ResilientHttpClient c = client(fastProps(1, 2));

        for (int i = 0; i < 2; i++) {
            assertThrows(ResourceAccessException.class, () -> c.execute("t", () -> {
                calls.incrementAndGet();
                throw new ResourceAccessException("down");
            }));
        }
        assertEquals(2, calls.get());
        assertEquals(SimpleCircuitBreaker.State.OPEN, c.circuitState("t"));

        // 熔断打开后请求不应发出
        assertThrows(CircuitBreakerOpenException.class, () -> c.execute("t", () -> {
            calls.incrementAndGet();
            return "should-not-run";
        }));
        assertEquals(2, calls.get(), "熔断打开后业务动作不应被执行");
    }

    @Test
    @DisplayName("熔断隔离：4xx 不计入失败，避免调用方参数错误误伤对端可用性判定")
    void clientErrorDoesNotTripCircuit() {
        ResilientHttpClient c = client(fastProps(1, 2));

        for (int i = 0; i < 5; i++) {
            assertThrows(HttpClientErrorException.class, () -> c.execute("t",
                    () -> {
                        throw new HttpClientErrorException(HttpStatus.BAD_REQUEST);
                    }));
        }
        assertEquals(SimpleCircuitBreaker.State.CLOSED, c.circuitState("t"),
                "连续 5 次 400 不应打开熔断");
    }

    @Test
    @DisplayName("熔断按 target 隔离：一个目标系统熔断不影响其他目标")
    void circuitIsolatedPerTarget() {
        ResilientHttpClient c = client(fastProps(1, 1));

        assertThrows(ResourceAccessException.class, () -> c.execute("temu", () -> {
            throw new ResourceAccessException("down");
        }));
        assertEquals(SimpleCircuitBreaker.State.OPEN, c.circuitState("temu"));
        assertEquals(SimpleCircuitBreaker.State.CLOSED, c.circuitState("shein"));

        assertEquals("ok", c.execute("shein", () -> "ok"));
    }

    @Test
    @DisplayName("Retry-After 上限截断：对端返回超长等待时按 maxRetryAfterMs 封顶")
    void retryAfterIsCapped() {
        ResilientHttpProperties p = fastProps(2, 5);
        p.setMaxRetryAfterMs(60L); // 上限压到 60ms
        ResilientHttpClient c = client(p);

        AtomicInteger calls = new AtomicInteger();
        long start = System.currentTimeMillis();
        String result = c.execute("t", () -> {
            if (calls.incrementAndGet() < 2) {
                // 对端要求等 3600 秒；不截断会挂死业务线程
                throw tooManyRequests("3600");
            }
            return "ok";
        });
        long elapsed = System.currentTimeMillis() - start;

        assertEquals("ok", result);
        assertTrue(elapsed < 2_000L,
                "Retry-After 应被截断到 maxRetryAfterMs，实际耗时 " + elapsed + "ms");
    }

    @Test
    @DisplayName("Retry-After 为 HTTP-date 等非数值形式：忽略并回退到指数退避，不抛异常")
    void invalidRetryAfterFallsBackToBackoff() {
        AtomicInteger calls = new AtomicInteger();
        ResilientHttpClient c = client(fastProps(2, 5));

        String result = c.execute("t", () -> {
            if (calls.incrementAndGet() < 2) {
                throw tooManyRequests("Wed, 21 Oct 2026 07:28:00 GMT");
            }
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("未注入 MeterRegistry 时退化为无指标模式，不影响业务链路")
    void worksWithoutMeterRegistry() {
        ResilientHttpClient c = new ResilientHttpClient(null, fastProps(2, 5), null);
        assertEquals("ok", c.execute("t", () -> "ok"));
    }
}
