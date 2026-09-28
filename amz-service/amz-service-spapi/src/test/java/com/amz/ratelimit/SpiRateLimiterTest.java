package com.amz.ratelimit;

import com.google.gson.JsonObject;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SP-API 限流器行为测试（Task 5）。
 * <p>
 * 断言对象是<b>官方 usage plan</b>（rate + burst），不是本仓库自定的滑动窗口：
 * 官方数值逐项取自 {@code src/test/resources/contracts/} 下 15 份官方 OpenAPI 模型快照的
 * {@code description} "Usage Plan" 表（字节数与 sha256 由
 * {@link com.amz.client.SpApiPathContractTest} 锁定），因此本类可达到 E3 级证据
 * （官方夹具），而旧实现的「自写自测」只能到 E1。
 * <p>
 * 旧实现的缺陷（本类先写失败用例的动机）：
 * <ul>
 *   <li>策略按 <b>endpoint</b>（如 {@code "fees"}）分档，调用方传 operationId 时命中兜底
 *       30 req/30s（= 1 req/s），对 {@code fees.getMyFeesEstimates}（官方 0.5 req/s、burst 1）
 *       实际越权约 2 倍、burst 放大 30 倍；</li>
 *   <li>窗口而非 burst 语义：突发上限由窗口长度决定，与官方 burst 无关；</li>
 *   <li>等待发生在 {@code synchronized(deque)} 内，同键请求被串行化；</li>
 *   <li>配额只在 endpoint 维度收紧，单店触发收紧会殃及所有店铺。</li>
 * </ul>
 * <p>
 * 证据边界：本类证明「本组件的速率/burst 与官方模型一致、按店铺与 operation 隔离、
 * 观测收紧可恢复」（E3），<b>不</b>证明平台接受我方请求节奏（A5 需凭证联调）。
 */
@DisplayName("SP-API 限流：官方 usage plan 与按店铺隔离（Task 5）")
class SpiRateLimiterTest {

    private static final Long SHOP_A = 1001L;
    private static final Long SHOP_B = 2002L;

    /** 官方 fees.getMyFeesEstimates：0.5 req/s，burst 1（contracts/productFeesV0.json）。 */
    private static final String FEES_ESTIMATES = "fees.getMyFeesEstimates";

    /** 官方 reports.createReport：0.0167 req/s，burst 15（contracts/reports_2021-06-30.json）。 */
    private static final String REPORTS_CREATE = "reports.createReport";

    /** 官方 orders.getOrders：0.0167 req/s，burst 20（contracts/ordersV0.json）——补令牌约 60s。 */
    private static final String ORDERS_GET_ORDERS = "orders.getOrders";

    /** 官方 tokens.createRestrictedDataToken：1 req/s，burst 10（contracts/tokens_2021-03-01.json）。 */
    private static final String TOKENS_CREATE_RDT = "tokens.createRestrictedDataToken";

    /** 官方模型快照：classpath 资源 + 限流键分组前缀 + 字节数 + sha256（来源见 contracts/README.md）。 */
    private record Snapshot(String resource, String groupPrefix, long bytes, String sha256) {
    }

    private static final List<Snapshot> SNAPSHOTS = List.of(
            new Snapshot("/contracts/ordersV0.json", "orders", 226555L,
                    "027ac6f5c97126647c6925db9be09f78c7c741cd1d8727a5367374a1846bedc5"),
            new Snapshot("/contracts/reports_2021-06-30.json", "reports", 83685L,
                    "d72db9e5280262a92933a0e45e2207c150272f1d66177b2517c20671d69c732c"),
            new Snapshot("/contracts/feeds_2021-06-30.json", "feeds", 55901L,
                    "ab235b4a0e5ce21083b885dd4f2b8cae7a6f597d7adf2647b47b90d6f5098a16"),
            new Snapshot("/contracts/financesV0.json", "finances", 134109L,
                    "d80e881091367b0eccd4bde3ce834ed08877d3cf51095239eb8b1e328c0d19d6"),
            new Snapshot("/contracts/productFeesV0.json", "fees", 49426L,
                    "d06ad35f909d8c0985845f21420c1f75599531465b27f46d4946f7d7f522fc35"),
            new Snapshot("/contracts/fbaInventory.json", "fbaInventory", 36985L,
                    "7c14bcdb22de8ca2df45e5a40f2a422cff344d45985a68b9515b2e800edcc5ab"),
            new Snapshot("/contracts/messaging.json", "messaging", 106025L,
                    "16b585e87a3b72c3637ffa0890e08acb4e2090864f1e8b4c1a9271a06700a8a1"),
            new Snapshot("/contracts/uploads_2020-11-01.json", "uploads", 12157L,
                    "202444dd425c24308366a4aaab28680dfb2ec70c25f4d9cd5441ea7d968ec3bd"),
            new Snapshot("/contracts/sellers.json", "sellers", 29604L,
                    "497862ea32de8040453649986e2cd7c6fcc15b55e8022becc783e4a6d6ffcffd"),
            new Snapshot("/contracts/tokens_2021-03-01.json", "tokens", 15751L,
                    "3cd09ae7f218c83f32536a894cb8c42f2191c94b9c27f6bcf0a164442089b061"),
            new Snapshot("/contracts/notifications.json", "notifications", 95405L,
                    "6a5e945f2a53a91b9b97c27cd4570dc399db3dde2b777f623fc226fbcdc8469a"),
            new Snapshot("/contracts/listingsItems_2021-08-01.json", "listingsItems", 157514L,
                    "117617f4c86dbd5c1708913103806a24c0ef0bbcfb6054e415d044d07761faeb"),
            new Snapshot("/contracts/productPricing_2022-05-01.json", "productPricing", 105753L,
                    "db6ffeab130bf1d4ab8fa47e4e83417d30d9cb682b3ce53f74ed51b62f6f817c"),
            new Snapshot("/contracts/catalogItems_2022-04-01.json", "catalogItems", 151872L,
                    "1a029b01df1d847d3057740a6e877f89f2ab78104b5b4d8f4b839f8d00f600c2"),
            new Snapshot("/contracts/fulfillmentInbound_2024-03-20.json", "fbaInbound", 560644L,
                    "a4d4cdd08dd3f381f27154d7f9f503d45e0486d416c629598341bbd23c7ff487"));
    /** 官方 usage plan 表的数据行，形如 {@code "| 0.0222 | 10 |"}。 */
    private static final Pattern USAGE_PLAN_ROW =
            Pattern.compile("\\|\\s*([0-9]+(?:\\.[0-9]+)?)\\s*\\|\\s*([0-9]+)\\s*\\|");

    private static final Set<String> HTTP_METHODS =
            Set.of("get", "post", "put", "delete", "patch");

    @Test
    @DisplayName("burst 用尽后按官方 rate 阻塞：fees.getMyFeesEstimates = 0.5 req/s / burst 1")
    void burstExhaustionWaitsAtOfficialRate() {
        SpiRateLimiter limiter = new SpiRateLimiter();
        limiter.acquire(SHOP_A, FEES_ESTIMATES);

        long startNanos = System.nanoTime();
        limiter.acquire(SHOP_A, FEES_ESTIMATES);
        long waitedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        assertTrue(waitedMs >= 1500L,
                "burst=1 用尽后第二次 acquire 必须按官方 rate 0.5 req/s 等待约 2000ms，实际 " + waitedMs
                        + "ms；旧实现按 endpoint 兜底 30 req/30s 直接放行（越权约 2 倍、burst 放大 30 倍）");
        assertTrue(waitedMs <= 6000L, "等待时间异常偏大：实际 " + waitedMs + "ms");
    }

    @Test
    @DisplayName("burst 只允许官方额度：reports.createReport = 0.0167 req/s / burst 15，第 16 次必须阻塞")
    void burstIsCappedAtOfficialValue() throws Exception {
        SpiRateLimiter limiter = new SpiRateLimiter();
        for (int i = 0; i < 15; i++) {
            limiter.acquire(SHOP_A, REPORTS_CREATE);
        }

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                limiter.acquire(SHOP_A, REPORTS_CREATE);
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "spi-rate-limit-waiter");
        waiter.setDaemon(true);
        waiter.start();
        waiter.join(500L);
        boolean stillWaiting = waiter.isAlive();

        waiter.interrupt();
        waiter.join(5000L);

        assertTrue(stillWaiting,
                "官方 burst=15 用尽后第 16 次 acquire 必须阻塞（官方 rate 0.0167 req/s ≈ 等待 60s）；"
                        + "旧实现 burst 兜底 30 → 立即放行，突发可越权约 2 倍");
        assertTrue(failure.get() instanceof RateLimitException,
                "等待期间被中断必须抛 RateLimitException（保留中断标志），实际=" + failure.get());
        assertTrue(!waiter.isAlive(), "中断后等待线程必须退出，避免测试泄漏线程");
    }

    @Test
    @DisplayName("店铺隔离：A 店被观测收紧不影响 B 店同 operation 的配额与放行")
    void tighteningOneShopDoesNotAffectAnother() {
        SpiRateLimiter limiter = new SpiRateLimiter();
        limiter.updateLimitObserved(SHOP_A, FEES_ESTIMATES, 0.01d);

        assertEquals(0.01d, limiter.planFor(SHOP_A, FEES_ESTIMATES).ratePerSecond(), 1e-9,
                "A 店应按观测值收紧到 0.01 req/s");
        assertEquals(0.5d, limiter.planFor(SHOP_B, FEES_ESTIMATES).ratePerSecond(), 1e-9,
                "B 店必须仍是官方 0.5 req/s——旧实现按 endpoint 维度收紧，单店 429 会永久拖慢所有店铺");
        assertEquals(1, limiter.planFor(SHOP_B, FEES_ESTIMATES).burst(),
                "收紧只改速率，burst 仍取官方值");

        long startNanos = System.nanoTime();
        limiter.acquire(SHOP_B, FEES_ESTIMATES);
        long waitedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        assertTrue(waitedMs < 500L,
                "B 店首个请求应命中自身满桶立即放行，实际等待 " + waitedMs + "ms");
    }

    @Test
    @DisplayName("等待在锁外：A 店长等待期间，B 店同 operation 与 A 店其它 operation 都立即放行")
    void waitingDoesNotBlockOtherShopsOrOperations() throws Exception {
        SpiRateLimiter limiter = new SpiRateLimiter();
        // orders.getOrders 官方 burst=20：全部取满后第 21 次需等约 60s 才补出下一个令牌
        for (int i = 0; i < 20; i++) {
            limiter.acquire(SHOP_A, ORDERS_GET_ORDERS);
        }

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                limiter.acquire(SHOP_A, ORDERS_GET_ORDERS);
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "spi-rate-limit-long-waiter");
        waiter.setDaemon(true);
        waiter.start();
        waiter.join(300L);
        assertTrue(waiter.isAlive(), "A 店第 21 次 acquire 必须仍在等待（burst 已用尽）");

        // 两个调用都不应被 A 店的睡眠阻塞：旧实现在 synchronized(deque) 内 sleep，同键请求被串行化
        long startNanos = System.nanoTime();
        limiter.acquire(SHOP_B, ORDERS_GET_ORDERS);
        long otherShopMs = (System.nanoTime() - startNanos) / 1_000_000L;
        limiter.acquire(SHOP_A, FEES_ESTIMATES);
        long otherOperationMs = (System.nanoTime() - startNanos) / 1_000_000L - otherShopMs;

        waiter.interrupt();
        waiter.join(5000L);

        assertTrue(otherShopMs < 500L,
                "B 店同 operation 应命中自身满桶立即放行，实际等待 " + otherShopMs + "ms（等待必须发生在锁外）");
        assertTrue(otherOperationMs < 500L,
                "A 店 fees.getMyFeesEstimates 与 orders.getOrders 是不同的桶，应立即放行，实际等待 "
                        + otherOperationMs + "ms");
        assertTrue(failure.get() instanceof RateLimitException,
                "长等待线程被中断必须抛 RateLimitException，实际=" + failure.get());
        assertTrue(!waiter.isAlive(), "中断后等待线程必须退出，避免测试泄漏线程");
    }

    @Test
    @DisplayName("观测收紧可恢复：观测值 ≥ 官方值即撤销收紧，且绝不放大到官方默认之上")
    void observedRateRecoveryRestoresOfficialPlan() {
        SpiRateLimiter limiter = new SpiRateLimiter();
        assertEquals(0.0167d, limiter.planFor(SHOP_A, REPORTS_CREATE).ratePerSecond(), 1e-9);

        limiter.updateLimit(SHOP_A, REPORTS_CREATE, "0.005");
        assertEquals(0.005d, limiter.planFor(SHOP_A, REPORTS_CREATE).ratePerSecond(), 1e-9,
                "429 响应头观测值必须生效（旧实现只对 feeds 生效，且只收紧不恢复）");

        limiter.updateLimit(SHOP_A, REPORTS_CREATE, "0.0167");
        assertEquals(0.0167d, limiter.planFor(SHOP_A, REPORTS_CREATE).ratePerSecond(), 1e-9,
                "观测值回升到官方值即恢复官方默认速率");
        assertEquals(15, limiter.planFor(SHOP_A, REPORTS_CREATE).burst(),
                "收紧与恢复都不得改动官方 burst");

        // 官方文档明确：高吞吐卖家的 x-amzn-RateLimit-Limit 可能高于默认值 → 本地不放大
        limiter.updateLimit(SHOP_A, REPORTS_CREATE, "5");
        assertEquals(0.0167d, limiter.planFor(SHOP_A, REPORTS_CREATE).ratePerSecond(), 1e-9,
                "观测值高于官方默认时按官方值封顶，不得放大配额");

        limiter.updateLimit(SHOP_A, REPORTS_CREATE, "not-a-number");
        limiter.updateLimit(SHOP_A, REPORTS_CREATE, "");
        limiter.updateLimit(SHOP_A, REPORTS_CREATE, null);
        assertEquals(0.0167d, limiter.planFor(SHOP_A, REPORTS_CREATE).ratePerSecond(), 1e-9,
                "非法/空/缺失响应头必须被忽略，不得污染生效配额");
    }

    @Test
    @DisplayName("结构化观测：保留原始头值、观测速率、回填生效值、burst 与时间")
    void observationsExposeStructuredRateLimitState() {
        SpiRateLimiter limiter = new SpiRateLimiter();
        Instant before = Instant.now();
        limiter.updateLimit(SHOP_A, REPORTS_CREATE, "0.005");
        Instant after = Instant.now();

        List<SpiRateLimiter.RateLimitObservation> observations = limiter.observations();
        assertEquals(1, observations.size(), "每次有效观测都必须留下结构化记录，不能只写 WARN 日志");
        SpiRateLimiter.RateLimitObservation tightened = observations.get(0);
        assertEquals(SHOP_A, tightened.shopId());
        assertEquals(REPORTS_CREATE, tightened.operationId());
        assertNull(tightened.variant());
        assertEquals("0.005", tightened.headerValue(),
                "必须保留平台原始头值，便于验收报告核对，而不是只保留四舍五入后的速率");
        assertEquals(0.005d, tightened.observedRatePerSecond(), 1e-9);
        assertEquals(0.005d, tightened.effectiveRatePerSecond(), 1e-9);
        assertEquals(15, tightened.burst(), "观测收紧只改速率，burst 仍为官方值");
        assertFalse(tightened.observedAt().isBefore(before));
        assertFalse(tightened.observedAt().isAfter(after));

        limiter.updateLimit(SHOP_A, REPORTS_CREATE, "5");
        SpiRateLimiter.RateLimitObservation recovered = limiter.observations().get(0);
        assertEquals("5", recovered.headerValue(), "恢复观测也必须保留最新原始头值");
        assertEquals(5.0d, recovered.observedRatePerSecond(), 1e-9);
        assertEquals(0.0167d, recovered.effectiveRatePerSecond(), 1e-9,
                "观测值高于官方值时，结构化出口必须显示封顶后的生效值，不能显示未采用的 5 req/s");
        assertEquals(0.0167d, limiter.planFor(SHOP_A, REPORTS_CREATE).ratePerSecond(), 1e-9);
    }

    @Test
    @DisplayName("Micrometer：观测值与回填生效值以 spapi.ratelimit.limit Gauge 暴露")
    void micrometerExposesObservedAndEffectiveRateLimit() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SpiRateLimiter limiter = new SpiRateLimiter();
        limiter.bindTo(registry);
        limiter.updateLimit(SHOP_A, REPORTS_CREATE, "0.005");

        Gauge observed = registry.get("spapi.ratelimit.limit")
                .tags("shopId", String.valueOf(SHOP_A), "operation", REPORTS_CREATE,
                        "variant", "", "kind", "observed")
                .gauge();
        Gauge effective = registry.get("spapi.ratelimit.limit")
                .tags("shopId", String.valueOf(SHOP_A), "operation", REPORTS_CREATE,
                        "variant", "", "kind", "effective")
                .gauge();
        assertNotNull(observed, "必须暴露平台原始观测值 Gauge");
        assertNotNull(effective, "必须暴露回填后实际生效值 Gauge");
        assertEquals(0.005d, observed.value(), 1e-9);
        assertEquals(0.005d, effective.value(), 1e-9);

        limiter.updateLimit(SHOP_A, REPORTS_CREATE, "5");
        assertEquals(5.0d, observed.value(), 1e-9,
                "平台原始观测值必须随最新响应头更新");
        assertEquals(0.0167d, effective.value(), 1e-9,
                "生效值必须始终反映实际限流计划，不得放大到官方值以上");
    }
    @Test
    @DisplayName("官方契约：officialPlans() 与 15 份模型快照的 Usage Plan 表逐项一致（106 个 operation）")
    void officialPlansMatchContractSnapshots() throws Exception {
        Map<String, double[]> fromSnapshots = usagePlansFromSnapshots();

        assertEquals(106, fromSnapshots.size(),
                "15 份官方模型快照的 Usage Plan 表应解析出 106 个 operation；"
                        + "数量变化说明快照被替换或解析规则失效，必须显式复核");

        assertNotNull(fromSnapshots.get("sellers.getMarketplaceParticipations"));
        assertEquals(0.016d, fromSnapshots.get("sellers.getMarketplaceParticipations")[0], 1e-9);
        assertEquals(15, (int) fromSnapshots.get("sellers.getMarketplaceParticipations")[1]);
        assertNotNull(fromSnapshots.get("sellers.getAccount"));
        assertEquals(0.016d, fromSnapshots.get("sellers.getAccount")[0], 1e-9);
        assertEquals(15, (int) fromSnapshots.get("sellers.getAccount")[1]);
        assertNotNull(fromSnapshots.get(TOKENS_CREATE_RDT));
        assertEquals(1.0d, fromSnapshots.get(TOKENS_CREATE_RDT)[0], 1e-9);
        assertEquals(10, (int) fromSnapshots.get(TOKENS_CREATE_RDT)[1]);

        Map<String, UsagePlan> fromCode = new SpiRateLimiter().officialPlans();
        assertEquals(fromSnapshots.keySet(), fromCode.keySet(),
                "官方快照的 operationId 集合与 OFFICIAL_PLANS 必须双向一致"
                        + "（漏登记 → 运行时落到兜底，等于配额失真）");

        for (Map.Entry<String, double[]> entry : fromSnapshots.entrySet()) {
            String operationId = entry.getKey();
            UsagePlan plan = fromCode.get(operationId);
            assertNotNull(plan, "OFFICIAL_PLANS 缺少 " + operationId);
            assertEquals(entry.getValue()[0], plan.ratePerSecond(), 1e-9,
                    operationId + " 的 rate 与官方模型不一致");
            assertEquals((int) entry.getValue()[1], plan.burst(),
                    operationId + " 的 burst 与官方模型不一致");
            assertEquals(operationId, plan.key(), operationId + " 的限流键必须等于 operationId");
            assertNull(plan.variant(), operationId + " 未登记分档，variant 必须为 null");
        }

        // 反向护栏：官方模型中没有配额表的 operation 不得被凭空登记（避免臆造数值）
        assertNull(fromCode.get("fbaInventory.createInventoryItem"),
                "createInventoryItem 在官方模型中没有 Usage Plan 表，不得登记猜测值");
        assertNull(fromCode.get("messaging.sendInvoice"),
                "sendInvoice 在官方模型中没有 Usage Plan 表，不得登记猜测值");
        assertNull(fromCode.get("feeds.createFeed|JSON_LISTINGS_FEED"),
                "JSON_LISTINGS_FEED 的分档配额不在模型快照内，不得登记猜测值");
    }

    /** 解析 15 份官方模型快照的 Usage Plan 表：{@code 分组.operationId -> [rate, burst]}。 */
    private static Map<String, double[]> usagePlansFromSnapshots() throws Exception {
        Map<String, double[]> plans = new LinkedHashMap<>();
        for (Snapshot snapshot : SNAPSHOTS) {
            byte[] bytes = readResource(snapshot.resource());
            assertNotNull(bytes, "测试 classpath 缺少官方模型快照：" + snapshot.resource());
            assertEquals(snapshot.bytes(), bytes.length,
                    snapshot.resource() + " 字节数漂移：官方快照被替换，期望值需显式复核");
            assertEquals(snapshot.sha256(), sha256Hex(bytes),
                    snapshot.resource() + " sha256 漂移：官方快照被替换，期望值需显式复核");

            JsonObject model = JsonParser
                    .parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject paths = model.getAsJsonObject("paths");
            assertNotNull(paths, snapshot.resource() + " 没有 paths 段");

            for (String path : paths.keySet()) {
                JsonObject item = paths.getAsJsonObject(path);
                for (String method : item.keySet()) {
                    if (!HTTP_METHODS.contains(method)) {
                        continue;
                    }
                    JsonObject operation = item.getAsJsonObject(method);
                    String description = operation.has("description")
                            && operation.get("description").isJsonPrimitive()
                            ? operation.get("description").getAsString() : "";
                    Matcher matcher = USAGE_PLAN_ROW.matcher(description);
                    if (!matcher.find()) {
                        continue;
                    }
                    String operationId = operation.has("operationId")
                            && operation.get("operationId").isJsonPrimitive()
                            ? operation.get("operationId").getAsString() : null;
                    assertNotNull(operationId,
                            snapshot.resource() + " " + method.toUpperCase() + " " + path
                                    + " 有 Usage Plan 表但没有 operationId（无法登记限流维度）");
                    // 捕获组必须在二次 find() 之前取：Matcher 失配后会丢弃上一次的匹配结果
                    double rate = Double.parseDouble(matcher.group(1));
                    double burst = Double.parseDouble(matcher.group(2));
                    assertTrue(!matcher.find(),
                            snapshot.resource() + " " + operationId
                                    + " 的 description 里有多个 Usage Plan 数据行，解析规则需复核");
                    String key = snapshot.groupPrefix() + "." + operationId;
                    double[] previous = plans.put(key, new double[]{rate, burst});
                    assertNull(previous, "快照里出现重复的 " + key);
                }
            }
        }
        return plans;
    }

    private static byte[] readResource(String resource) throws Exception {
        try (InputStream in = SpiRateLimiterTest.class.getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private static String sha256Hex(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
