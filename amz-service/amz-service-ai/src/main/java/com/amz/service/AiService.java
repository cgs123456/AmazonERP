package com.amz.service;

import com.amz.result.Result;

public interface AiService {

    Result<String> chat(String prompt);

    /**
     * Agent 多消息聊天（支持多轮对话）
     * @param agentChatDto 包含 messages 数组的请求体
     * @return DeepSeek 返回的原始文本响应（content 字段）
     */
    Result<String> agentChat(com.amz.model.dto.AgentChatDto agentChatDto);

}