package com.amz.notification;

import com.amz.model.NotificationInboxEntity;

/**
 * 事件处理器：Worker 领取到租约后，把解密后的原始通知交给它。
 * <p>
 * Task 5 只定义接口、不挂业务实现是刻意的：领取 / 重试 / DLQ 这套机制必须先被证明正确，
 * 再接业务 Handler。否则 Handler 一抛异常，很难区分是业务错了还是重试机制错了。
 * <p>
 * 实现约束：必须幂等。SQS 是 standard 队列，同一条通知可能重复投递；
 * Inbox 只吸收同号同哈希的重投，跨批次重投仍可能让同一条事件被处理第二次。
 */
public interface NotificationEventProcessor {

    /**
     * 处理一条已领取租约的通知。
     *
     * @param event          已领取的 Inbox 记录（attempt_count 已由 SQL 原子自增）
     * @param rawPayloadJson 解密后的原始通知 JSON；超限事件未存正文时为 null
     * @throws NotificationProcessingException 业务失败；{@code retryable=false} 时直接进 DLQ
     */
    void process(NotificationInboxEntity event, String rawPayloadJson);
}
