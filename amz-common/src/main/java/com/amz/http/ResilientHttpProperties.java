package com.amz.http;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 弹性 HTTP 客户端配置（前缀 {@code amz.http}）。
 * <p>
 * 全部参数均有生产可用默认值，业务模块无需配置即可获得「超时 + 重试 + 熔断」保护；
 * 需要按目标系统差异化调整时，在各服务 application.yml 覆盖即可。
 * <p>
 * 配置示例：
 * <pre>
 * amz:
 *   http:
 *     connect-timeout-ms: 3000
 *     read-timeout-ms: 10000
 *     max-attempts: 3
 *     initial-backoff-ms: 500
 *     backoff-multiplier: 2.0
 *     max-backoff-ms: 8000
 *     jitter-ratio: 0.2
 *     max-retry-after-ms: 30000
 *     circuit:
 *       failure-threshold: 5
 *       open-duration-ms: 30000
 *       half-open-max-calls: 1
 * </pre>
 */
@ConfigurationProperties(prefix = "amz.http")
public class ResilientHttpProperties {

    /** 建连超时（毫秒）。过大会在对端不可达时长时间占用线程。 */
    private int connectTimeoutMs = 3000;

    /** 读取超时（毫秒）。需大于对端 P99 响应时间，否则会把慢响应误判为失败并重试放大压力。 */
    private int readTimeoutMs = 10000;

    /** 最大尝试次数（含首次调用）。设为 1 表示不重试。 */
    private int maxAttempts = 3;

    /** 首次重试前的基础退避时长（毫秒）。 */
    private long initialBackoffMs = 500L;

    /** 退避倍数，每次重试退避时长乘以该系数（指数退避）。 */
    private double backoffMultiplier = 2.0d;

    /** 单次退避上限（毫秒），防止指数增长导致线程长时间挂起。 */
    private long maxBackoffMs = 8000L;

    /**
     * 抖动比例（0~1）。实际退避 = base * (1 ± jitterRatio * random)，
     * 用于打散多实例同时重试造成的惊群（thundering herd）。
     */
    private double jitterRatio = 0.2d;

    /**
     * 尊重对端 {@code Retry-After} 响应头时的最大等待上限（毫秒）。
     * 避免对端返回超长 Retry-After 导致业务线程被无限期占用。
     */
    private long maxRetryAfterMs = 30000L;

    /** 熔断参数。 */
    private final Circuit circuit = new Circuit();

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getInitialBackoffMs() {
        return initialBackoffMs;
    }

    public void setInitialBackoffMs(long initialBackoffMs) {
        this.initialBackoffMs = initialBackoffMs;
    }

    public double getBackoffMultiplier() {
        return backoffMultiplier;
    }

    public void setBackoffMultiplier(double backoffMultiplier) {
        this.backoffMultiplier = backoffMultiplier;
    }

    public long getMaxBackoffMs() {
        return maxBackoffMs;
    }

    public void setMaxBackoffMs(long maxBackoffMs) {
        this.maxBackoffMs = maxBackoffMs;
    }

    public double getJitterRatio() {
        return jitterRatio;
    }

    public void setJitterRatio(double jitterRatio) {
        this.jitterRatio = jitterRatio;
    }

    public long getMaxRetryAfterMs() {
        return maxRetryAfterMs;
    }

    public void setMaxRetryAfterMs(long maxRetryAfterMs) {
        this.maxRetryAfterMs = maxRetryAfterMs;
    }

    public Circuit getCircuit() {
        return circuit;
    }

    /**
     * 熔断器参数（按 target 维度独立生效）。
     */
    public static class Circuit {

        /** 连续失败达到该次数即打开熔断。 */
        private int failureThreshold = 5;

        /** 熔断打开后的静默时长（毫秒），到期转为半开放行探测请求。 */
        private long openDurationMs = 30000L;

        /** 半开状态下允许并发探测的请求数。 */
        private int halfOpenMaxCalls = 1;

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public long getOpenDurationMs() {
            return openDurationMs;
        }

        public void setOpenDurationMs(long openDurationMs) {
            this.openDurationMs = openDurationMs;
        }

        public int getHalfOpenMaxCalls() {
            return halfOpenMaxCalls;
        }

        public void setHalfOpenMaxCalls(int halfOpenMaxCalls) {
            this.halfOpenMaxCalls = halfOpenMaxCalls;
        }
    }
}
