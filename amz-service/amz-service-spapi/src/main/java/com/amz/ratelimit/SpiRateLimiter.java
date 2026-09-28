package com.amz.ratelimit;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SP-API 通用限流组件（Task 5 重构）。
 * <p>
 * 语义严格对齐官方 usage plan：每个 <b>operation</b> 有独立的
 * {@code Rate (requests per second)} 与 {@code Burst}，本组件按
 * {@code (shopId, operationId[, variant])} 维度维护**令牌桶**：
 * <ul>
 *   <li>初始令牌数 = 官方 burst，允许一次性突发到官方额度；</li>
 *   <li>之后按官方 rate 匀速补充（rate 可小于 1 req/s，如 {@code orders.getOrders=0.0167}）；</li>
 *   <li>令牌不足时在<b>锁外</b>等待并在唤醒后重算，因此同键的多个线程不会串行化等待、
 *       不同键（其它店铺 / 其它 operation）完全不受影响；</li>
 *   <li>被中断时恢复中断标志并抛 {@link RateLimitException}（调用方可熔断而非无限阻塞）。</li>
 * </ul>
 * <p>
 * <b>为什么不用滑动窗口</b>：旧实现按「窗口内最大请求数」限流，窗口长度与官方 burst 无关，
 * 既可能在窗口内越权（30 req/30s 对 {@code fees.getMyFeesEstimates} 的 burst=1 放大 30 倍），
 * 也可能因为窗口固定而长时间误伤（{@code reports.createReport} 的 0.0167 req/s 若写成
 * 15 req/15min，突发后用满一次要等整窗）。令牌桶是官方 rate+burst 的直接映射。
 * <p>
 * 官方数值来源：{@code src/test/resources/contracts/} 下 15 份官方 OpenAPI 模型快照
 * {@code description} 的 "Usage Plan" 表，逐项由 {@link com.amz.ratelimit.SpiRateLimiter#officialPlans()}
 * 暴露给契约测试比对（见 {@code SpiRateLimiterTest}）。**未知 operationId 一律落到保守兜底
 * 1 req/s + burst 30（与旧实现 30 req/30s 等效）并打一次 WARN**，绝不静默放大配额。
 * <p>
 * 动态调整：{@code x-amzn-RateLimit-Limit} 观测值按 {@code (shopId, operationId)} 记录
 * （{@link #updateLimit}），只会收紧到观测值、且可在观测值回升时恢复到官方默认上限
 * ——旧实现是「只收紧不恢复 + endpoint 维度」，单店被限流会永久拖慢所有店铺。
 */
@Component
public class SpiRateLimiter implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(SpiRateLimiter.class);

    /** P0-52b：结构化观测与 Prometheus 抓取共用的指标名。 */
    private static final String RATE_LIMIT_METRIC = "spapi.ratelimit.limit";

    private static final String METRIC_KIND_OBSERVED = "observed";
    private static final String METRIC_KIND_EFFECTIVE = "effective";

    /** 未知 operationId 的兜底速率（req/s）：与旧实现 30 req/30s 等效，不改变既有放行量。 */
    private static final double FALLBACK_RATE_PER_SECOND = 1.0d;

    /** 未知 operationId 的兜底 burst（与旧实现 30s 窗口内 30 次等效）。 */
    private static final int FALLBACK_BURST = 30;

    /** 单次等待下限（纳秒）：避免令牌刚补充完时的忙等。 */
    private static final long MIN_WAIT_NANOS = 1_000_000L;

    /** 等待超过该阈值时以 INFO 记录（低于阈值只记 DEBUG，避免日志噪声）。 */
    private static final long INFO_LOG_WAIT_MILLIS = 1_000L;

    /**
     * 官方 usage plan 表：{@code operationId[|variant]} -> plan。
     * <p>
     * 键前缀是 API 分组（与官方模型文件名一一对应：orders / reports / feeds / finances / fees /
     * fbaInventory），后缀是该模型的 {@code operationId}（模型内是裸名，如 {@code getOrders}）。
     * 例：{@code ordersV0.json} 的 {@code getOrders} → {@code orders.getOrders}。
     */
    private static final Map<String, UsagePlan> OFFICIAL_PLANS = buildOfficialPlans();

    /** 令牌桶状态；锁只保护这三个字段，等待一律在锁外。 */
    private static final class TokenBucket {
        private final Object lock = new Object();
        private double tokens;
        private long lastRefillNanos;
        private boolean initialized;
    }

    /**
     * 一次有效 {@code x-amzn-RateLimit-Limit} 观测的结构化快照。
     * <p>
     * {@code observedRatePerSecond} 是平台原始观测值；{@code effectiveRatePerSecond} 是本地实际
     * 采用的速率（恒 ≤ 官方值）。两者分开保留，避免验收时只看到 WARN 文本，或把平台高于官方
     * 默认值的观测误当成已放大配额。
     *
     * @param shopId                  店铺 ID
     * @param operationId             官方 operationId
     * @param variant                 分档值；无分档时为 null
     * @param headerValue             平台响应头原始值（trim 后）
     * @param observedRatePerSecond   平台观测速率（req/s）
     * @param effectiveRatePerSecond  本地实际生效速率（req/s）
     * @param burst                   官方 burst（观测只改速率，不改 burst）
     * @param observedAt              观测时间
     */
    public record RateLimitObservation(
            Long shopId,
            String operationId,
            String variant,
            String headerValue,
            double observedRatePerSecond,
            double effectiveRatePerSecond,
            int burst,
            Instant observedAt) {

        public RateLimitObservation {
            Objects.requireNonNull(shopId, "shopId must not be null");
            if (operationId == null || operationId.isBlank()) {
                throw new IllegalArgumentException("operationId must not be blank");
            }
            if (headerValue == null || headerValue.isBlank()) {
                throw new IllegalArgumentException("headerValue must not be blank");
            }
            if (!(observedRatePerSecond > 0.0d) || !Double.isFinite(observedRatePerSecond)) {
                throw new IllegalArgumentException("observedRatePerSecond must be positive and finite");
            }
            if (!(effectiveRatePerSecond > 0.0d) || !Double.isFinite(effectiveRatePerSecond)) {
                throw new IllegalArgumentException("effectiveRatePerSecond must be positive and finite");
            }
            if (effectiveRatePerSecond > observedRatePerSecond) {
                throw new IllegalArgumentException("effectiveRatePerSecond must not exceed observedRatePerSecond");
            }
            if (burst < 1) {
                throw new IllegalArgumentException("burst must be >= 1");
            }
            Objects.requireNonNull(observedAt, "observedAt must not be null");
        }
    }
    /** {@code shopId:operationKey} -> 令牌桶。 */
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    /** {@code shopId:operationKey} -> 最近一次有效观测快照；恢复官方配额后仍保留，供验收取证。 */
    private final ConcurrentHashMap<String, RateLimitObservation> observations = new ConcurrentHashMap<>();

    /** Spring Boot 绑定的指标注册表；未注入时为 null，不影响限流主链路。 */
    private volatile MeterRegistry meterRegistry;

    /** 已注册 Gauge 的观测键，避免重复注册同一时间序列。 */
    private final Set<String> meterKeys = ConcurrentHashMap.newKeySet();
    /** {@code shopId:operationKey} -> 观测收紧后的速率（req/s，恒 ≤ 官方值）。 */
    private final ConcurrentHashMap<String, Double> observedRates = new ConcurrentHashMap<>();

    /** 只告警一次的键（未知 operation、未登记 variant），避免高并发下刷日志。 */
    private final Set<String> warnedKeys = ConcurrentHashMap.newKeySet();

    /**
     * 获取限流许可；令牌不足时阻塞到按官方 rate 补充出下一个令牌为止。
     *
     * @param shopId      店铺 ID
     * @param operationId 官方 operationId（带分组前缀，如 {@code orders.getOrders}）
     */
    public void acquire(Long shopId, String operationId) {
        acquire(shopId, operationId, null);
    }

    /**
     * 获取限流许可（带分档维度，如 {@code feeds.createFeed} + {@code JSON_LISTINGS_FEED}）。
     * <p>
     * 未登记官方分档配额时沿用 operation 默认配额，并只作为独立的限流窗口（不合并计数）。
     *
     * @param shopId      店铺 ID
     * @param operationId 官方 operationId
     * @param variant     分档值（如 feedType）；无分档传 {@code null} 或空串
     */
    public void acquire(Long shopId, String operationId, String variant) {
        Objects.requireNonNull(shopId, "shopId must not be null");
        String operation = requireOperationId(operationId);
        String operationKey = operationKey(operation, variant);
        String bucketKey = shopId + ":" + operationKey;
        TokenBucket bucket = buckets.computeIfAbsent(bucketKey, key -> new TokenBucket());

        while (true) {
            // 配额可能被 429 观测收紧或恢复：每次循环都重新解析，保证下次请求即生效。
            UsagePlan plan = effectivePlan(shopId, operation, variant);
            long waitNanos;
            synchronized (bucket.lock) {
                long nowNanos = System.nanoTime();
                refill(bucket, plan, nowNanos);
                if (bucket.tokens >= 1.0d) {
                    bucket.tokens -= 1.0d;
                    return;
                }
                waitNanos = nanosUntilNextToken(bucket, plan);
            }
            awaitOutsideLock(shopId, operationKey, waitNanos);
        }
    }

    /**
     * 当前生效的限流计划（店铺级观测优先；未观测到则官方默认）。
     *
     * @param shopId      店铺 ID
     * @param operationId 官方 operationId
     * @return 生效计划（未知 operation 返回保守兜底，速率 1 req/s、burst 30）
     */
    public UsagePlan planFor(Long shopId, String operationId) {
        return effectivePlan(shopId, requireOperationId(operationId), null);
    }

    /** {@link #planFor(Long, String)} 的分档版本。 */
    public UsagePlan planFor(Long shopId, String operationId, String variant) {
        return effectivePlan(shopId, requireOperationId(operationId), variant);
    }

    /**
     * 官方 usage plan 表（不可变副本），供契约测试与自描述端点比对官方模型快照。
     *
     * @return {@code operationId[|variant]} -> 官方 plan
     */
    public Map<String, UsagePlan> officialPlans() {
        return Map.copyOf(OFFICIAL_PLANS);
    }

    /**
     * 返回最近一次有效限流观测的不可变快照，按店铺与 operation 稳定排序。
     * <p>
     * 该出口只读、不改变限流状态；无观测时返回空列表。它用于验收时核对平台响应头是否
     * 真正进入本地限流器，不能替代真实 429/响应头联调。
     */
    public List<RateLimitObservation> observations() {
        return List.copyOf(observations.values().stream()
                .sorted(Comparator.comparingLong(RateLimitObservation::shopId)
                        .thenComparing(RateLimitObservation::operationId)
                        .thenComparing(observation -> observation.variant() == null
                                ? "" : observation.variant())
                        .thenComparing(RateLimitObservation::observedAt))
                .toList());
    }

    /**
     * 将已存在的观测注册为 Micrometer Gauge；后续观测也会动态注册。
     * <p>
     * 未绑定 registry 时限流主链路完全不受影响。Gauge 的 supplier 每次抓取时读取当前
     * 快照，因此恢复观测会更新同一时间序列，而不会留下过期的旧值。
     */
    @Override
    public void bindTo(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry must not be null");
        this.meterRegistry = registry;
        observations.values().stream()
                .sorted(Comparator.comparingLong(RateLimitObservation::shopId)
                        .thenComparing(RateLimitObservation::operationId)
                        .thenComparing(observation -> observation.variant() == null
                                ? "" : observation.variant()))
                .forEach(observation -> publishRateLimitMetrics(
                        observationKey(observation), observation));
    }

    /**
     * 根据 {@code x-amzn-RateLimit-Limit} 响应头记录**该店铺该 operation** 的观测速率。
     * <p>
     * 与旧实现的区别：① 只影响传入的 {@code shopId}（其它店铺不受牵连）；
     * ② 观测值回升时**撤销**收紧、恢复到官方默认上限；③ 观测值高于官方值时按官方值封顶，
     * 绝不放大到官方配额以上。
     *
     * @param shopId          店铺 ID
     * @param operationId     官方 operationId
     * @param rateLimitHeader {@code x-amzn-RateLimit-Limit} 头值（req/s）；null/空 或非法时忽略
     */
    public void updateLimit(Long shopId, String operationId, String rateLimitHeader) {
        updateLimit(shopId, operationId, null, rateLimitHeader);
    }

    /** {@link #updateLimit(Long, String, String)} 的分档版本。 */
    public void updateLimit(Long shopId, String operationId, String variant, String rateLimitHeader) {
        if (rateLimitHeader == null || rateLimitHeader.isBlank()) {
            return;
        }
        double observed;
        try {
            observed = Double.parseDouble(rateLimitHeader.trim());
        } catch (NumberFormatException e) {
            log.warn("updateLimit: cannot parse x-amzn-RateLimit-Limit='{}' shopId={} operation={}",
                    rateLimitHeader, shopId, operationKey(operationId, variant));
            return;
        }
        updateLimitObserved(shopId, operationId, variant, observed, rateLimitHeader.trim());
    }

    /** 直接以观测速率（req/s）更新店铺级配额；由 {@link #updateLimit} 或测试调用。 */
    public void updateLimitObserved(Long shopId, String operationId, double observedRatePerSecond) {
        updateLimitObserved(shopId, operationId, null, observedRatePerSecond);
    }

    /** {@link #updateLimitObserved(Long, String, double)} 的分档版本。 */
    public void updateLimitObserved(Long shopId, String operationId, String variant,
                                    double observedRatePerSecond) {
        updateLimitObserved(shopId, operationId, variant, observedRatePerSecond,
                Double.toString(observedRatePerSecond));
    }

    /**
     * 更新限流状态并保留原始观测文本。
     * <p>
     * 分成公开的数值入口与私有文本入口，避免 {@code updateLimit("5")} 在结构化出口里
     * 被改写成 {@code "5.0"}，验收时无法与平台原始响应头逐字对照。
     */
    private void updateLimitObserved(Long shopId, String operationId, String variant,
                                     double observedRatePerSecond, String headerValue) {
        Objects.requireNonNull(shopId, "shopId must not be null");
        String operation = requireOperationId(operationId);
        if (!(observedRatePerSecond > 0.0d) || !Double.isFinite(observedRatePerSecond)) {
            log.warn("updateLimit: ignored non-positive/non-finite observed rate {} shopId={} operation={}",
                    observedRatePerSecond, shopId, operationKey(operation, variant));
            return;
        }
        String normalizedVariant = variant == null || variant.isBlank() ? null : variant;
        UsagePlan official = officialPlan(operation, normalizedVariant);
        String operationKey = operationKey(operation, normalizedVariant);
        String key = shopId + ":" + operationKey;
        double effective = Math.min(observedRatePerSecond, official.ratePerSecond());

        // 先保留观测快照：即使速率未变化，最新响应头与时间也必须可审计。
        RateLimitObservation observation = new RateLimitObservation(
                shopId, operation, normalizedVariant, headerValue,
                observedRatePerSecond, effective, official.burst(), Instant.now());
        observations.put(key, observation);
        publishRateLimitMetrics(key, observation);

        // 观测值高于官方默认值时按官方值处理：既不放大（遵守平台配额），也能撤销之前的收紧。
        if (observedRatePerSecond >= official.ratePerSecond()) {
            if (observedRates.remove(key) != null) {
                log.info("updateLimit recovered: shopId={} operation={} observed={}req/s >= official {}req/s"
                                + " → restored official quota (burst {})",
                        shopId, operationKey, observedRatePerSecond, official.ratePerSecond(), official.burst());
            }
            return;
        }

        Double previous = observedRates.get(key);
        if (previous != null && Double.compare(previous, effective) == 0) {
            return;
        }
        observedRates.put(key, effective);
        log.warn("updateLimit tightened: shopId={} operation={} observed={}req/s → effective {}req/s"
                        + " (official {}req/s, burst {}); previous={}req/s",
                shopId, operationKey, observedRatePerSecond, effective,
                official.ratePerSecond(), official.burst(), previous);
    }

    private static String observationKey(RateLimitObservation observation) {
        return observation.shopId() + ":" + operationKey(observation.operationId(), observation.variant());
    }

    /**
     * 注册一个观测键的 observed/effective 两个 Gauge。指标值从当前快照读取，
     * 因此后续恢复或再次收紧都会反映到同一时间序列。
     */
    private void publishRateLimitMetrics(String key, RateLimitObservation observation) {
        MeterRegistry registry = meterRegistry;
        if (registry == null) {
            return;
        }
        registerGauge(registry, key, observation, METRIC_KIND_OBSERVED);
        registerGauge(registry, key, observation, METRIC_KIND_EFFECTIVE);
    }

    private void registerGauge(MeterRegistry registry, String key,
                               RateLimitObservation observation, String kind) {
        String meterKey = System.identityHashCode(registry) + ":" + key + ":" + kind;
        if (!meterKeys.add(meterKey)) {
            return;
        }
        Gauge.builder(RATE_LIMIT_METRIC, this, limiter -> limiter.metricValue(key, kind))
                .description("SP-API rate limit observed from response headers and effective local limit")
                .tags("shopId", String.valueOf(observation.shopId()),
                        "operation", observation.operationId(),
                        "variant", observation.variant() == null ? "" : observation.variant(),
                        "kind", kind)
                .register(registry);
    }

    private double metricValue(String key, String kind) {
        RateLimitObservation observation = observations.get(key);
        if (observation == null) {
            return 0.0d;
        }
        return METRIC_KIND_OBSERVED.equals(kind)
                ? observation.observedRatePerSecond() : observation.effectiveRatePerSecond();
    }

    /** 店铺级观测优先；未观测到则官方默认。 */
    private UsagePlan effectivePlan(Long shopId, String operation, String variant) {
        UsagePlan official = officialPlan(operation, variant);
        Double observed = observedRates.get(shopId + ":" + operationKey(operation, variant));
        if (observed == null) {
            return official;
        }
        return official.withRatePerSecond(Math.min(observed, official.ratePerSecond()));
    }

    /**
     * 解析官方计划：先查 {@code operationId|variant}，再退回 operation 默认，最后落到保守兜底。
     * 未登记的情况只告警一次，避免热路径刷日志。
     */
    private UsagePlan officialPlan(String operation, String variant) {
        String key = operationKey(operation, variant);
        UsagePlan plan = OFFICIAL_PLANS.get(key);
        if (plan != null) {
            return plan;
        }
        if (variant != null && !variant.isBlank()) {
            UsagePlan base = OFFICIAL_PLANS.get(operation);
            if (base != null) {
                warnOnce("variant:" + key, "SP-API usage plan has no entry for variant=" + variant
                        + " operation=" + operation + "；沿用 operation 默认 " + base.ratePerSecond()
                        + " req/s, burst " + base.burst()
                        + "（官方说明该分档数值与之不同，未取得官方数值前不填猜测值；"
                        + "以 x-amzn-RateLimit-Limit 观测为准）");
                return base.withVariant(variant);
            }
        }
        UsagePlan fallback = new UsagePlan(FALLBACK_RATE_PER_SECOND, FALLBACK_BURST, operation,
                variant == null || variant.isBlank() ? null : variant);
        warnOnce("fallback:" + key, "SP-API usage plan unknown for operation=" + operation
                + "；使用保守兜底 " + FALLBACK_RATE_PER_SECOND + " req/s, burst " + FALLBACK_BURST
                + "（如需精确配额，请核对官方模型并登记到 OFFICIAL_PLANS）");
        return fallback;
    }

    private void warnOnce(String key, String message) {
        if (warnedKeys.add(key)) {
            log.warn(message);
        }
    }

    /** 补充令牌：初始装满 burst；之后按 rate 线性补充，并裁剪到 burst 上限（收紧时不会瞬时超发）。 */
    private static void refill(TokenBucket bucket, UsagePlan plan, long nowNanos) {
        if (!bucket.initialized) {
            bucket.tokens = plan.burst();
            bucket.lastRefillNanos = nowNanos;
            bucket.initialized = true;
            return;
        }
        long elapsedNanos = nowNanos - bucket.lastRefillNanos;
        if (elapsedNanos <= 0L) {
            return;
        }
        double refilled = bucket.tokens + (elapsedNanos / 1_000_000_000.0d) * plan.ratePerSecond();
        bucket.tokens = Math.min(plan.burst(), refilled);
        bucket.lastRefillNanos = nowNanos;
    }

    /** 距下一个令牌可用的等待时长（纳秒），保证 ≥ {@link #MIN_WAIT_NANOS}。 */
    private static long nanosUntilNextToken(TokenBucket bucket, UsagePlan plan) {
        double deficit = 1.0d - bucket.tokens;
        double nanos = deficit / plan.ratePerSecond() * 1_000_000_000.0d;
        return Math.max(MIN_WAIT_NANOS, (long) Math.ceil(nanos));
    }

    /** 锁外等待（旧实现在 {@code synchronized(deque)} 内 sleep，同键请求被串行化）。 */
    private static void awaitOutsideLock(Long shopId, String operationKey, long waitNanos) {
        long waitMillis = Math.max(1L, waitNanos / 1_000_000L);
        if (waitMillis >= INFO_LOG_WAIT_MILLIS) {
            log.info("SP-API rate limit wait: shopId={} operation={} waitMs={}", shopId, operationKey, waitMillis);
        } else {
            log.debug("SP-API rate limit wait: shopId={} operation={} waitMs={}", shopId, operationKey, waitMillis);
        }
        try {
            Thread.sleep(waitMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RateLimitException("Interrupted while waiting for SP-API rate limit: shopId="
                    + shopId + " operation=" + operationKey, e);
        }
    }

    private static String requireOperationId(String operationId) {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
        return operationId;
    }

    /** 分档键：{@code operationId} 或 {@code operationId|variant}。 */
    private static String operationKey(String operationId, String variant) {
        return variant == null || variant.isBlank() ? operationId : operationId + "|" + variant;
    }

    /**
     * 官方 usage plan 表（106 个 operation，逐项来自 15 份官方模型快照的 "Usage Plan" 表）。
     * <p>
     * 改动这里的任何数值都必须同时改 {@code src/test/resources/contracts/} 的快照，
     * 否则 {@code SpiRateLimiterTest#officialPlansMatchContractSnapshots} 会以官方期望值失败。
     */
    private static Map<String, UsagePlan> buildOfficialPlans() {
        Map<String, UsagePlan> plans = new HashMap<>();

        // contracts/ordersV0.json
        put(plans, "orders.getOrders", 0.0167, 20);
        put(plans, "orders.getOrder", 0.5, 30);
        put(plans, "orders.getOrderBuyerInfo", 0.5, 30);
        put(plans, "orders.getOrderAddress", 0.5, 30);
        put(plans, "orders.getOrderItems", 0.5, 30);
        put(plans, "orders.getOrderItemsBuyerInfo", 0.5, 30);
        put(plans, "orders.getOrderRegulatedInfo", 0.5, 30);
        put(plans, "orders.updateVerificationStatus", 0.5, 30);
        put(plans, "orders.updateShipmentStatus", 5, 15);
        put(plans, "orders.confirmShipment", 2, 10);

        // contracts/reports_2021-06-30.json
        put(plans, "reports.createReport", 0.0167, 15);
        put(plans, "reports.getReport", 2, 15);
        put(plans, "reports.getReportDocument", 0.0167, 15);
        put(plans, "reports.getReports", 0.0222, 10);
        put(plans, "reports.cancelReport", 0.0222, 10);
        put(plans, "reports.getReportSchedules", 0.0222, 10);
        put(plans, "reports.getReportSchedule", 0.0222, 10);
        put(plans, "reports.createReportSchedule", 0.0222, 10);
        put(plans, "reports.cancelReportSchedule", 0.0222, 10);

        // contracts/feeds_2021-06-30.json
        // 注意：官方 description 另注 JSON_LISTINGS_FEED 的分档限流与之不同（数值在
        // Building Listings Management Workflows Guide，未纳入模型快照）→ 不登记猜测值，
        // 运行时以 feeds.createFeed|JSON_LISTINGS_FEED 为独立窗口并靠响应头观测收敛。
        put(plans, "feeds.createFeed", 0.0083, 15);
        put(plans, "feeds.createFeedDocument", 0.5, 15);
        put(plans, "feeds.getFeed", 2, 15);
        put(plans, "feeds.cancelFeed", 2, 15);
        put(plans, "feeds.getFeeds", 0.0222, 10);
        put(plans, "feeds.getFeedDocument", 0.0222, 10);

        // contracts/financesV0.json
        put(plans, "finances.listFinancialEvents", 0.5, 30);
        put(plans, "finances.listFinancialEventGroups", 0.5, 30);
        put(plans, "finances.listFinancialEventsByGroupId", 0.5, 30);
        put(plans, "finances.listFinancialEventsByOrderId", 0.5, 30);

        // contracts/productFeesV0.json
        put(plans, "fees.getMyFeesEstimates", 0.5, 1);
        put(plans, "fees.getMyFeesEstimateForASIN", 1, 2);
        put(plans, "fees.getMyFeesEstimateForSKU", 1, 2);

        // contracts/fbaInventory.json（createInventoryItem/deleteInventoryItem/addInventory
        // 在官方模型中没有 usage plan 表，因此不登记 → 落到保守兜底）
        put(plans, "fbaInventory.getInventorySummaries", 2, 2);

        // contracts/messaging.json（sendInvoice 没有 usage plan 表，不登记猜测值）
        put(plans, "messaging.getMessagingActionsForOrder", 1, 5);
        put(plans, "messaging.GetAttributes", 1, 5);
        put(plans, "messaging.confirmCustomizationDetails", 1, 5);
        put(plans, "messaging.createConfirmDeliveryDetails", 1, 5);
        put(plans, "messaging.createLegalDisclosure", 1, 5);
        put(plans, "messaging.createConfirmOrderDetails", 1, 5);
        put(plans, "messaging.createConfirmServiceDetails", 1, 5);
        put(plans, "messaging.CreateWarranty", 1, 5);
        put(plans, "messaging.createDigitalAccessKey", 1, 5);
        put(plans, "messaging.createUnexpectedProblem", 1, 5);

        // contracts/uploads_2020-11-01.json
        put(plans, "uploads.createUploadDestinationForResource", 10, 10);

        // contracts/sellers.json（getAccount 尚未提供客户端，但官方配额必须完整登记）
        put(plans, "sellers.getMarketplaceParticipations", 0.016, 15);
        put(plans, "sellers.getAccount", 0.016, 15);

        // contracts/tokens_2021-03-01.json
        put(plans, "tokens.createRestrictedDataToken", 1, 10);


        // contracts/notifications.json
        put(plans, "notifications.getSubscriptions", 1, 5);
        put(plans, "notifications.getSubscription", 1, 5);
        put(plans, "notifications.createSubscription", 1, 5);
        put(plans, "notifications.getSubscriptionById", 1, 5);
        put(plans, "notifications.deleteSubscriptionById", 1, 5);
        put(plans, "notifications.sendTestNotification", 1, 5);
        put(plans, "notifications.getDestinations", 1, 5);
        put(plans, "notifications.createDestination", 1, 5);
        put(plans, "notifications.getDestination", 1, 5);
        put(plans, "notifications.deleteDestination", 1, 5);

        // contracts/listingsItems_2021-08-01.json
        put(plans, "listingsItems.deleteListingsItem", 5, 5);
        put(plans, "listingsItems.getListingsItem", 5, 10);
        put(plans, "listingsItems.patchListingsItem", 5, 5);
        put(plans, "listingsItems.putListingsItem", 5, 10);
        put(plans, "listingsItems.searchListingsItems", 5, 5);

        // contracts/productPricing_2022-05-01.json
        put(plans, "productPricing.getFeaturedOfferExpectedPriceBatch", 0.033, 1);
        put(plans, "productPricing.getCompetitiveSummary", 0.033, 1);

        // contracts/catalogItems_2022-04-01.json
        put(plans, "catalogItems.searchCatalogItems", 2, 2);
        put(plans, "catalogItems.getCatalogItem", 2, 2);

        // contracts/fulfillmentInbound_2024-03-20.json
        put(plans, "fbaInbound.listInboundPlans", 2, 6);
        put(plans, "fbaInbound.createInboundPlan", 2, 2);
        put(plans, "fbaInbound.getInboundPlan", 2, 6);
        put(plans, "fbaInbound.listInboundPlanBoxes", 2, 6);
        put(plans, "fbaInbound.cancelInboundPlan", 2, 2);
        put(plans, "fbaInbound.listInboundPlanItems", 2, 6);
        put(plans, "fbaInbound.updateInboundPlanName", 2, 30);
        put(plans, "fbaInbound.listPackingGroupBoxes", 2, 30);
        put(plans, "fbaInbound.listPackingGroupItems", 2, 30);
        put(plans, "fbaInbound.setPackingInformation", 2, 2);
        put(plans, "fbaInbound.listPackingOptions", 2, 6);
        put(plans, "fbaInbound.generatePackingOptions", 2, 2);
        put(plans, "fbaInbound.confirmPackingOption", 2, 2);
        put(plans, "fbaInbound.listInboundPlanPallets", 2, 6);
        put(plans, "fbaInbound.listPlacementOptions", 2, 6);
        put(plans, "fbaInbound.generatePlacementOptions", 2, 2);
        put(plans, "fbaInbound.confirmPlacementOption", 2, 2);
        put(plans, "fbaInbound.getShipment", 2, 6);
        put(plans, "fbaInbound.listShipmentBoxes", 2, 30);
        put(plans, "fbaInbound.listShipmentContentUpdatePreviews", 2, 30);
        put(plans, "fbaInbound.generateShipmentContentUpdatePreviews", 2, 30);
        put(plans, "fbaInbound.getShipmentContentUpdatePreview", 2, 30);
        put(plans, "fbaInbound.confirmShipmentContentUpdatePreview", 2, 30);
        put(plans, "fbaInbound.getDeliveryChallanDocument", 2, 6);
        put(plans, "fbaInbound.listDeliveryWindowOptions", 2, 30);
        put(plans, "fbaInbound.generateDeliveryWindowOptions", 2, 30);
        put(plans, "fbaInbound.confirmDeliveryWindowOptions", 2, 30);
        put(plans, "fbaInbound.listShipmentItems", 2, 30);
        put(plans, "fbaInbound.updateShipmentName", 2, 30);
        put(plans, "fbaInbound.listShipmentPallets", 2, 30);
        put(plans, "fbaInbound.updateShipmentSourceAddress", 2, 30);
        put(plans, "fbaInbound.updateShipmentTrackingDetails", 2, 2);
        put(plans, "fbaInbound.listTransportationOptions", 2, 6);
        put(plans, "fbaInbound.generateTransportationOptions", 2, 2);
        put(plans, "fbaInbound.confirmTransportationOptions", 2, 2);
        put(plans, "fbaInbound.listItemComplianceDetails", 2, 6);
        put(plans, "fbaInbound.updateItemComplianceDetails", 2, 6);
        put(plans, "fbaInbound.createMarketplaceItemLabels", 2, 30);
        put(plans, "fbaInbound.setPrepDetails", 2, 30);
        put(plans, "fbaInbound.getInboundOperationStatus", 2, 6);

        return Map.copyOf(plans);
    }

    private static void put(Map<String, UsagePlan> plans, String operationId, double ratePerSecond, int burst) {
        UsagePlan previous = plans.put(operationId, new UsagePlan(ratePerSecond, burst, operationId));
        if (previous != null) {
            throw new IllegalStateException("duplicate official usage plan: " + operationId);
        }
    }
}
