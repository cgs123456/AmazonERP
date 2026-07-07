package com.amz.model.dto;

import lombok.Data;
import java.util.List;

@Data
public class AgentChatDto {
    /** 消息数组（role: system/user/assistant/tool） */
    private List<Message> messages;
    /** 可选：单独传入 system prompt（会拼接到 messages 前） */
    private String systemPrompt;

    @Data
    public static class Message {
        private String role;
        private String content;
    }
}
