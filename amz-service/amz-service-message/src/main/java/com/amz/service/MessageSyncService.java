package com.amz.service;

import java.util.List;
import java.util.Map;

public interface MessageSyncService {
    /** 从 Amazon 同步买家消息到本地数据库 */
    List<Map<String, Object>> syncMessages(Long shopId, String marketplaceId);
    /** 获取本地消息列表 */
    List<Map<String, Object>> listLocalMessages(Long shopId, int page, int pageSize);
    /** 回复消息 */
    boolean replyMessage(Long shopId, String messageId, String body);
}
