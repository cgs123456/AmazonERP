package com.amz.connector;

import com.amz.client.TokensClient;
import com.amz.config.RestrictedDataProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("RDT 内存缓存、刷新与租户隔离")
class RestrictedDataTokenManagerTest {

    private static final long SHOP_ID = 1001L;
    private static final String MARKETPLACE_ID = "ATVPDKIKX0DER";
    private static final Instant NOW = Instant.parse("2026-09-26T06:00:00Z");
    private static final List<RestrictedResource> RESOURCES =
            List.of(new RestrictedResource("GET", "/orders/v0/orders", List.of("buyerInfo")));

    @Test
    @DisplayName("相同 shop/marketplace/resource 在有效期内复用缓存")
    void reusesTokenForSameScope() {
        TokensClient client = mock(TokensClient.class);
        when(client.requestToken(anyLong(), anyString(), any())).thenReturn(token("rdt-1", NOW.plusSeconds(600)));
        RestrictedDataTokenManager manager = manager(client, new MutableClock(NOW), 60, 10);

        assertEquals("rdt-1", manager.getToken(SHOP_ID, MARKETPLACE_ID, RESOURCES));
        assertEquals("rdt-1", manager.getToken(SHOP_ID, MARKETPLACE_ID, RESOURCES));

        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(1))
                .requestToken(SHOP_ID, MARKETPLACE_ID, RESOURCES);
    }

    @Test
    @DisplayName("不同店铺、marketplace、资源集合不得复用 token")
    void isolatesCacheScopes() {
        TokensClient client = mock(TokensClient.class);
        when(client.requestToken(anyLong(), anyString(), any()))
                .thenAnswer(invocation -> token("rdt-" + invocation.getArgument(0) + "-"
                        + invocation.getArgument(1) + "-" + invocation.getArgument(2).hashCode(),
                        NOW.plusSeconds(600)));
        RestrictedDataTokenManager manager = manager(client, new MutableClock(NOW), 60, 10);

        manager.getToken(SHOP_ID, MARKETPLACE_ID, RESOURCES);
        manager.getToken(1002L, MARKETPLACE_ID, RESOURCES);
        manager.getToken(SHOP_ID, "A1PA6795UKMFR9", RESOURCES);
        manager.getToken(SHOP_ID, MARKETPLACE_ID,
                List.of(new RestrictedResource("GET", "/orders/v0/orders/123-1234567-1234567", List.of("buyerInfo"))));

        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(4))
                .requestToken(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("距离过期小于 refresh skew 时提前刷新")
    void refreshesBeforeExpiry() {
        TokensClient client = mock(TokensClient.class);
        when(client.requestToken(anyLong(), anyString(), any()))
                .thenReturn(token("rdt-1", NOW.plusSeconds(120)))
                .thenReturn(token("rdt-2", NOW.plusSeconds(600)));
        MutableClock clock = new MutableClock(NOW);
        RestrictedDataTokenManager manager = manager(client, clock, 60, 10);

        assertEquals("rdt-1", manager.getToken(SHOP_ID, MARKETPLACE_ID, RESOURCES));
        clock.advance(Duration.ofSeconds(61));
        assertEquals("rdt-2", manager.getToken(SHOP_ID, MARKETPLACE_ID, RESOURCES));

        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(2))
                .requestToken(SHOP_ID, MARKETPLACE_ID, RESOURCES);
    }

    @Test
    @DisplayName("并发刷新同一 key 只触发一次 Tokens API 请求")
    void concurrentRefreshIsSingleFlight() throws Exception {
        TokensClient client = mock(TokensClient.class);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(client.requestToken(anyLong(), anyString(), any())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return token("rdt-single", NOW.plusSeconds(600));
        });
        RestrictedDataTokenManager manager = manager(client, new MutableClock(NOW), 60, 10);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            Future<String> first = pool.submit(() -> manager.getToken(SHOP_ID, MARKETPLACE_ID, RESOURCES));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Future<String> second = pool.submit(() -> manager.getToken(SHOP_ID, MARKETPLACE_ID, RESOURCES));
            Future<String> third = pool.submit(() -> manager.getToken(SHOP_ID, MARKETPLACE_ID, RESOURCES));
            release.countDown();

            assertEquals("rdt-single", first.get(5, TimeUnit.SECONDS));
            assertEquals("rdt-single", second.get(5, TimeUnit.SECONDS));
            assertEquals("rdt-single", third.get(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("invalidate 只清除目标 key，不误伤其他租户")
    void invalidateOnlyTargetScope() {
        TokensClient client = mock(TokensClient.class);
        when(client.requestToken(anyLong(), anyString(), any()))
                .thenReturn(token("rdt-1", NOW.plusSeconds(600)))
                .thenReturn(token("rdt-2", NOW.plusSeconds(600)))
                .thenReturn(token("rdt-3", NOW.plusSeconds(600)));
        RestrictedDataTokenManager manager = manager(client, new MutableClock(NOW), 60, 10);

        assertEquals("rdt-1", manager.getToken(SHOP_ID, MARKETPLACE_ID, RESOURCES));
        assertEquals("rdt-2", manager.getToken(1002L, MARKETPLACE_ID, RESOURCES));
        manager.invalidate(SHOP_ID, MARKETPLACE_ID, RESOURCES);
        assertEquals("rdt-3", manager.getToken(SHOP_ID, MARKETPLACE_ID, RESOURCES));
        assertEquals("rdt-2", manager.getToken(1002L, MARKETPLACE_ID, RESOURCES));

        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(3))
                .requestToken(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("缓存达到上限时淘汰最久未使用的 key")
    void cacheIsBounded() {
        TokensClient client = mock(TokensClient.class);
        when(client.requestToken(anyLong(), anyString(), any()))
                .thenReturn(token("rdt-1", NOW.plusSeconds(600)))
                .thenReturn(token("rdt-2", NOW.plusSeconds(600)))
                .thenReturn(token("rdt-3", NOW.plusSeconds(600)))
                .thenReturn(token("rdt-4", NOW.plusSeconds(600)));
        RestrictedDataTokenManager manager = manager(client, new MutableClock(NOW), 60, 2);

        manager.getToken(SHOP_ID, MARKETPLACE_ID, RESOURCES);
        manager.getToken(1002L, MARKETPLACE_ID, RESOURCES);
        manager.getToken(1003L, MARKETPLACE_ID, RESOURCES);
        manager.getToken(SHOP_ID, MARKETPLACE_ID, RESOURCES);

        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(4))
                .requestToken(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("默认关闭时 fail-closed，且不调用 Tokens API")
    void disabledFailsClosed() {
        TokensClient client = mock(TokensClient.class);
        RestrictedDataProperties properties = properties(false, 60, 10);
        RestrictedDataTokenManager manager = new RestrictedDataTokenManager(
                client, properties, new MutableClock(NOW));

        LocalApiException error = assertThrows(LocalApiException.class,
                () -> manager.getToken(SHOP_ID, MARKETPLACE_ID, RESOURCES));

        assertEquals("RESTRICTED_DATA_DISABLED", error.getCode());
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    @DisplayName("token 不进入管理器字符串或异常诊断")
    void tokenDoesNotLeakFromManager() {
        TokensClient client = mock(TokensClient.class);
        when(client.requestToken(anyLong(), anyString(), any())).thenReturn(token("Atza|secret-rdt", NOW.plusSeconds(600)));
        RestrictedDataTokenManager manager = manager(client, new MutableClock(NOW), 60, 10);

        manager.getToken(SHOP_ID, MARKETPLACE_ID, RESOURCES);

        assertFalse(manager.toString().contains("Atza|secret-rdt"));
        assertFalse(manager.toString().contains("orders/v0/orders"));
    }

    private static RestrictedDataTokenManager manager(TokensClient client, Clock clock,
                                                     long skewSeconds, int maxEntries) {
        return new RestrictedDataTokenManager(client, properties(true, skewSeconds, maxEntries), clock);
    }

    private static RestrictedDataProperties properties(boolean enabled, long skewSeconds, int maxEntries) {
        RestrictedDataProperties properties = new RestrictedDataProperties();
        properties.setEnabled(enabled);
        properties.setRefreshSkewSeconds(skewSeconds);
        properties.setMaxCacheEntries(maxEntries);
        return properties;
    }

    private static TokensClient.RestrictedDataToken token(String value, Instant expiresAt) {
        return new TokensClient.RestrictedDataToken(value, expiresAt);
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        private final ZoneId zone = ZoneOffset.UTC;

        private MutableClock(Instant initial) {
            this.now = new AtomicReference<>(initial);
        }

        private void advance(Duration duration) {
            now.updateAndGet(current -> current.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
