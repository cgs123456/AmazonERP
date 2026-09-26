package com.amz.notification;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/**
 * 通过结构校验的通知（尚未归属店铺、尚未解密落库）。
 *
 * @param notificationId   入口去重键
 * @param notificationType 通知类型
 * @param payloadVersion   payload 版本
 * @param eventTime        事件时间（已归一到 UTC），乱序判断的唯一依据
 * @param publishTime      Amazon 发布时间（可为空）
 * @param applicationId    应用 ID
 * @param subscriptionId   订阅 ID，用于反查店铺
 * @param payload          业务载荷节点（可能含 PII，禁止直接进日志）
 * @param payloadBytes     原始字节数
 */
public record ValidatedNotification(String notificationId,
                                    String notificationType,
                                    String payloadVersion,
                                    LocalDateTime eventTime,
                                    LocalDateTime publishTime,
                                    String applicationId,
                                    String subscriptionId,
                                    JsonNode payload,
                                    int payloadBytes) {
}