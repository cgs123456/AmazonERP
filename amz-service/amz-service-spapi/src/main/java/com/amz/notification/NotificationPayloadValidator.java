package com.amz.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * 通知载荷结构校验。
 * <p>
 * 校验的是「我们订阅时登记的结构是否完整」，不是业务语义。
 * 缺字段 / 非 JSON / 超长一律抛 {@link InvalidNotificationException}：
 * 这类消息重试一万次也还是坏的，正确处置是隔离留证。
 * <p>
 * 日志只允许打印 notificationId 与字段名，不允许打印 payload。
 */
@Slf4j
@Component
public class NotificationPayloadValidator {

    /** 结构类错误码。 */
    public static final String ERR_NOT_JSON = "NOT_JSON";
    public static final String ERR_TOO_LARGE = "PAYLOAD_TOO_LARGE";
    public static final String ERR_MISSING_FIELD = "MISSING_FIELD";
    public static final String ERR_BAD_EVENT_TIME = "BAD_EVENT_TIME";

    private static final String[] REQUIRED_TOP_LEVEL = {
            "NotificationVersion", "NotificationType", "PayloadVersion",
            "EventTime", "Payload", "NotificationMetadata"
    };

    private static final String[] REQUIRED_METADATA = {
            "ApplicationId", "SubscriptionId", "NotificationId"
    };

    private final ObjectMapper objectMapper;

    public NotificationPayloadValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 校验并解析原始通知。
     *
     * @param rawJson  原始 JSON
     * @param maxBytes 允许的最大字节数
     * @throws InvalidNotificationException 结构非法或超过大小上限
     */
    public ValidatedNotification validate(String rawJson, int maxBytes) {
        if (rawJson == null || rawJson.isBlank()) {
            throw new InvalidNotificationException(ERR_MISSING_FIELD, "原始通知为空");
        }
        byte[] bytes = rawJson.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            throw new InvalidNotificationException(ERR_TOO_LARGE,
                    String.format("通知载荷 %d 字节，超过上限 %d 字节", bytes.length, maxBytes));
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(rawJson);
        } catch (JsonProcessingException e) {
            throw new InvalidNotificationException(ERR_NOT_JSON, "通知载荷不是合法 JSON");
        }
        if (root == null || !root.isObject()) {
            throw new InvalidNotificationException(ERR_NOT_JSON, "通知载荷根节点不是 JSON 对象");
        }
        for (String field : REQUIRED_TOP_LEVEL) {
            JsonNode node = root.get(field);
            if (node == null || node.isNull() || (node.isTextual() && node.asText().isBlank())) {
                throw new InvalidNotificationException(ERR_MISSING_FIELD,
                        "通知缺少必填字段 " + field);
            }
        }
        JsonNode metadata = root.get("NotificationMetadata");
        for (String field : REQUIRED_METADATA) {
            JsonNode node = metadata.get(field);
            if (node == null || node.isNull() || (node.isTextual() && node.asText().isBlank())) {
                throw new InvalidNotificationException(ERR_MISSING_FIELD,
                        "NotificationMetadata 缺少必填字段 " + field);
            }
        }
        LocalDateTime eventTime = parseTime(root.get("EventTime").asText(), "EventTime");
        JsonNode publishNode = metadata.get("PublishTime");
        LocalDateTime publishTime = publishNode == null || publishNode.isNull()
                ? null
                : parseTime(publishNode.asText(), "PublishTime");

        return new ValidatedNotification(
                metadata.get("NotificationId").asText(),
                root.get("NotificationType").asText(),
                root.get("PayloadVersion").asText(),
                eventTime,
                publishTime,
                metadata.get("ApplicationId").asText(),
                metadata.get("SubscriptionId").asText(),
                root.get("Payload"),
                bytes.length);
    }

    private LocalDateTime parseTime(String text, String field) {
        try {
            return OffsetDateTime.parse(text)
                    .withOffsetSameInstant(ZoneOffset.UTC)
                    .toLocalDateTime();
        } catch (DateTimeParseException e) {
            throw new InvalidNotificationException(ERR_BAD_EVENT_TIME,
                    field + " 不是 ISO-8601 带偏移量的时间格式：" + text);
        }
    }
}