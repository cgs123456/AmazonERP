package com.amz.http;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 统一出站 HTTP 客户端：超时 + 指数退避重试（含抖动）+ 按目标维度熔断 + Micrometer 指标。
 * <p>
 * <b>解决的问题：</b>改造前各模块散落 6 处 {@code new RestTemplate()}，
 * 既无超时（默认无限等待，对端挂起会拖垮调用方线程池），也无重试与熔断，
 * 更无任何可观测指标。本类作为 amz-common 单一出站通道收口这些问题。
 * <p>
 * <b>target 语义：</b>调用方传入的目标系统逻辑标识（如 {@code TEMU} / {@code 1688} /
 * {@code exchange-rate}），用于熔断隔离与指标打标。<b>务必按外部系统而非按接口划分</b>，
 * 否则熔断粒度过细将失去保护意义。
 * <p>
 * <b>重试语义（关键）：</b>仅对以下情形重试，因其可安全假定为幂等失败：
 * <ul>
 *   <li>网络 IO 异常 / 超时（{@link ResourceAccessException}）—— 请求可能未到达对端</li>
 *   <li>5xx 服务端错误 —— 对端自述处理失败</li>
 *   <li>429 限流 —— 并额外尊重 {@code Retry-After} 响应头</li>
 * </ul>
 * 其余 4xx（参数错误 / 鉴权失败）<b>不重试</b>，重试无意义且会放大错误日志。
 * <p>
 * <b>注意：</b>POST 等非幂等写操作在网络异常下重试存在重复提交风险。
 * 对下单类接口请通过 {@link #execute(String, Supplier)} 自行控制，
 * 或确保对端支持幂等键（idempotency key）。
 * <p>
 * <b>暴露指标：</b>
 * <ul>
 *   <li>{@code amz.http.requests{target,outcome=success|failure|circuit_open}} 调用计数</li>
 *   <li>{@code amz.http.retries{target,reason=io|5xx|429}} 重试计数</li>
 *   <li>{@code amz.http.circuit.opened{target}} 熔断打开次数</li>
 *   <li>{@code amz.http.latency{target}} 调用耗时</li>
 * </ul>
 */
public class ResilientHttpClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientHttpClient.class);

    private static final String METRIC_REQUESTS = "amz.http.requests";
    private static final String METRIC_RETRIES = "amz.http.retries";
    private static final String METRIC_CIRCUIT_OPENED = "amz.http.circuit.opened";
    private static final String METRIC_LATENCY = "amz.http.latency";

    private final RestTemplate restTemplate;
    private final ResilientHttpProperties props;

    /** 可为 null：未引入 Micrometer 或单元测试场景下退化为无指标。 */
    private final MeterRegistry meterRegistry;

    /** 按 target 维度隔离的熔断器，首次使用时懒创建。 */
    private final Map<String, SimpleCircuitBreaker> breakers = new ConcurrentHashMap<>();

    public ResilientHttpClient(RestTemplate restTemplate, ResilientHttpProperties props,
                               MeterRegistry meterRegistry) {
        this.restTemplate = restTemplate;
        this.props = props != null ? props : new ResilientHttpProperties();
        this.meterRegistry = meterRegistry;
    }

    // ------------------------------------------------------------------ HTTP 便捷方法

    /**
     * GET 请求并返回响应体字符串。
     *
     * @param target  目标系统标识（熔断与指标维度）
     * @param url     完整 URL
     * @param headers 附加请求头，可为 null
     */
    public String get(String target, String url, Map<String, String> headers) {
        HttpEntity<Void> entity = new HttpEntity<>(toHttpHeaders(headers, false));
        return exchange(target, url, HttpMethod.GET, entity, String.class);
    }

    /**
     * POST JSON 请求并返回响应体字符串。
     *
     * @param target  目标系统标识（熔断与指标维度）
     * @param url     完整 URL
     * @param headers 附加请求头，可为 null（Content-Type 自动置为 application/json）
     * @param body    请求体
     */
    public String post(String target, String url, Map<String, String> headers, String body) {
        HttpEntity<String> entity = new HttpEntity<>(body, toHttpHeaders(headers, true));
        return exchange(target, url, HttpMethod.POST, entity, String.class);
    }

    /**
     * GET 并反序列化为指定类型（等价于 {@code RestTemplate.getForObject}，但带弹性保护）。
     */
    public <T> T getForObject(String target, String url, Class<T> responseType) {
        return execute(target, () -> restTemplate.getForObject(url, responseType));
    }

    /**
     * 通用 exchange，带弹性保护。
     */
    public <T> T exchange(String target, String url, HttpMethod method,
                          HttpEntity<?> entity, Class<T> responseType) {
        return execute(target, () -> restTemplate.exchange(url, method, entity, responseType).getBody());
    }

    // ------------------------------------------------------------------ 核心护栏

    /**
     * 以弹性策略执行任意调用（不限于 RestTemplate，也可包裹 JDK HttpClient / SDK 调用）。
     * <p>
     * 执行顺序：熔断准入 → 调用 → 失败分类 → 可重试则退避重试 → 记录指标与熔断状态。
     *
     * @param target 目标系统标识
     * @param action 实际调用动作
     * @return 调用结果
     * @throws CircuitBreakerOpenException 熔断打开，请求未发出
     */
    public <T> T execute(String target, Supplier<T> action) {
        String tag = target == null || target.isBlank() ? "unknown" : target;
        SimpleCircuitBreaker breaker = breakerFor(tag);

        try {
            breaker.acquirePermission();
        } catch (CircuitBreakerOpenException e) {
            count(METRIC_REQUESTS, "target", tag, "outcome", "circuit_open");
            log.warn("出站调用被熔断拒绝：target={} {}", tag, e.getMessage());
            throw e;
        }

        long startNanos = System.nanoTime();
        int maxAttempts = Math.max(1, props.getMaxAttempts());
        RuntimeException lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                T result = action.get();
                breaker.onSuccess();
                count(METRIC_REQUESTS, "target", tag, "outcome", "success");
                recordLatency(tag, startNanos);
                return result;
            } catch (RuntimeException e) {
                lastError = e;
                String reason = retryReason(e);

                if (reason == null || attempt == maxAttempts) {
                    // 不可重试，或已用尽尝试次数
                    if (isCircuitFailure(e)) {
                        breaker.onFailure();
                    } else {
                        // 4xx 参数/鉴权类错误说明对端是健康的，不应计入熔断
                        breaker.onSuccess();
                    }
                    count(METRIC_REQUESTS, "target", tag, "outcome", "failure");
                    recordLatency(tag, startNanos);
                    throw e;
                }

                count(METRIC_RETRIES, "target", tag, "reason", reason);
                long backoff = backoffMs(attempt, e);
                log.warn("出站调用失败将重试：target={} attempt={}/{} reason={} backoff={}ms err={}",
                        tag, attempt, maxAttempts, reason, backoff, e.getMessage());
                if (!sleep(backoff)) {
                    // 线程被中断：不再重试，按失败上报
                    breaker.onFailure();
                    count(METRIC_REQUESTS, "target", tag, "outcome", "failure");
                    recordLatency(tag, startNanos);
                    throw e;
                }
            }
        }
        // 理论不可达（循环内必然 return 或 throw），兜底保证编译期确定性
        throw lastError != null ? lastError
                : new IllegalStateException("出站调用异常终止：target=" + tag);
    }

    /**
     * 返回某 target 当前熔断状态，供健康检查 / 运维接口查询。
     */
    public SimpleCircuitBreaker.State circuitState(String target) {
        SimpleCircuitBreaker breaker = breakers.get(target);
        return breaker == null ? SimpleCircuitBreaker.State.CLOSED : breaker.getState();
    }

    // ------------------------------------------------------------------ 内部实现

    private SimpleCircuitBreaker breakerFor(String target) {
        return breakers.computeIfAbsent(target, t -> new SimpleCircuitBreaker(
                t,
                props.getCircuit().getFailureThreshold(),
                props.getCircuit().getOpenDurationMs(),
                props.getCircuit().getHalfOpenMaxCalls(),
                System::currentTimeMillis,
                () -> {
                    count(METRIC_CIRCUIT_OPENED, "target", t);
                    log.error("熔断打开：target={} 连续失败达阈值 {}，后续请求将快速失败 {}ms",
                            t, props.getCircuit().getFailureThreshold(),
                            props.getCircuit().getOpenDurationMs());
                }));
    }

    /**
     * 判定异常是否可重试，返回重试原因标签；不可重试返回 null。
     */
    private String retryReason(RuntimeException e) {
        if (e instanceof ResourceAccessException) {
            // 连接超时 / 读超时 / 连接被拒 —— 请求可能未被对端处理
            return "io";
        }
        if (e instanceof HttpStatusCodeException) {
            int code = ((HttpStatusCodeException) e).getStatusCode().value();
            if (code == 429) {
                return "429";
            }
            if (code >= 500 && code < 600) {
                return "5xx";
            }
        }
        return null;
    }

    /**
     * 判定异常是否应计入熔断失败。
     * <p>
     * 4xx（除 429）代表调用方请求有问题而非对端不可用，计入熔断会误伤：
     * 例如某个接口参数写错就把整个平台的出站通道熔断掉。
     */
    private boolean isCircuitFailure(RuntimeException e) {
        if (e instanceof HttpStatusCodeException) {
            int code = ((HttpStatusCodeException) e).getStatusCode().value();
            return code == 429 || code >= 500;
        }
        // IO 异常与其他未知异常按对端不可用处理
        return true;
    }

    /**
     * 计算本次重试的退避时长：指数增长 + 上限截断 + 抖动；
     * 若对端返回 {@code Retry-After} 则取两者较大值（但不超过配置上限）。
     */
    private long backoffMs(int attempt, RuntimeException e) {
        double base = props.getInitialBackoffMs()
                * Math.pow(props.getBackoffMultiplier(), attempt - 1d);
        long capped = (long) Math.min(base, props.getMaxBackoffMs());

        double jitterRatio = Math.max(0d, Math.min(1d, props.getJitterRatio()));
        if (jitterRatio > 0d) {
            double factor = 1d + ThreadLocalRandom.current().nextDouble(-jitterRatio, jitterRatio);
            capped = Math.max(1L, (long) (capped * factor));
        }

        long retryAfter = parseRetryAfterMs(e);
        return Math.max(capped, retryAfter);
    }

    /**
     * 解析 {@code Retry-After} 响应头（仅支持「秒」数值形式，HTTP-date 形式忽略），
     * 并按 {@code max-retry-after-ms} 截断，避免业务线程被对端无限期占用。
     */
    private long parseRetryAfterMs(RuntimeException e) {
        if (!(e instanceof HttpStatusCodeException)) {
            return 0L;
        }
        HttpHeaders headers = ((HttpStatusCodeException) e).getResponseHeaders();
        if (headers == null) {
            return 0L;
        }
        String raw = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            long seconds = Long.parseLong(raw.trim());
            if (seconds <= 0L) {
                return 0L;
            }
            return Math.min(seconds * 1000L, props.getMaxRetryAfterMs());
        } catch (NumberFormatException ex) {
            // HTTP-date 形式：不做解析，回退到指数退避
            log.debug("Retry-After 非数值形式，忽略：{}", raw);
            return 0L;
        }
    }

    /**
     * @return true 正常睡眠完成；false 被中断（调用方应终止重试）
     */
    private boolean sleep(long millis) {
        if (millis <= 0L) {
            return true;
        }
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private HttpHeaders toHttpHeaders(Map<String, String> headers, boolean jsonContentType) {
        HttpHeaders hh = new HttpHeaders();
        if (headers != null) {
            headers.forEach(hh::add);
        }
        if (jsonContentType && hh.getContentType() == null) {
            hh.setContentType(MediaType.APPLICATION_JSON);
        }
        return hh;
    }

    private void count(String metric, String... tags) {
        if (meterRegistry == null) {
            return;
        }
        try {
            meterRegistry.counter(metric, tags).increment();
        } catch (Exception e) {
            // 指标失败绝不影响业务链路
            log.debug("指标上报失败 metric={} {}", metric, e.getMessage());
        }
    }

    private void recordLatency(String target, long startNanos) {
        if (meterRegistry == null) {
            return;
        }
        try {
            meterRegistry.timer(METRIC_LATENCY, "target", target)
                    .record(Duration.ofNanos(System.nanoTime() - startNanos));
        } catch (Exception e) {
            log.debug("耗时指标上报失败 target={} {}", target, e.getMessage());
        }
    }
}
