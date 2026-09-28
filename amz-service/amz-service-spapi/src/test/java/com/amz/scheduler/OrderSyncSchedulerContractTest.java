package com.amz.scheduler;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.amz.client.OrdersClient;
import com.amz.constant.MqConstant;
import com.amz.order.OrderUpsertPublishException;
import com.amz.order.OrderUpsertPublisher;
import com.amz.connector.SpApiEndpointResolver;
import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OrderSyncScheduler：订单 PII 开关与脱敏契约")
class OrderSyncSchedulerContractTest {

    private static final long SHOP_ID = 1001L;
    private static final String MARKETPLACE_ID = "ATVPDKIKX0DER";
    private static final String ORDER_ID = "123-1234567-1234567";
    private static final String BUYER_EMAIL = "buyer@example.com";
    private static final String BUYER_NAME = "Sensitive Buyer";
    private static final String BUYER_ADDRESS = "1 Secret Street";
    private static final String RDT_TOKEN = "Atza|sensitive-rdt";

    @Test
    @DisplayName("开关关闭时继续使用普通 LWA Orders API")
    void disabledUsesLwaOrdersApi() {
        OrdersClient ordersClient = mock(OrdersClient.class);
        when(ordersClient.fetchOrders(eq(SHOP_ID), eq(MARKETPLACE_ID), any(Instant.class), anyList()))
                .thenReturn(List.of(order()));
        when(ordersClient.fetchOrderItems(SHOP_ID, MARKETPLACE_ID, ORDER_ID)).thenReturn(List.of());

        invokeSync(scheduler(ordersClient, false));

        verify(ordersClient).fetchOrders(eq(SHOP_ID), eq(MARKETPLACE_ID), any(Instant.class), anyList());
        verify(ordersClient).fetchOrderItems(SHOP_ID, MARKETPLACE_ID, ORDER_ID);
        verify(ordersClient, never()).fetchOrdersWithRestrictedData(
                anyLong(), anyString(), any(Instant.class), anyList());
        verify(ordersClient, never()).fetchOrderItemsWithRestrictedData(
                anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("开关开启时 Orders 与 OrderItems 都使用受限数据方法")
    void enabledUsesRestrictedDataOrdersApi() {
        OrdersClient ordersClient = mock(OrdersClient.class);
        when(ordersClient.fetchOrdersWithRestrictedData(
                eq(SHOP_ID), eq(MARKETPLACE_ID), any(Instant.class), anyList()))
                .thenReturn(List.of(order()));
        when(ordersClient.fetchOrderItemsWithRestrictedData(SHOP_ID, MARKETPLACE_ID, ORDER_ID))
                .thenReturn(List.of());

        invokeSync(scheduler(ordersClient, true));

        verify(ordersClient).fetchOrdersWithRestrictedData(
                eq(SHOP_ID), eq(MARKETPLACE_ID), any(Instant.class), anyList());
        verify(ordersClient).fetchOrderItemsWithRestrictedData(SHOP_ID, MARKETPLACE_ID, ORDER_ID);
        verify(ordersClient, never()).fetchOrders(
                anyLong(), anyString(), any(Instant.class), anyList());
        verify(ordersClient, never()).fetchOrderItems(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("RDT 失败时不得静默回退普通 LWA Orders API")
    void restrictedFailureDoesNotFallbackToLwa() {
        OrdersClient ordersClient = mock(OrdersClient.class);
        when(ordersClient.fetchOrdersWithRestrictedData(
                eq(SHOP_ID), eq(MARKETPLACE_ID), any(Instant.class), anyList()))
                .thenThrow(new IllegalStateException("RDT unavailable"));

        invokeSync(scheduler(ordersClient, true));

        verify(ordersClient).fetchOrdersWithRestrictedData(
                eq(SHOP_ID), eq(MARKETPLACE_ID), any(Instant.class), anyList());
        verify(ordersClient, never()).fetchOrders(
                anyLong(), anyString(), any(Instant.class), anyList());
    }

    @Test
    @DisplayName("发送失败时不得标记已发布：否则该订单 14 天 TTL 内不再重发（静默丢单）")
    void publishFailureDoesNotMarkPublished() {
        OrderUpsertPublisher publisher = mock(OrderUpsertPublisher.class);
        doThrow(new OrderUpsertPublishException("broker down", new IllegalStateException("broker down")))
                .when(publisher).sendJson(anyString(), anyString(), any());
        OrdersClient ordersClient = mock(OrdersClient.class);
        when(ordersClient.fetchOrders(eq(SHOP_ID), eq(MARKETPLACE_ID), any(Instant.class), anyList()))
                .thenReturn(List.of(order()));
        when(ordersClient.fetchOrderItems(SHOP_ID, MARKETPLACE_ID, ORDER_ID)).thenReturn(List.of());

        OrderSyncScheduler scheduler = scheduler(ordersClient, false, publisher);
        StringRedisTemplate redis =
                (StringRedisTemplate) ReflectionTestUtils.getField(scheduler, "stringRedisTemplate");
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = (ValueOperations<String, String>) redis.opsForValue();

        Logger logger = (Logger) LoggerFactory.getLogger(OrderSyncScheduler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            invokeSync(scheduler);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        // 关键：发送失败 -> 未标记 -> 下一轮可重发。旧实现先标记再发送，等于永久丢单。
        verify(ops, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));

        String logs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
        assertTrue(logs.contains("syncOrders failed"), logs);
        assertFalse(logs.contains(BUYER_EMAIL), logs);
        assertFalse(logs.contains(BUYER_NAME), logs);
        assertFalse(logs.contains(RDT_TOKEN), logs);
    }

    @Test
    @DisplayName("发送成功后才标记已发布，避免故障窗口内的订单被永久跳过")
    void marksPublishedOnlyAfterSend() {
        OrderUpsertPublisher publisher = mock(OrderUpsertPublisher.class);
        OrdersClient ordersClient = mock(OrdersClient.class);
        when(ordersClient.fetchOrders(eq(SHOP_ID), eq(MARKETPLACE_ID), any(Instant.class), anyList()))
                .thenReturn(List.of(order()));
        when(ordersClient.fetchOrderItems(SHOP_ID, MARKETPLACE_ID, ORDER_ID)).thenReturn(List.of());

        OrderSyncScheduler scheduler = scheduler(ordersClient, false, publisher);
        StringRedisTemplate redis =
                (StringRedisTemplate) ReflectionTestUtils.getField(scheduler, "stringRedisTemplate");
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = (ValueOperations<String, String>) redis.opsForValue();

        invokeSync(scheduler);

        verify(publisher).sendJson(eq(MqConstant.SAVE_ORDER_EXCHANGE), eq(""), any());
        verify(publisher).sendJson(eq(MqConstant.PROFIT_EXCHANGE), eq(MqConstant.PROFIT_ROUTING_KEY), any());
        verify(ops).setIfAbsent(eq("amz:sync:published:" + SHOP_ID + ":" + ORDER_ID),
                eq("1"), any(Duration.class));
    }

    @Test
    @DisplayName("保存消息必须携带真实订单明细（不再固定下发空数组）")
    void saveMessageCarriesRealOrderItems() {
        OrderUpsertPublisher publisher = mock(OrderUpsertPublisher.class);
        OrdersClient ordersClient = mock(OrdersClient.class);
        when(ordersClient.fetchOrders(eq(SHOP_ID), eq(MARKETPLACE_ID), any(Instant.class), anyList()))
                .thenReturn(List.of(order()));
        when(ordersClient.fetchOrderItems(SHOP_ID, MARKETPLACE_ID, ORDER_ID))
                .thenReturn(List.of(orderItem()));

        invokeSync(scheduler(ordersClient, false, publisher));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(publisher).sendJson(eq(MqConstant.SAVE_ORDER_EXCHANGE), eq(""), captor.capture());
        Object items = captor.getValue().get("orderItems");
        assertTrue(items instanceof List, "orderItems 必须是列表，实际为：" + items);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itemList = (List<Map<String, Object>>) items;
        assertEquals(1, itemList.size(), "明细行不得丢失：" + itemList);
        assertEquals("SKU-REAL-1", itemList.get(0).get("sellerSku"));
        assertEquals("ORDER-ITEM-1", itemList.get(0).get("amazonOrderItemId"));
        assertEquals("19.99", itemList.get(0).get("itemPrice"));
        assertEquals(2, itemList.get(0).get("quantity"));
    }

    private static OrderSyncScheduler scheduler(OrdersClient ordersClient, boolean piiSyncEnabled) {
        return scheduler(ordersClient, piiSyncEnabled, mock(OrderUpsertPublisher.class));
    }

    private static OrderSyncScheduler scheduler(OrdersClient ordersClient, boolean piiSyncEnabled,
                                               OrderUpsertPublisher publisher) {
        ShopCredentialStore store = mock(ShopCredentialStore.class);
        ShopCredential credential = new ShopCredential();
        credential.setShopId(SHOP_ID);
        credential.setMarketplaceId(MARKETPLACE_ID);
        when(store.getActiveShopIds()).thenReturn(Set.of(SHOP_ID));
        when(store.get(SHOP_ID)).thenReturn(credential);

        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Boolean.TRUE);
        when(redisTemplate.hasKey(anyString())).thenReturn(Boolean.FALSE);

        OrderSyncScheduler scheduler = new OrderSyncScheduler();
        ReflectionTestUtils.setField(scheduler, "shopCredentialStore", store);
        ReflectionTestUtils.setField(scheduler, "ordersClient", ordersClient);
        ReflectionTestUtils.setField(scheduler, "spApiEndpointResolver", SpApiEndpointResolver.officialOnly());
        ReflectionTestUtils.setField(scheduler, "orderUpsertPublisher", publisher);
        ReflectionTestUtils.setField(scheduler, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(scheduler, "orderPiiSyncEnabled", piiSyncEnabled);
        return scheduler;
    }

    private static void invokeSync(OrderSyncScheduler scheduler) {
        ReflectionTestUtils.invokeMethod(scheduler, "doSyncOrders");
    }

    private static JsonObject orderItem() {
        return JsonParser.parseString("""
                {
                  "OrderItemId": "ORDER-ITEM-1",
                  "ASIN": "B0TEST00001",
                  "SellerSKU": "SKU-REAL-1",
                  "Title": "Real Product",
                  "QuantityOrdered": 2,
                  "ItemPrice": {"Amount": "19.99", "CurrencyCode": "USD"},
                  "ItemTax": {"Amount": "1.60", "CurrencyCode": "USD"},
                  "PromotionDiscount": {"Amount": "2.00", "CurrencyCode": "USD"}
                }
                """).getAsJsonObject();
    }

    private static JsonObject order() {
        return JsonParser.parseString("""
                {
                  "AmazonOrderId": "123-1234567-1234567",
                  "OrderStatus": "Unshipped",
                  "OrderTotal": {"Amount": "19.99", "CurrencyCode": "USD"},
                  "BuyerInfo": {
                    "BuyerEmail": "buyer@example.com",
                    "BuyerName": "Sensitive Buyer"
                  }
                }
                """).getAsJsonObject();
    }
}