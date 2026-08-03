package com.amz.client.impl;

import com.amz.client.MessagingApiClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Component
@Profile("mock")
public class MessagingApiMockClient implements MessagingApiClient {

    private static final String[] SUBJECTS = {
        "Where is my order?", "Return request", "Product damaged", "Wrong item received",
        "Size exchange", "Shipping delay", "Refund status", "Product inquiry"
    };
    private static final String[] BUYERS = {"John D.", "Sarah M.", "Mike T.", "Emily R.", "David K."};

    @Override
    public List<Map<String, Object>> listMessages(Long shopId, String marketplaceId, int pageSize) {
        int count = Math.min(pageSize, 5 + ThreadLocalRandom.current().nextInt(0, 6));
        List<Map<String, Object>> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("messageId", "MSG-" + System.currentTimeMillis() % 100000 + "-" + i);
            msg.put("buyerName", BUYERS[ThreadLocalRandom.current().nextInt(BUYERS.length)]);
            msg.put("subject", SUBJECTS[ThreadLocalRandom.current().nextInt(SUBJECTS.length)]);
            msg.put("body", "I have a question about my recent order...");
            msg.put("receivedAt", LocalDateTime.now().minusHours(ThreadLocalRandom.current().nextInt(1, 72)).toString());
            msg.put("isRead", ThreadLocalRandom.current().nextBoolean());
            msg.put("orderId", "AMZ-" + (10000000L + ThreadLocalRandom.current().nextLong(0, 90000000)));
            messages.add(msg);
        }
        return messages;
    }

    @Override
    public Map<String, Object> getMessage(Long shopId, String messageId) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("messageId", messageId);
        msg.put("buyerName", "John D.");
        msg.put("subject", "Where is my order?");
        msg.put("body", "Hello, I ordered item B0XXXXXXXX on July 28 but haven't received tracking info yet. Can you help check?");
        msg.put("receivedAt", LocalDateTime.now().minusHours(3).toString());
        msg.put("isRead", false);
        msg.put("orderId", "AMZ-11487654321");
        return msg;
    }

    @Override
    public boolean replyMessage(Long shopId, String messageId, String body) {
        log.info("Mock reply to message={} body={}", messageId, body);
        return true;
    }

    @Override
    public boolean markAsRead(Long shopId, String messageId) {
        log.info("Mock mark as read message={}", messageId);
        return true;
    }
}
