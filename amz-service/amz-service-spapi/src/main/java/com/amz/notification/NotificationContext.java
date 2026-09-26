package com.amz.notification;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/**
 * 交给 Handler 的事件上下文。
 * <p>
 * 刻意不含原始 JSON 全量以外的敏感字段：Handler 只拿到已解析的 {@code payload} 节点，
 * 需要原始串时再用 {@code rawPayloadJson}。这样日志与异常里不会顺手把 PII 打出去。
 *
 * @param payload 已解析的 Payload 节点；超限事件未存正文时为 null
 */
public record NotificationContext(Long inboxId,
                                  String notificationId,
                                  String notificationType,
                                  LocalDateTime eventTime,
                                  LocalDateTime publishTime,
                                  Long shopId,
                                  String marketplaceId,
                                  String subscriptionId,
                                  String payloadVersion,
                                  boolean synthetic,
                                  JsonNode payload,
                                  String rawPayloadJson) {
}