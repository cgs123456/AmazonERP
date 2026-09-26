package com.amz.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合成事件源测试。
 * <p>
 * 断言的重点不是「能生成事件」，而是「生成的东西可以被识别为合成数据」——
 * 演练数据一旦混进生产业务表，对账与财务都会出错，而且极难追溯。
 */
class MockNotificationEventSourceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final NotificationPayloadValidator validator = new NotificationPayloadValidator(objectMapper);

    @Test
    void defaultScenarioProducesOrderChangeEvents() {
        MockNotificationEventSource source = new MockNotificationEventSource(objectMapper, "order-change");
        assertEquals(3, source.size());
        List<NotificationEnvelope> batch = source.poll(10);
        assertEquals(3, batch.size());
        for (NotificationEnvelope envelope : batch) {
            assertTrue(envelope.synthetic(), "合成事件源产出的事件必须标记 synthetic");
            assertTrue(envelope.rawJson().contains(MockNotificationEventSource.SYNTHETIC_MARKER_VALUE),
                    "载荷必须自带 SYNTHETIC 标记");
            ValidatedNotification n = validator.validate(envelope.rawJson(), 262144);
            assertEquals("ORDER_CHANGE", n.notificationType());
        }
    }

    @Test
    void pollRespectsMaxMessages() {
        MockNotificationEventSource source = new MockNotificationEventSource(objectMapper, "order-change");
        assertEquals(2, source.poll(2).size());
        assertEquals(1, source.poll(2).size());
        assertTrue(source.poll(2).isEmpty(), "事件流必须有限，取完后返回空而不是无限刷事件");
    }

    @Test
    void duplicateScenarioRepeatsSameNotificationId() {
        MockNotificationEventSource source = new MockNotificationEventSource(objectMapper, "duplicate");
        List<NotificationEnvelope> batch = source.poll(10);
        assertEquals(2, batch.size());
        String first = validator.validate(batch.get(0).rawJson(), 262144).notificationId();
        String second = validator.validate(batch.get(1).rawJson(), 262144).notificationId();
        assertEquals(first, second, "duplicate 场景必须产出相同 notificationId 才能验证去重");
    }

    @Test
    void unsupportedScenarioProducesUnknownType() {
        MockNotificationEventSource source = new MockNotificationEventSource(objectMapper, "unsupported");
        NotificationEnvelope envelope = source.poll(1).get(0);
        ValidatedNotification n = validator.validate(envelope.rawJson(), 262144);
        assertEquals("SOME_UNSUPPORTED_NOTIFICATION", n.notificationType());
    }

    @Test
    void feedReportScenarioProducesBothTypes() {
        MockNotificationEventSource source = new MockNotificationEventSource(objectMapper, "feed-report");
        List<NotificationEnvelope> batch = source.poll(10);
        assertEquals(2, batch.size());
        assertEquals("FEED_PROCESSING_FINISHED",
                validator.validate(batch.get(0).rawJson(), 262144).notificationType());
        assertEquals("REPORT_PROCESSING_FINISHED",
                validator.validate(batch.get(1).rawJson(), 262144).notificationType());
    }

    @Test
    void acknowledgeRecordsHandles() {
        MockNotificationEventSource source = new MockNotificationEventSource(objectMapper, "order-change");
        List<NotificationEnvelope> batch = source.poll(2);
        source.acknowledge(List.of(batch.get(0).receiptHandle()));
        assertTrue(source.acknowledgedHandles().contains(batch.get(0).receiptHandle()));
        assertFalse(source.acknowledgedHandles().contains(batch.get(1).receiptHandle()),
                "未成功落库的事件不得被 ack，否则消息会被删除且事件丢失");
    }

    @Test
    void generatedPayloadHasExpectedShape() {
        MockNotificationEventSource source = new MockNotificationEventSource(objectMapper, "order-change");
        ValidatedNotification n = validator.validate(source.poll(1).get(0).rawJson(), 262144);
        JsonNode orderChange = n.payload().get("OrderChangeNotification");
        assertNotNull(orderChange, "ORDER_CHANGE 载荷结构必须是 Payload.OrderChangeNotification");
        assertTrue(orderChange.get("AmazonOrderId").asText().startsWith("903-"));
        assertNotNull(orderChange.get("OrderStatus"));
    }

    @Test
    void unknownScenarioFallsBackToOrderChange() {
        MockNotificationEventSource source = new MockNotificationEventSource(objectMapper, "no-such-scenario");
        assertEquals("ORDER_CHANGE",
                validator.validate(source.poll(1).get(0).rawJson(), 262144).notificationType());
    }
}