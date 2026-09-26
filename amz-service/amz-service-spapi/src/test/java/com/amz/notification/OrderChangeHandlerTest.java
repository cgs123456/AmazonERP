package com.amz.notification;

import com.amz.order.OrderUpsertMessage;
import com.amz.order.OrderUpsertPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("OrderChangeHandler：订单变更通知 -> 订单 upsert 消息")
class OrderChangeHandlerTest {

    private static final Long SHOP_ID = 7L;
    private static final String ORDER_ID = "123-1234567-1234567";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OrderUpsertPublisher publisher = mock(OrderUpsertPublisher.class);
    private final OrderChangeHandler handler = new OrderChangeHandler(publisher);

    @Test
    @DisplayName("类型匹配：只认 ORDER_CHANGE")
    void supportsOnlyOrderChange() {
        assertTrue(handler.supports("ORDER_CHANGE"));
        assertTrue(handler.supports("order_change"));
        assertFalse(handler.supports("REPORT_PROCESSING_FINISHED"));
        assertFalse(handler.supports(null));
    }

    @Test
    @DisplayName("正常通知：发布 upsert 消息，幂等键含店铺/订单号/事件时间/类型")
    void publishesUpsertMessage() {
        LocalDateTime eventTime = LocalDateTime.of(2026, 9, 26, 10, 30, 0);

        handler.handle(context(payload("""
                {"OrderChangeNotification":{"AmazonOrderId":"123-1234567-1234567",
                 "OrderStatus":"Shipped","FulfillmentChannel":"MFN","ShipServiceLevel":"Std"}}
                """), SHOP_ID, eventTime));

        OrderUpsertMessage msg = capturedMessage();
        assertEquals(SHOP_ID, msg.shopId());
        assertEquals(ORDER_ID, msg.amazonOrderId());
        assertEquals("Shipped", msg.orderStatus());
        assertEquals("MFN", msg.fulfillmentChannel());
        assertEquals(eventTime, msg.eventTime());
        assertEquals(SHOP_ID + ":" + ORDER_ID + ":" + eventTime + ":ORDER_CHANGE", msg.idempotencyKey());
        // 有 eventTime 才判定为通知驱动，下游据此决定是否做「较新才覆盖」
        assertEquals(OrderUpsertMessage.SOURCE_NOTIFICATION, msg.toBodyMap().get("source"));
        assertTrue(msg.toBodyMap().containsKey("buyerInfo"), "消费端按 buyerInfo 解析，字段不能缺");
    }

    @Test
    @DisplayName("事件时间缺失：用当前时间兜底，保证幂等键不出现 null")
    void missingEventTimeFallsBackToNow() {
        handler.handle(context(payload("""
                {"OrderChangeNotification":{"AmazonOrderId":"123-1234567-1234567","OrderStatus":"Unshipped"}}
                """), SHOP_ID, null));

        OrderUpsertMessage msg = capturedMessage();
        assertNotNull(msg.eventTime());
        assertFalse(msg.idempotencyKey().contains("null"));
    }

    @Test
    @DisplayName("缺少 OrderChangeNotification 节点：不可重试（DLQ），重放无意义")
    void missingNodeIsFatal() {
        NotificationProcessingException ex = assertThrows(NotificationProcessingException.class,
                () -> handler.handle(context(payload("{}"), SHOP_ID, LocalDateTime.now())));

        assertFalse(ex.isRetryable());
        assertNull(ex.getTerminalStatus(), "事件本身坏了才走 DLQ，与 UNSUPPORTED 区分开");
        assertEquals(OrderChangeHandler.ERR_MISSING_NODE, ex.getErrorCode());
        verify(publisher, never()).publish(any());
    }

    @Test
    @DisplayName("缺少 AmazonOrderId / OrderStatus：不可重试，绝不发布无标识消息")
    void missingKeyFieldsAreFatal() {
        NotificationProcessingException missingId = assertThrows(NotificationProcessingException.class,
                () -> handler.handle(context(payload("""
                        {"OrderChangeNotification":{"OrderStatus":"Shipped"}}
                        """), SHOP_ID, LocalDateTime.now())));
        assertEquals(OrderChangeHandler.ERR_MISSING_ORDER_ID, missingId.getErrorCode());
        assertFalse(missingId.isRetryable());

        NotificationProcessingException missingStatus = assertThrows(NotificationProcessingException.class,
                () -> handler.handle(context(payload("""
                        {"OrderChangeNotification":{"AmazonOrderId":"123-1234567-1234567"}}
                        """), SHOP_ID, LocalDateTime.now())));
        assertEquals(OrderChangeHandler.ERR_MISSING_STATUS, missingStatus.getErrorCode());

        verify(publisher, never()).publish(any());
    }

    @Test
    @DisplayName("未归属店铺：终态 UNRESOLVED_SUBSCRIPTION，补映射后可重放，不丢事件")
    void unresolvedShopIsTerminalNotDlq() {
        NotificationProcessingException ex = assertThrows(NotificationProcessingException.class,
                () -> handler.handle(context(payload("""
                        {"OrderChangeNotification":{"AmazonOrderId":"123-1234567-1234567","OrderStatus":"Shipped"}}
                        """), null, LocalDateTime.now())));

        assertEquals(NotificationInboxStatus.UNRESOLVED_SUBSCRIPTION, ex.getTerminalStatus());
        assertFalse(ex.isRetryable());
        verify(publisher, never()).publish(any());
    }

    private OrderUpsertMessage capturedMessage() {
        ArgumentCaptor<OrderUpsertMessage> captor = ArgumentCaptor.forClass(OrderUpsertMessage.class);
        verify(publisher).publish(captor.capture());
        return captor.getValue();
    }

    private static com.fasterxml.jackson.databind.JsonNode payload(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static NotificationContext context(com.fasterxml.jackson.databind.JsonNode payload,
                                               Long shopId, LocalDateTime eventTime) {
        return new NotificationContext(1L, "NOTIF-1", "ORDER_CHANGE", eventTime, eventTime,
                shopId, "ATVPDKIKX0DER", "sub-1", "1.0", false, payload, "{}");
    }

}