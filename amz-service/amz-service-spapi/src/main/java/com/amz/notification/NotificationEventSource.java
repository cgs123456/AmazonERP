package com.amz.notification;

import java.util.List;

/**
 * 通知事件源抽象：只负责传输，不理解业务。
 * <p>
 * 实现分两种：
 * <ul>
 *   <li>{@code MockNotificationEventSource}：合成事件，用于离线开发、回归与演练。</li>
 *   <li>{@code SqsNotificationEventSource}：AWS SDK v2 长轮询真实队列。</li>
 * </ul>
 * 切换只改配置，不重写业务主链路。
 * <p>
 * 硬规则：只有成功落 Inbox 的事件才允许 {@link #acknowledge}。落库失败不 ack，
 * 由可见性超时到期触发重投；这条规则是「DB 写失败绝不删消息」的实现方式。
 */
public interface NotificationEventSource {

    /**
     * 拉取一批原始通知；无事件时返回空列表。
     *
     * @param maxMessages 本轮最多拉取条数
     */
    List<NotificationEnvelope> poll(int maxMessages);

    /**
     * 确认已成功落 Inbox 的事件（SQS 为 DeleteMessage）。
     *
     * @param receiptHandles 只允许传已成功持久化的回执句柄
     */
    void acknowledge(List<String> receiptHandles);

    /** 事件源名称，用于日志与指标标签。 */
    String name();
}
