package com.amz.ratelimit;

/**
 * SP-API 单个 operation 的官方 usage plan（速率 + 突发额度）。
 * <p>
 * 官方定义：{@code Rate (requests per second)} 为长期平均速率，{@code Burst} 为瞬时允许的
 * 额外请求数（Amazon 侧按令牌桶语义执行）。数值来源是官方 OpenAPI 模型
 * {@code description} 的 "Usage Plan" 表，本仓库把它逐字节锁定在
 * {@code src/test/resources/contracts/}（见 {@code contracts/README.md} 与
 * {@link com.amz.client.SpApiPathContractTest}）。
 * <p>
 * {@code variant} 是可选的第二维度：官方 {@code feeds.createFeed} 的说明写明
 * {@code JSON_LISTINGS_FEED} 的限流与 {@code createFeed} operation <b>不同</b>，
 * 但该分档数值位于 *Building Listings Management Workflows Guide*（不在模型快照内）。
 * 轮询到官方数值后登记为 {@code operationId|feedType} 条目即自动生效；
 * 未登记前 {@code variant} 只用于隔离窗口，速率沿用 operation 默认值——**不填猜测值**。
 *
 * @param ratePerSecond 官方长期速率（req/s，&gt; 0）
 * @param burst         官方突发额度（&ge; 1）
 * @param operationId   官方 operationId（本仓库带 API 分组前缀，如 {@code orders.getOrders}）
 * @param variant       可选分档值（如 {@code JSON_LISTINGS_FEED}）；无分档时为 {@code null}
 */
public record UsagePlan(double ratePerSecond, int burst, String operationId, String variant) {

    public UsagePlan {
        if (!(ratePerSecond > 0.0d) || !Double.isFinite(ratePerSecond)) {
            throw new IllegalArgumentException("ratePerSecond must be a positive finite number: " + ratePerSecond);
        }
        if (burst < 1) {
            throw new IllegalArgumentException("burst must be >= 1: " + burst);
        }
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
        if (variant != null && variant.isBlank()) {
            variant = null;
        }
    }

    /** 无分档维度的官方 usage plan。 */
    public UsagePlan(double ratePerSecond, int burst, String operationId) {
        this(ratePerSecond, burst, operationId, null);
    }

    /**
     * 按观测值（{@code x-amzn-RateLimit-Limit}）派生的店铺级计划：只改速率，burst 仍取官方值。
     * <p>
     * 调用方负责取 {@code min(观测值, 官方值)}——观测值不得把速率放大到官方值以上。
     */
    public UsagePlan withRatePerSecond(double observedRatePerSecond) {
        return new UsagePlan(observedRatePerSecond, burst, operationId, variant);
    }

    /** 同一配额下补上分档维度（用于未登记的 variant：沿用 operation 默认配额，仅隔离窗口）。 */
    public UsagePlan withVariant(String observedVariant) {
        return new UsagePlan(ratePerSecond, burst, operationId, observedVariant);
    }

    /** 唯一键：{@code operationId}，有分档时为 {@code operationId|variant}。 */
    public String key() {
        return variant == null ? operationId : operationId + "|" + variant;
    }

    @Override
    public String toString() {
        return ratePerSecond + " req/s, burst " + burst + " (" + key() + ")";
    }
}