package com.amz.auth;

import com.amz.config.SpApiConfig;
import com.amz.credential.ShopCredential;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LWA Token 管理器单元测试。
 * <p>
 * 覆盖场景：
 * <ul>
 *   <li>Token 获取：null 凭证校验</li>
 *   <li>缓存命中：预置缓存条目后应直接返回，不触发 HTTP 刷新</li>
 *   <li>缓存失效：invalidate(null) 无副作用；invalidate(id) 移除条目</li>
 *   <li>过期前刷新：缓存条目临过期时应触发刷新</li>
 *   <li>并发安全：多线程并发 getToken 不产生并发异常</li>
 * </ul>
 * <p>
 * 说明：refreshToken() 走真实 HTTP，此处通过预置 cache 绕过 HTTP 调用，
 * 专注验证缓存逻辑与并发安全。HTTP 刷新由集成测试覆盖。
 */
@DisplayName("LwaTokenManager Token 管理器测试")
class LwaTokenManagerTest {

    private LwaTokenManager tokenManager;
    private SpApiConfig spApiConfig;

    @BeforeEach
    void setUp() {
        tokenManager = new LwaTokenManager();
        spApiConfig = new SpApiConfig();
        spApiConfig.setLwaEndpoint("https://api.amazon.com/auth/o2/token");
        ReflectionTestUtils.setField(tokenManager, "spApiConfig", spApiConfig);
    }

    private ShopCredential buildCredential() {
        ShopCredential c = new ShopCredential();
        c.setShopId(1L);
        c.setClientId("client-001");
        c.setClientSecret("secret-001");
        c.setRefreshToken("refresh-001");
        return c;
    }

    /**
     * 通过反射构造 TokenEntry 并放入 cache。
     * TokenEntry 是私有静态内部类，需反射构造。
     */
    @SuppressWarnings("unchecked")
    private void putCacheEntry(String clientId, String accessToken, Instant expiresAt) throws Exception {
        Class<?> tokenEntryClass = Class.forName("com.amz.auth.LwaTokenManager$TokenEntry");
        Constructor<?> constructor = tokenEntryClass.getDeclaredConstructor(String.class, Instant.class);
        constructor.setAccessible(true);
        Object tokenEntry = constructor.newInstance(accessToken, expiresAt);

        Map<String, Object> cache = (Map<String, Object>) ReflectionTestUtils.getField(tokenManager, "cache");
        cache.put(clientId, tokenEntry);
    }

    @Test
    @DisplayName("getToken：null 凭证应抛出 IllegalArgumentException")
    void testGetTokenNullCredentialThrows() {
        assertThrows(IllegalArgumentException.class, () -> tokenManager.getToken(null));
    }

    @Test
    @DisplayName("getToken：缓存命中且未临近过期应直接返回缓存 token，不触发 HTTP 刷新")
    void testGetTokenCacheHit() throws Exception {
        String cachedToken = "cached-access-token-abc123";
        // 过期时间设为 1 小时后（远超 5 分钟提前刷新阈值）
        putCacheEntry("client-001", cachedToken, Instant.now().plusSeconds(3600));

        ShopCredential credential = buildCredential();
        String token = tokenManager.getToken(credential);

        assertEquals(cachedToken, token, "应返回缓存中的 token");
    }

    @Test
    @DisplayName("getToken：缓存临过期（小于 5 分钟）应触发刷新；此处验证未命中缓存路径会调用 refreshToken")
    void testGetTokenCacheExpiringSoonTriggersRefresh() throws Exception {
        // 过期时间设为 1 分钟后（小于 REFRESH_AHEAD=5 分钟）
        putCacheEntry("client-001", "expiring-token", Instant.now().plusSeconds(60));

        ShopCredential credential = buildCredential();
        // refreshToken 会调用真实 LWA 端点，此处预期抛 RuntimeException（无网络或 401）
        assertThrows(RuntimeException.class, () -> tokenManager.getToken(credential));
    }

    @Test
    @DisplayName("invalidate：null clientId 应无副作用（不抛异常）")
    void testInvalidateNullIdIsNoOp() {
        assertDoesNotThrow(() -> tokenManager.invalidate(null));
    }

    @Test
    @DisplayName("invalidate：有效 clientId 应从缓存移除")
    void testInvalidateRemovesEntry() throws Exception {
        putCacheEntry("client-001", "token-aaa", Instant.now().plusSeconds(3600));

        tokenManager.invalidate("client-001");

        @SuppressWarnings("unchecked")
        Map<String, Object> cache = (Map<String, Object>) ReflectionTestUtils.getField(tokenManager, "cache");
        assertTrue(!cache.containsKey("client-001"), "缓存中应不再包含该 clientId");
    }

    @Test
    @DisplayName("invalidate：未知 clientId 应无副作用")
    void testInvalidateUnknownIdIsNoOp() {
        assertDoesNotThrow(() -> tokenManager.invalidate("non-existent-client"));
    }

    @Test
    @DisplayName("并发安全：50 线程并发 getToken 同一 clientId 不应产生并发异常")
    void testConcurrentGetTokenThreadSafety() throws Exception {
        // 预置一个永不过期的缓存条目，让所有线程都走缓存命中路径
        putCacheEntry("client-001", "concurrent-token", Instant.now().plusSeconds(7200));

        ShopCredential credential = buildCredential();
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    String token = tokenManager.getToken(credential);
                    if ("concurrent-token".equals(token)) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "所有线程应在 5 秒内完成");
        assertEquals(threadCount, successCount.get(), "所有线程应成功获取缓存 token");
        assertEquals(0, errorCount.get(), "不应有线程抛异常");

        executor.shutdownNow();
    }

    @Test
    @DisplayName("不同 clientId 应有独立缓存条目，互不干扰")
    void testMultipleClientIdsIndependentCache() throws Exception {
        putCacheEntry("client-A", "token-A", Instant.now().plusSeconds(3600));
        putCacheEntry("client-B", "token-B", Instant.now().plusSeconds(3600));

        ShopCredential credA = buildCredential();
        credA.setClientId("client-A");

        ShopCredential credB = buildCredential();
        credB.setClientId("client-B");

        assertEquals("token-A", tokenManager.getToken(credA));
        assertEquals("token-B", tokenManager.getToken(credB));
    }
}
