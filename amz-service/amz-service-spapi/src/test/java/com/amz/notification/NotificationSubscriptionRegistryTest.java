package com.amz.notification;

import com.amz.mapper.NotificationSubscriptionMapper;
import com.amz.model.NotificationSubscriptionEntity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationSubscriptionRegistryTest {

    private static final String SUB_ID = "SUB-0001";
    private static final long TTL = 30_000L;

    private NotificationSubscriptionMapper mapper;
    private NotificationMetrics metrics;
    private AtomicLong nowMillis;
    private NotificationSubscriptionRegistry registry;

    @BeforeEach
    void setUp() {
        mapper = mock(NotificationSubscriptionMapper.class);
        metrics = new NotificationMetrics(new SimpleMeterRegistry());
        nowMillis = new AtomicLong(1_700_000_000_000L);
        registry = new NotificationSubscriptionRegistry(mapper, metrics, TTL, nowMillis::get);
    }

    @Test
    @DisplayName("命中映射时返回店铺绑定，并在 TTL 内复用缓存")
    void resolvesAndCaches() {
        when(mapper.selectList(any())).thenReturn(List.of(activeRow(SUB_ID, 1001L)));

        Optional<NotificationShopBinding> first = registry.resolve(SUB_ID);
        Optional<NotificationShopBinding> second = registry.resolve(SUB_ID);

        assertTrue(first.isPresent());
        assertEquals(1001L, first.get().shopId());
        assertEquals("ATVPDKIKX0DER", first.get().marketplaceId());
        assertEquals("DEST-1", first.get().destinationId());
        assertEquals(first.get(), second.get());
        verify(mapper, times(1)).selectList(any());
    }

    @Test
    @DisplayName("查不到映射时返回 empty 并计入未解析指标；空 subscriptionId 不查库但也要计数")
    void unresolvedIsAFirstClassOutcome() {
        when(mapper.selectList(any())).thenReturn(List.of());

        assertTrue(registry.resolve(SUB_ID).isEmpty());
        assertTrue(registry.resolve(null).isEmpty());
        assertTrue(registry.resolve("   ").isEmpty());

        assertEquals(3.0, metrics.counter("unresolved.subscription").count(),
                "缺少 subscriptionId 的事件同样要计未解析，否则会被静默吞掉");
        verify(mapper, times(1)).selectList(any());
    }

    @Test
    @DisplayName("负缓存：查不到的订阅不会在突发流量下反复打库")
    void negativeCaching() {
        when(mapper.selectList(any())).thenReturn(List.of());

        registry.resolve(SUB_ID);
        registry.resolve(SUB_ID);
        registry.resolve(SUB_ID);

        verify(mapper, times(1)).selectList(any());
        assertEquals(1, registry.cachedEntries());
        assertEquals(1.0, metrics.counter("unresolved.subscription").count());
    }

    @Test
    @DisplayName("TTL 过期后重新查询：补上映射最迟一个 TTL 生效，不需要重启")
    void expiresAfterTtl() {
        when(mapper.selectList(any())).thenReturn(List.of());
        registry.resolve(SUB_ID);

        nowMillis.addAndGet(TTL);
        Mockito.doReturn(List.of(activeRow(SUB_ID, 1002L))).when(mapper).selectList(any());
        Optional<NotificationShopBinding> after = registry.resolve(SUB_ID);

        assertTrue(after.isPresent());
        assertEquals(1002L, after.get().shopId());
        verify(mapper, times(2)).selectList(any());
    }

    @Test
    @DisplayName("命中多条有效映射时拒绝猜测归属：猜错就是跨店串数据")
    void refusesToGuessWhenAmbiguous() {
        when(mapper.selectList(any())).thenReturn(List.of(activeRow(SUB_ID, 1001L), activeRow(SUB_ID, 1002L)));

        assertTrue(registry.resolve(SUB_ID).isEmpty());
    }

    @Test
    @DisplayName("非 ACTIVE 的映射不能用来归属：已下线店铺继续收数据是最难排查的事故之一")
    void rejectsNonActiveRow() {
        NotificationSubscriptionEntity deleted = activeRow(SUB_ID, 1001L);
        deleted.setStatus("DELETED");
        when(mapper.selectList(any())).thenReturn(List.of(deleted));

        assertTrue(registry.resolve(SUB_ID).isEmpty());
    }

    @Test
    @DisplayName("映射缺 shopId 时视为未映射，不能带着 null shopId 继续处理")
    void rejectsRowWithoutShopId() {
        NotificationSubscriptionEntity row = activeRow(SUB_ID, null);
        when(mapper.selectList(any())).thenReturn(List.of(row));

        assertTrue(registry.resolve(SUB_ID).isEmpty());
    }

    @Test
    @DisplayName("数据库异常时按未映射处理：宁可卡住等重试，也不能猜店铺")
    void dbFailureTreatedAsUnresolved() {
        when(mapper.selectList(any())).thenThrow(new IllegalStateException("连接被重置"));

        assertTrue(registry.resolve(SUB_ID).isEmpty());
        assertEquals(1.0, metrics.counter("unresolved.subscription").count());
    }

    @Test
    @DisplayName("数据库不可用后的失败结果不进入缓存：不能把一次抖动缓存成 30 秒未映射")
    void dbFailureIsNotCached() {
        when(mapper.selectList(any())).thenThrow(new IllegalStateException("连接被重置"));
        registry.resolve(SUB_ID);
        assertEquals(0, registry.cachedEntries());

        Mockito.doReturn(List.of(activeRow(SUB_ID, 1001L))).when(mapper).selectList(any());
        assertTrue(registry.resolve(SUB_ID).isPresent());
        verify(mapper, times(2)).selectList(any());
    }

    @Test
    @DisplayName("invalidate 立即失效单个/全部缓存，对账补映射后不必等 TTL")
    void invalidation() {
        when(mapper.selectList(any())).thenReturn(List.of(activeRow(SUB_ID, 1001L)));
        registry.resolve(SUB_ID);
        registry.resolve("SUB-0002");

        registry.invalidate(SUB_ID);
        registry.resolve(SUB_ID);
        verify(mapper, times(3)).selectList(any());

        registry.invalidateAll();
        assertEquals(0, registry.cachedEntries());
    }

    @Test
    @DisplayName("invalidate(null) 不应抛异常")
    void invalidateNullIsSafe() {
        registry.invalidate(null);
        verify(mapper, never()).selectList(any());
    }

    private static NotificationSubscriptionEntity activeRow(String subscriptionId, Long shopId) {
        NotificationSubscriptionEntity entity = new NotificationSubscriptionEntity();
        entity.setSubscriptionId(subscriptionId);
        entity.setDestinationId("DEST-1");
        entity.setShopId(shopId);
        entity.setMarketplaceId("ATVPDKIKX0DER");
        entity.setNotificationType("ORDER_CHANGE");
        entity.setStatus(NotificationSubscriptionRegistry.STATUS_ACTIVE);
        entity.setSynthetic(0);
        return entity;
    }
}