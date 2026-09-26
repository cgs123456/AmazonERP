package com.amz.scheduler;

import com.amz.client.NotificationsClient;
import com.amz.mapper.NotificationSubscriptionMapper;
import com.amz.model.NotificationSubscriptionEntity;
import com.amz.notification.NotificationMetrics;
import com.amz.notification.NotificationProperties;
import com.amz.notification.NotificationSubscriptionDrift;
import com.amz.notification.NotificationSubscriptionRegistry;
import com.google.gson.JsonObject;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationSubscriptionSyncSchedulerTest {

    private static final String SUB_ID = "SUB-0001";

    private NotificationSubscriptionMapper mapper;
    private NotificationsClient client;
    private NotificationSubscriptionRegistry registry;
    private NotificationMetrics metrics;
    private NotificationProperties properties;
    private NotificationSubscriptionSyncScheduler scheduler;

    @BeforeEach
    void setUp() {
        mapper = mock(NotificationSubscriptionMapper.class);
        client = mock(NotificationsClient.class);
        registry = mock(NotificationSubscriptionRegistry.class);
        metrics = new NotificationMetrics(new SimpleMeterRegistry());
        properties = new NotificationProperties();
        properties.setEnabled(true);
        scheduler = new NotificationSubscriptionSyncScheduler(mapper, client, registry, metrics, properties);
    }

    @Test
    @DisplayName("通知链路未启用时不对账：不需要打 Amazon API，也不产生假告警")
    void skipsWhenDisabled() {
        properties.setEnabled(false);

        assertTrue(scheduler.sync().isEmpty());
        verify(mapper, never()).selectList(any());
    }

    @Test
    @DisplayName("Amazon 侧一致时没有漂移，也不动缓存")
    void noDriftWhenRemoteMatches() {
        when(mapper.selectList(any())).thenReturn(List.of(row(SUB_ID, 1001L, "ORDER_CHANGE", 0)));
        when(client.getSubscriptionById(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(remote(SUB_ID));

        List<NotificationSubscriptionDrift> report = scheduler.sync();

        assertTrue(report.isEmpty());
        verify(registry, never()).invalidate(anyString());
        assertEquals(List.of(), scheduler.lastReport());
    }

    @Test
    @DisplayName("Amazon 侧查不到时判定 MISSING_REMOTELY，并立即失效缓存")
    void detectsMissingRemotely() {
        when(mapper.selectList(any())).thenReturn(List.of(row(SUB_ID, 1001L, "ORDER_CHANGE", 0)));
        when(client.getSubscriptionById(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(new JsonObject());

        List<NotificationSubscriptionDrift> report = scheduler.sync();

        assertEquals(1, report.size());
        assertEquals(NotificationSubscriptionDrift.Kind.MISSING_REMOTELY, report.get(0).kind());
        verify(registry, times(1)).invalidate(SUB_ID);
        assertEquals(1.0, metrics.counter("subscription.drift", "kind", "MISSING_REMOTELY").count());
    }

    @Test
    @DisplayName("校验调用失败绝不当成「不存在」：不失效缓存、不改本地状态")
    void verifyFailureIsNeverTreatedAsMissing() {
        when(mapper.selectList(any())).thenReturn(List.of(row(SUB_ID, 1001L, "ORDER_CHANGE", 0)));
        when(client.getSubscriptionById(anyLong(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("无有效凭证"));

        List<NotificationSubscriptionDrift> report = scheduler.sync();

        assertEquals(1, report.size());
        assertEquals(NotificationSubscriptionDrift.Kind.VERIFY_FAILED, report.get(0).kind());
        // 校验失败可能只是限流或网络抖动，失效缓存会让后续事件被判成未映射
        verify(registry, never()).invalidate(anyString());
        assertEquals(1.0, metrics.counter("subscription.drift", "kind", "VERIFY_FAILED").count());
    }

    @Test
    @DisplayName("本地映射自身缺字段时不调用 API，直接记为无法校验")
    void incompleteLocalRecord() {
        when(mapper.selectList(any())).thenReturn(List.of(row("  ", null, null, 0)));

        List<NotificationSubscriptionDrift> report = scheduler.sync();

        assertEquals(1, report.size());
        assertEquals(NotificationSubscriptionDrift.Kind.INCOMPLETE_LOCAL_RECORD, report.get(0).kind());
        verify(client, never()).getSubscriptionById(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("合成演练数据不得打真实 Amazon API：污染配额且必然产生假漂移")
    void skipsSyntheticRows() {
        when(mapper.selectList(any())).thenReturn(List.of(row(SUB_ID, 1001L, "ORDER_CHANGE", 1)));

        assertTrue(scheduler.sync().isEmpty());
        verify(client, never()).getSubscriptionById(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("报告会留存到下一轮，供运维读取")
    void keepsLastReport() {
        when(mapper.selectList(any())).thenReturn(List.of(row(SUB_ID, 1001L, "ORDER_CHANGE", 0)));
        when(client.getSubscriptionById(anyLong(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("限流"));

        scheduler.sync();

        assertEquals(1, scheduler.lastReport().size());
        assertSame(scheduler.lastReport(), scheduler.lastReport());
    }

    @Test
    @DisplayName("定时入口吞掉异常：旁路对账的一次抖动不能影响同进程其它调度任务")
    void runSwallowsExceptions() {
        when(mapper.selectList(any())).thenThrow(new IllegalStateException("数据库不可用"));

        scheduler.run();

        assertTrue(scheduler.lastReport().isEmpty());
    }

    private static JsonObject remote(String subscriptionId) {
        JsonObject json = new JsonObject();
        json.addProperty("subscriptionId", subscriptionId);
        json.addProperty("payloadVersion", "2022-02-02");
        json.addProperty("destinationId", "DEST-1");
        return json;
    }

    private static NotificationSubscriptionEntity row(String subscriptionId,
                                                      Long shopId,
                                                      String notificationType,
                                                      Integer synthetic) {
        NotificationSubscriptionEntity entity = new NotificationSubscriptionEntity();
        entity.setSubscriptionId(subscriptionId);
        entity.setDestinationId("DEST-1");
        entity.setShopId(shopId);
        entity.setMarketplaceId("ATVPDKIKX0DER");
        entity.setNotificationType(notificationType);
        entity.setStatus(NotificationSubscriptionRegistry.STATUS_ACTIVE);
        entity.setSynthetic(synthetic);
        return entity;
    }
}