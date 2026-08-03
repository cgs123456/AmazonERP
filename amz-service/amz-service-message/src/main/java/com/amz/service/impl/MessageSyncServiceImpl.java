package com.amz.service.impl;

import com.amz.client.MessagingApiClient;
import com.amz.service.MessageSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class MessageSyncServiceImpl implements MessageSyncService {

    @Autowired
    private MessagingApiClient messagingApiClient;

    // 内存存储（生产应换 MySQL）
    private final Map<String, Map<String, Object>> localMessages = new LinkedHashMap<>();

    @Override
    public List<Map<String, Object>> syncMessages(Long shopId, String marketplaceId) {
        log.info("同步 Amazon 消息 shopId={} marketplaceId={}", shopId, marketplaceId);
        List<Map<String, Object>> messages = messagingApiClient.listMessages(shopId, marketplaceId, 50);
        for (Map<String, Object> msg : messages) {
            String id = String.valueOf(msg.get("messageId"));
            msg.put("shopId", shopId);
            msg.put("syncedAt", java.time.LocalDateTime.now().toString());
            localMessages.putIfAbsent(id, msg);
        }
        return messages;
    }

    @Override
    public List<Map<String, Object>> listLocalMessages(Long shopId, int page, int pageSize) {
        return new ArrayList<>(localMessages.values());
    }

    @Override
    public boolean replyMessage(Long shopId, String messageId, String body) {
        return messagingApiClient.replyMessage(shopId, messageId, body);
    }
}
