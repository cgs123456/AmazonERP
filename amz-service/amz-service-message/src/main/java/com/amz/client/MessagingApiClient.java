package com.amz.client;

import java.util.List;
import java.util.Map;

/**
 * Amazon SP-API Messaging (Buyer-Seller Messages) 客户端。
 * 对接 sellingpartnerapi 的 messaging/v1 API。
 */
public interface MessagingApiClient {
    /** 获取买家消息列表 */
    List<Map<String, Object>> listMessages(Long shopId, String marketplaceId, int pageSize);
    /** 获取单条消息详情 */
    Map<String, Object> getMessage(Long shopId, String messageId);
    /** 回复买家消息 */
    boolean replyMessage(Long shopId, String messageId, String body);
    /** 标记消息已读 */
    boolean markAsRead(Long shopId, String messageId);
}
