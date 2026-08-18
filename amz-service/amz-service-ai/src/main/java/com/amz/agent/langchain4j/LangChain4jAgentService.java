package com.amz.agent.langchain4j;

import com.amz.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;

/**
 * LangChain4j Agent 服务。
 * <p>
 * 核心设计：
 * <table border="1">
 * <tr><th>维度</th><th>实现方式</th></tr>
 * <tr><td>编排方式</td><td>AiServices 自动编排（最多 10 轮）</td></tr>
 * <tr><td>工具调用</td><td>原生 Function Calling（tool_calls 字段）</td></tr>
 * <tr><td>工具注册</td><td>@Tool 注解自动扫描注册</td></tr>
 * <tr><td>消息管理</td><td>ChatMemory 自动管理上下文窗口</td></tr>
 * </table>
 */
@Slf4j
@Service
public class LangChain4jAgentService {

    @Autowired(required = false)
    private ErpAgentInterface erpAgent;

    /**
     * 执行 Agent 对话（LangChain4j 编排）。
     *
     * @param userId      用户 ID，用于隔离 ChatMemory 会话
     * @param userMessage 用户输入
     * @return Agent 最终回复
     */
    @SentinelResource(value = "chat", fallback = "chatFallback")
    public Result<String> chat(Long userId, String userMessage) {
        if (erpAgent == null) {
            return Result.failure("LangChain4j Agent 未启用：deepseek.api_key 未配置");
        }
        try {
            String sessionId = "sess-" + userId;
            String response = erpAgent.chat(sessionId, userMessage);
            return Result.success(response);
        } catch (Exception e) {
            log.error("LangChain4j Agent 调用失败", e);
            return Result.failure("Agent 调用失败: " + e.getMessage());
        }
    }
    /**
     * chat 的 fallback 方法：Agent 调用熔断或异常时返回友好提示。
     */
    public Result<String> chatFallback(Long userId, String userMessage, Throwable e) {
        log.warn("chat fallback triggered userId={} err={}", userId, e.getMessage());
        return Result.failure("服务繁忙，请稍后重试");
    }
}