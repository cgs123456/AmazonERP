package com.amz.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SP-API 通用滑动窗口限流组件。
 * <p>
 * 按 {@code (shopId, endpoint)} 维度维护独立的滑动窗口，避免单店铺突发流量打爆
 * Amazon SP-API 配额导致 429。默认各 endpoint 配置参考 SP-API 官方 RateLimit：
 * <ul>
 *   <li>Orders: 30 req / 30s</li>
 *   <li>FBA Inventory: 25 req / 30s</li>
 *   <li>Listings: 10 req / 30s</li>
 *   <li>Reports: 5 req / 60s</li>
 * </ul>
 * <p>
 * 同时支持通过响应头 {@code x-amzn-RateLimit-Limit}（单位：req/s）动态收紧窗口上限，
 * 使本地限流与 Amazon 实际配额保持一致。
 */
@Component
public class SpiRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(SpiRateLimiter.class);

    /**
     * 默认窗口（未知 endpoint 兜底）：30 秒。
     */
    private static final Duration DEFAULT_WINDOW = Duration.ofSeconds(30);

    /**
     * 默认窗口内最大请求数（未知 endpoint 兜底）。
     */
    private static final int DEFAULT_MAX_REQUESTS = 30;

    /**
     * endpoint 限流策略（maxRequests 可被 {@link #updateLimit} 动态调整）。
     */
    private static final class EndpointPolicy {
        volatile int maxRequests;
        final Duration window;

        EndpointPolicy(int maxRequests, Duration window) {
            this.maxRequests = maxRequests;
            this.window = window;
        }
    }

    /**
     * endpoint -> 限流策略。
     */
    private final Map<String, EndpointPolicy> policies = new ConcurrentHashMap<>();

    /**
     * key: {@code shopId:endpoint} -> 请求时间戳队列（滑动窗口）。
     * 通过对 Deque 加锁保证单窗口统计的线程安全。
     */
    private final ConcurrentHashMap<String, Deque<Instant>> windows = new ConcurrentHashMap<>();

    public SpiRateLimiter() {
        // SP-API 官方默认配额（保守值）
        policies.put("orders", new EndpointPolicy(30, Duration.ofSeconds(30)));
        policies.put("fba-inventory", new EndpointPolicy(25, Duration.ofSeconds(30)));
        policies.put("listings", new EndpointPolicy(10, Duration.ofSeconds(30)));
        policies.put("reports", new EndpointPolicy(5, Duration.ofSeconds(60)));
    }

    /**
     * 获取限流许可。若当前窗口已满则阻塞等待至最早请求过期，被中断时抛 {@link RateLimitException}。
     *
     * @param shopId   店铺 ID
     * @param endpoint 端点标识，如 {@code "orders"}
     */
    public void acquire(Long shopId, String endpoint) {
        EndpointPolicy policy = policies.get(endpoint);
        if (policy == null) {
            policy = new EndpointPolicy(DEFAULT_MAX_REQUESTS, DEFAULT_WINDOW);
        }
        String key = shopId + ":" + endpoint;
        Deque<Instant> deque = windows.computeIfAbsent(key, k -> new ArrayDeque<>());

        synchronized (deque) {
            Instant now = Instant.now();
            Instant windowStart = now.minus(policy.window);
            // 清理窗口外的过期时间戳
            evictExpired(deque, windowStart);

            if (deque.size() >= policy.maxRequests) {
                Instant oldest = deque.peekFirst();
                long sleepMs = Duration.between(now, oldest.plus(policy.window)).toMillis();
                if (sleepMs > 0) {
                    log.info("acquire rate-limited: shopId={} endpoint={} window={}/{}ms, sleeping {}ms",
                            shopId, endpoint, deque.size(), policy.window.toMillis(), sleepMs);
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RateLimitException(
                                "Interrupted while waiting for rate limit: " + endpoint, e);
                    }
                }
                // 睡醒后再次清理过期时间戳
                evictExpired(deque, Instant.now().minus(policy.window));
            }
            deque.addLast(Instant.now());
        }
    }

    /**
     * 根据 SP-API 响应头 {@code x-amzn-RateLimit-Limit} 动态收紧窗口上限。
     * <p>
     * 头值为 req/s（如 {@code "0.0167"} 表示约 1 req/min），按当前窗口时长换算为
     * 窗口内最大请求数，仅在比当前配置更严格时生效（只收紧不放宽，避免误放大配额）。
     *
     * @param endpoint         端点标识
     * @param rateLimitHeader  {@code x-amzn-RateLimit-Limit} 头值（req/s）
     */
    public void updateLimit(String endpoint, String rateLimitHeader) {
        try {
            double ratePerSecond = Double.parseDouble(rateLimitHeader.trim());
            if (ratePerSecond <= 0 || Double.isNaN(ratePerSecond) || Double.isInfinite(ratePerSecond)) {
                return;
            }
            EndpointPolicy policy = policies.get(endpoint);
            if (policy == null) {
                policy = new EndpointPolicy(DEFAULT_MAX_REQUESTS, DEFAULT_WINDOW);
                policies.putIfAbsent(endpoint, policy);
            }
            // 换算为窗口内允许的最大请求数，至少 1
            int newMax = Math.max(1, (int) Math.ceil(ratePerSecond * policy.window.toMillis() / 1000.0));
            if (newMax < policy.maxRequests) {
                log.warn("updateLimit tightening: endpoint={} rate={}req/s -> max {} req per {}ms (was {})",
                        endpoint, ratePerSecond, newMax, policy.window.toMillis(), policy.maxRequests);
                policy.maxRequests = newMax;
            }
        } catch (NumberFormatException e) {
            log.warn("updateLimit failed to parse x-amzn-RateLimit-Limit='{}' for endpoint={}",
                    rateLimitHeader, endpoint);
        }
    }

    private void evictExpired(Deque<Instant> deque, Instant windowStart) {
        while (!deque.isEmpty() && deque.peekFirst().isBefore(windowStart)) {
            deque.pollFirst();
        }
    }
}
