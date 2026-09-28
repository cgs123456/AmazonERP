package com.amz.notification;

/**
 * 订阅对账发现的漂移：本地登记与 Amazon 侧实际订阅不一致。
 * <p>
 * 漂移不是「可以自动修掉的小问题」，它直接决定事件能不能归属店铺：
 * 本地少登记一条，对应的通知就全部进 UNRESOLVED_SUBSCRIPTION 卡住；
 * 本地多登记一条（Amazon 侧已删），运维会误以为订阅还在，从而漏掉补建。
 *
 * @param subscriptionId   Amazon 侧 subscriptionId
 * @param shopId           本地店铺 ID；映射缺字段时为 null
 * @param notificationType 订阅的通知类型；映射缺字段时为 null
 * @param kind             漂移类型
 * @param detail           可读原因；只放类名与字段名，不放报文内容
 */
public record NotificationSubscriptionDrift(String subscriptionId,
                                            Long shopId,
                                            String notificationType,
                                            Kind kind,
                                            String detail) {

    public enum Kind {
        /** 本地登记为 ACTIVE，但 Amazon 侧查不到该 subscriptionId。 */
        MISSING_REMOTELY,
        /** 调用 Amazon 校验失败（无凭证 / 限流 / 网络）：无法判定，必须重试而不是当成「不存在」。 */
        VERIFY_FAILED,
        /** 本地映射自身缺字段，连校验都发不出去。 */
        INCOMPLETE_LOCAL_RECORD,
        /** Amazon 侧存在但本地未登记：会造成对应店铺的通知全部无法归属。 */
        ORPHAN_REMOTE
    }
}
