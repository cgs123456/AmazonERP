package com.amz.ratelimit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SP-API 限流器行为测试（Task 5）。
 * <p>
 * 断言对象是<b>官方 usage plan</b>（rate + burst），不是本仓库自定的滑动窗口：
 * 官方数值逐项取自 {@code src/test/resources/contracts/} 下 6 份官方 OpenAPI 模型快照的
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
                    "7c14bcdb22de8ca2df45e5a40f2a422cff344d45985a68b9515b2e800edcc5ab"));

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
    @DisplayName("官方契约：officialPlans() 与 6 份模型快照的 Usage Plan 表逐项一致（33 个 operation）")
    void officialPlansMatchContractSnapshots() throws Exception {
        Map<String, double[]> fromSnapshots = usagePlansFromSnapshots();

        assertEquals(33, fromSnapshots.size(),
                "6 份官方模型快照的 Usage Plan 表应解析出 33 个 operation；"
                        + "数量变化说明快照被替换或解析规则失效，必须显式复核");

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
        assertNull(fromCode.get("feeds.createFeed|JSON_LISTINGS_FEED"),
                "JSON_LISTINGS_FEED 的分档配额不在模型快照内，不得登记猜测值");
    }

    /** 解析 6 份官方模型快照的 Usage Plan 表：{@code 分组.operationId -> [rate, burst]}。 */
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
