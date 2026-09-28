package com.amz.notification;

/**
 * SubscriptionId 反查到的店铺绑定结果。
 * <p>
 * SP-API 通知正文里只有 {@code NotificationMetadata.SubscriptionId}，
 * 没有 shopId、没有 marketplaceId——归属必须靠我们自己的订阅映射表反查。
 * 反查不到时 {@code null} 表示该事件暂时无法归属店铺，
 * 对应状态 {@link NotificationInboxStatus#UNRESOLVED_SUBSCRIPTION}：
 * 此时不能丢事件、也不能删 SQS 消息，只能隔离等补映射后重放。
 *
 * @param shopId        归属店铺；必填，不允许 null
 * @param marketplaceId 站点 ID，可为空
 * @param destinationId 通知目的地 ID，可为空
 */
public record NotificationShopBinding(Long shopId, String marketplaceId, String destinationId) {

    public NotificationShopBinding {
        if (shopId == null) {
            throw new IllegalArgumentException("NotificationShopBinding.shopId 不允许为 null");
        }
    }
}
