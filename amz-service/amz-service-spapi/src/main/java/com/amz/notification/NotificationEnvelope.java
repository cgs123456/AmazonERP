package com.amz.notification;

/**
 * 事件源返回的单条原始通知。
 *
 * @param receiptHandle 传输层回执句柄（SQS 为 ReceiptHandle，mock 为合成序号）；用于 ack（删消息）
 * @param rawJson       原始通知 JSON 字符串，不解析、不改字段
 * @param synthetic     true 表示合成事件（演练/mock），必须一路带到 Inbox 的 synthetic 列，
 *                      防止演练数据被当成真实经营数据
 */
public record NotificationEnvelope(String receiptHandle, String rawJson, boolean synthetic) {

    public NotificationEnvelope {
        if (receiptHandle == null || receiptHandle.isBlank()) {
            throw new IllegalArgumentException("receiptHandle 不能为空：没有回执句柄就无法 ack，消息会被无限重投");
        }
        if (rawJson == null || rawJson.isBlank()) {
            throw new IllegalArgumentException("rawJson 不能为空");
        }
    }
}