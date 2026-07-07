package com.amz.agent;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.amz.agent.dto.AgentMessage;
import com.amz.constant.RedisConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 多轮对话存储（Redis）。
 * - key: agent:conversation:{conversationId}
 * - TTL: 30 分钟，每次读写刷新
 * - 滑动窗口：最多保留 MAX_TURNS*2 条消息（10 轮），超出则截断保留最近的
 */
@Component
@Slf4j
public class ConversationStore {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private final Gson gson = new Gson();
    private static final Type MESSAGE_LIST_TYPE = new TypeToken<List<AgentMessage>>() {}.getType();

    /** 最大保留轮数（一问一答为一轮） */
    private static final int MAX_TURNS = 10;
    /** 每轮 2 条消息（user + assistant） */
    private static final int MAX_MESSAGES = MAX_TURNS * 2;
    /** TTL 30 分钟（秒） */
    private static final long TTL_SECONDS = 30 * 60;

    /**
     * 读取对话历史
     */
    public List<AgentMessage> getHistory(String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            return new ArrayList<>();
        }
        String key = RedisConstant.AGENT_CONVERSATION + conversationId;
        Object val = redisTemplate.opsForValue().get(key);
        if (val == null) {
            return new ArrayList<>();
        }
        try {
            List<AgentMessage> messages = gson.fromJson(val.toString(), MESSAGE_LIST_TYPE);
            // 刷新 TTL
            redisTemplate.expire(key, TTL_SECONDS, TimeUnit.SECONDS);
            return messages != null ? messages : new ArrayList<>();
        } catch (Exception e) {
            log.error("读取对话历史失败: conversationId={}", conversationId, e);
            return new ArrayList<>();
        }
    }

    /**
     * 追加一条消息并保存（自动滑动窗口截断）
     */
    public void appendMessage(String conversationId, AgentMessage message) {
        if (conversationId == null || conversationId.isEmpty() || message == null) {
            return;
        }
        List<AgentMessage> history = getHistory(conversationId);
        history.add(message);
        // 滑动窗口截断
        if (history.size() > MAX_MESSAGES) {
            history = new ArrayList<>(history.subList(history.size() - MAX_MESSAGES, history.size()));
        }
        save(conversationId, history);
    }

    /**
     * 批量追加消息
     */
    public void appendMessages(String conversationId, List<AgentMessage> messages) {
        if (conversationId == null || messages == null || messages.isEmpty()) {
            return;
        }
        List<AgentMessage> history = getHistory(conversationId);
        history.addAll(messages);
        if (history.size() > MAX_MESSAGES) {
            history = new ArrayList<>(history.subList(history.size() - MAX_MESSAGES, history.size()));
        }
        save(conversationId, history);
    }

    /**
     * 清空对话历史（话题切换时调用）
     */
    public void clear(String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            return;
        }
        redisTemplate.delete(RedisConstant.AGENT_CONVERSATION + conversationId);
    }

    private void save(String conversationId, List<AgentMessage> messages) {
        String key = RedisConstant.AGENT_CONVERSATION + conversationId;
        redisTemplate.opsForValue().set(key, gson.toJson(messages), TTL_SECONDS, TimeUnit.SECONDS);
    }
}
