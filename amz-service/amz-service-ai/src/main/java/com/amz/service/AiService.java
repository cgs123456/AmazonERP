package com.amz.service;

import com.amz.result.Result;

public interface AiService {

    /**
     * Agent 多消息聊天（支持多轮对话）。
     *
     * <p>取代了原来的 {@code chat(String prompt)}（§S33.47 删）：那个方法只发一条
     * user 消息，是本方法的严格子集，而它唯一的调用方 {@code POST /ai/chat}
     * 前端与内部服务都不调。保留两套 HTTP 管道等于让同一处错误处理漂移两份。
     *
     * @param agentChatDto 包含 messages 数组的请求体
     * @return DeepSeek 返回的原始文本响应（content 字段）
     */
    Result<String> agentChat(com.amz.model.dto.AgentChatDto agentChatDto);

}
