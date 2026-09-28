package com.amz.notification;

/**
 * Inbox 事件状态常量。
 * <p>
 * 用常量而不是散落的字符串字面量：状态机一旦写错（比如把 UNSUPPORTED 当成终态去删 SQS 消息），
 * 表现是事件静默消失，排查成本极高。
 */
public final class NotificationInboxStatus {

    /** 已落库，等待领取。 */
    public static final String RECEIVED = "RECEIVED";

    /** 已被 Worker 领取，处理中（持租约）。 */
    public static final String PROCESSING = "PROCESSING";

    /** 处理成功，终态。 */
    public static final String PROCESSED = "PROCESSED";

    /** 处理失败但仍有重试额度。 */
    public static final String FAILED = "FAILED";

    /** 超过 max_attempts，进入死信，只能人工重放。 */
    public static final String DLQ = "DLQ";

    /** 通知类型暂无 Handler：保留原始事件等待后续实现，不丢弃。 */
    public static final String UNSUPPORTED = "UNSUPPORTED";

    /**
     * subscriptionId 在本库查不到店铺映射。
     * 硬规则：不删 SQS 消息、不进 DLQ；补映射后重放。
     */
    public static final String UNRESOLVED_SUBSCRIPTION = "UNRESOLVED_SUBSCRIPTION";

    /** 载荷结构非法（缺必填字段 / 超长 / 非 JSON），保留原始事件。 */
    public static final String INVALID = "INVALID";

    private NotificationInboxStatus() {
    }
}
