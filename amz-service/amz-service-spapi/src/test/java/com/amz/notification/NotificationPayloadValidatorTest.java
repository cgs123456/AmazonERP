package com.amz.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 通知载荷结构校验测试。
 * <p>
 * 结构非法的消息重试也一样是坏的，所以校验必须硬失败并给出结构化错误码，
 * 不能靠下游 Handler 各写各的判空。
 */
class NotificationPayloadValidatorTest {

    private static final String VALID_JSON = """
            {
              "NotificationVersion": "2022-02-02",
              "NotificationType": "ORDER_CHANGE",
              "PayloadVersion": "2022-02-02",
              "EventTime": "2026-09-20T10:05:00.000Z",
              "Payload": {
                "OrderChangeNotification": {
                  "AmazonOrderId": "903-1000001-2000001",
                  "OrderStatus": "Shipped"
                }
              },
              "NotificationMetadata": {
                "ApplicationId": "amzn1.sp.solution.00000000",
                "SubscriptionId": "SUB-1",
                "NotificationId": "NOTIF-1",
                "PublishTime": "2026-09-20T10:05:05.000Z"
              }
            }
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final NotificationPayloadValidator validator = new NotificationPayloadValidator(objectMapper);

    @Test
    void validNotificationIsParsed() {
        ValidatedNotification n = validator.validate(VALID_JSON, 262144);
        assertEquals("NOTIF-1", n.notificationId());
        assertEquals("ORDER_CHANGE", n.notificationType());
        assertEquals("SUB-1", n.subscriptionId());
        assertEquals("amzn1.sp.solution.00000000", n.applicationId());
        assertNotNull(n.eventTime());
        assertEquals(2026, n.eventTime().getYear());
        assertEquals(10, n.eventTime().getHour());
        assertNotNull(n.publishTime());
        assertNotNull(n.payload());
    }

    @Test
    void nonJsonIsRejected() {
        InvalidNotificationException e = assertThrows(InvalidNotificationException.class,
                () -> validator.validate("{not json", 262144));
        assertEquals(NotificationPayloadValidator.ERR_NOT_JSON, e.getErrorCode());
    }

    @Test
    void oversizedPayloadIsRejected() {
        InvalidNotificationException e = assertThrows(InvalidNotificationException.class,
                () -> validator.validate(VALID_JSON, 64));
        assertEquals(NotificationPayloadValidator.ERR_TOO_LARGE, e.getErrorCode());
    }

    @Test
    void missingTopLevelFieldsAreRejected() {
        for (String field : new String[]{"NotificationVersion", "NotificationType",
                "PayloadVersion", "EventTime", "Payload", "NotificationMetadata"}) {
            String json = removeTopLevelField(field);
            InvalidNotificationException e = assertThrows(InvalidNotificationException.class,
                    () -> validator.validate(json, 262144), "缺少字段 " + field + " 必须被拒绝");
            assertEquals(NotificationPayloadValidator.ERR_MISSING_FIELD, e.getErrorCode());
        }
    }

    @Test
    void missingMetadataFieldsAreRejected() {
        for (String field : new String[]{"ApplicationId", "SubscriptionId", "NotificationId"}) {
            String json = removeMetadataField(field);
            InvalidNotificationException e = assertThrows(InvalidNotificationException.class,
                    () -> validator.validate(json, 262144), "NotificationMetadata 缺少 " + field + " 必须被拒绝");
            assertEquals(NotificationPayloadValidator.ERR_MISSING_FIELD, e.getErrorCode());
        }
    }

    @Test
    void badEventTimeIsRejected() {
        String json = VALID_JSON.replace("2026-09-20T10:05:00.000Z", "2026/09/20 10:05:00");
        InvalidNotificationException e = assertThrows(InvalidNotificationException.class,
                () -> validator.validate(json, 262144));
        assertEquals(NotificationPayloadValidator.ERR_BAD_EVENT_TIME, e.getErrorCode());
    }

    private String removeTopLevelField(String field) {
        JsonNode node = readTree();
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove(field);
        return node.toString();
    }

    private String removeMetadataField(String field) {
        JsonNode node = readTree();
        ((com.fasterxml.jackson.databind.node.ObjectNode) node.get("NotificationMetadata")).remove(field);
        return node.toString();
    }

    private JsonNode readTree() {
        try {
            return objectMapper.readTree(VALID_JSON);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}