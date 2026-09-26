package com.amz.notification;

import com.amz.model.NotificationInboxEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@DisplayName("NotificationEventDispatcher：类型路由与终态区分")
class NotificationEventDispatcherTest {

    private static final String RAW = "{\"Payload\":{\"OrderChangeNotification\":{\"AmazonOrderId\":\"123\"}}}";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("已知类型：路由到专属 Handler，不经过兜底")
    void knownTypeRoutesToSpecificHandler() {
        RecordingHandler orderChange = new RecordingHandler("ORDER_CHANGE");
        FallbackHandler fallback = new FallbackHandler();
        NotificationEventDispatcher dispatcher = dispatcher(orderChange, fallback);

        dispatcher.process(event("ORDER_CHANGE"), RAW);

        assertEquals(1, orderChange.calls);
        assertEquals(0, fallback.calls);
    }

    @Test
    @DisplayName("兜底 Handler 排在注入列表首位时也不能抢走事件（顺序无关）")
    void fallbackFirstDoesNotShadowSpecificHandler() {
        RecordingHandler orderChange = new RecordingHandler("ORDER_CHANGE");
        FallbackHandler fallback = new FallbackHandler();
        NotificationEventDispatcher dispatcher = dispatcher(fallback, orderChange);

        dispatcher.process(event("ORDER_CHANGE"), RAW);

        assertEquals(1, orderChange.calls, "顺序错了会把所有事件判成 UNSUPPORTED，这是最隐蔽的一类线上事故");
        assertEquals(0, fallback.calls);
    }

    @Test
    @DisplayName("未知类型：终态 UNSUPPORTED，不进 DLQ，补 Handler 后可批量重放")
    void unknownTypeIsUnsupportedNotDlq() {
        FallbackHandler fallback = new FallbackHandler();
        NotificationEventDispatcher dispatcher = dispatcher(new RecordingHandler("ORDER_CHANGE"), fallback);

        NotificationProcessingException ex = assertThrows(NotificationProcessingException.class,
                () -> dispatcher.process(event("BRAND_NEW_TYPE"), RAW));

        assertEquals(NotificationInboxStatus.UNSUPPORTED, ex.getTerminalStatus());
        assertEquals(NotificationEventDispatcher.ERR_UNSUPPORTED_TYPE, ex.getErrorCode());
        assertFalse(ex.isRetryable());
        assertEquals(1, fallback.calls);
    }

    @Test
    @DisplayName("一个 Handler 都没有：仍判 UNSUPPORTED 而非 NPE 或静默成功")
    void noHandlerAtAllIsUnsupported() {
        NotificationEventDispatcher dispatcher = dispatcher();

        NotificationProcessingException ex = assertThrows(NotificationProcessingException.class,
                () -> dispatcher.process(event("ORDER_CHANGE"), RAW));

        assertEquals(NotificationInboxStatus.UNSUPPORTED, ex.getTerminalStatus());
    }

    @Test
    @DisplayName("正文为空 / 非 JSON / 缺 Payload：不可重试进 DLQ（事件本身坏了）")
    void brokenPayloadIsFatal() {
        NotificationEventDispatcher dispatcher = dispatcher(new RecordingHandler("ORDER_CHANGE"));

        for (String raw : new String[]{null, "   ", "not-json", "{}"}) {
            NotificationProcessingException ex = assertThrows(NotificationProcessingException.class,
                    () -> dispatcher.process(event("ORDER_CHANGE"), raw), "raw=" + raw);
            assertEquals(NotificationEventDispatcher.ERR_EMPTY_PAYLOAD, ex.getErrorCode());
            assertFalse(ex.isRetryable());
            assertNull(ex.getTerminalStatus(), "坏事件走 DLQ，不能和 UNSUPPORTED 混在一起");
        }
    }

    @Test
    @DisplayName("上下文字段透传：Handler 能拿到店铺、订阅与事件时间")
    void contextCarriesShopAndSubscription() {
        RecordingHandler handler = new RecordingHandler("ORDER_CHANGE");
        LocalDateTime eventTime = LocalDateTime.of(2026, 9, 26, 10, 0, 0);
        NotificationInboxEntity e = event("ORDER_CHANGE");
        e.setEventTime(eventTime);
        e.setShopId(9L);
        e.setSubscriptionId("sub-9");
        e.setMarketplaceId("ATVPDKIKX0DER");

        dispatcher(handler).process(e, RAW);

        NotificationContext ctx = handler.lastContext;
        assertEquals(9L, ctx.shopId());
        assertEquals("sub-9", ctx.subscriptionId());
        assertEquals(eventTime, ctx.eventTime());
        assertEquals("ATVPDKIKX0DER", ctx.marketplaceId());
        assertTrue(ctx.payload().has("OrderChangeNotification"));
        assertFalse(ctx.synthetic());
    }

    @Test
    @DisplayName("真实兜底实现：任何类型都支持，handle 抛 UNSUPPORTED 终态")
    void unsupportedTypeHandlerContract() {
        UnsupportedTypeHandler handler = new UnsupportedTypeHandler();

        assertTrue(handler.supports("WHATEVER"));
        assertTrue(handler.supports(null));
        assertTrue(handler.fallback());
        NotificationProcessingException ex = assertThrows(NotificationProcessingException.class,
                () -> handler.handle(new NotificationContext(1L, "N-1", "WHATEVER", null, null,
                        null, null, null, null, false, null, "{}")));
        assertEquals(NotificationInboxStatus.UNSUPPORTED, ex.getTerminalStatus());
    }

    private NotificationEventDispatcher dispatcher(NotificationEventHandler... handlers) {
        return new NotificationEventDispatcher(objectMapper, List.of(handlers));
    }

    private static NotificationInboxEntity event(String type) {
        NotificationInboxEntity e = new NotificationInboxEntity();
        e.setId(1L);
        e.setNotificationId("NOTIF-1");
        e.setNotificationType(type);
        e.setShopId(7L);
        return e;
    }

    /** 记录调用的专属 Handler。 */
    private static final class RecordingHandler implements NotificationEventHandler {
        private final String type;
        private int calls;
        private NotificationContext lastContext;

        private RecordingHandler(String type) {
            this.type = type;
        }

        @Override
        public boolean supports(String notificationType) {
            return type.equalsIgnoreCase(notificationType);
        }

        @Override
        public void handle(NotificationContext ctx) {
            calls++;
            lastContext = ctx;
        }
    }

    /** 模拟兜底 Handler：支持所有类型。 */
    private static final class FallbackHandler implements NotificationEventHandler {
        private int calls;

        @Override
        public boolean supports(String notificationType) {
            return true;
        }

        @Override
        public boolean fallback() {
            return true;
        }

        @Override
        public void handle(NotificationContext ctx) {
            calls++;
            throw NotificationProcessingException.terminal(NotificationInboxStatus.UNSUPPORTED,
                    "UNSUPPORTED_TYPE", "兜底");
        }
    }
}