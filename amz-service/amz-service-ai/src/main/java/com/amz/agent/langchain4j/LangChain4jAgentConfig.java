package com.amz.agent.langchain4j;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LangChain4j Agent 配置。
 * <p>
 * 将 DeepSeek（OpenAI 兼容 API）配置为 ChatLanguageModel，
 * 并通过 AiServices 构建 ErpAgentInterface 代理对象。
 * <p>
 * 对比旧版 AiServiceImpl（OkHttp 裸调 + 手动 JSON 解析）：
 * - 统一由 OpenAiChatModel 管理连接池、重试、超时
 * - 自动处理 Function Calling 的 tools/tool_calls 字段
 * - 支持 ChatMemory（对话上下文窗口）
 */
@Slf4j
@Configuration
public class LangChain4jAgentConfig {

    @Value("${deepseek.api_url:https://api.deepseek.com/v1}")
    private String apiUrl;

    @Value("${deepseek.api_key:}")
    private String apiKey;

    /**
     * DeepSeek 聊天模型（OpenAI 兼容）。
     * DeepSeek API 完全兼容 OpenAI /v1/chat/completions 接口，
     * 可直接使用 langchain4j-open-ai 模块。
     */
    @Bean
    public ChatLanguageModel chatLanguageModel() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("DeepSeek API Key 未配置（deepseek.api_key 为空），LangChain4j Agent 将无法调用 LLM");
        }
        return dev.langchain4j.model.openai.OpenAiChatModel.builder()
                .baseUrl(apiUrl.endsWith("/") ? apiUrl : apiUrl + "/")
                .apiKey(apiKey != null && !apiKey.isBlank() ? apiKey : "dummy-key-for-init")
                .modelName("deepseek-chat")
                .temperature(0.7)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    /**
     * ERP Agent 代理对象。
     * AiServices 内部完成：
     * 1. 扫描 ErpTools 的 @Tool 注解 → 生成函数 schema
     * 2. 调用 LLM 时自动附带 tools 字段
     * 3. LLM 返回 tool_calls → 自动执行 → 结果注入 → 继续推理
     * 4. ChatMemory 维护最近 20 条消息的上下文窗口
     */
    @Bean
    public ErpAgentInterface erpAgent(ChatLanguageModel chatLanguageModel, ErpTools erpTools) {
        return AiServices.builder(ErpAgentInterface.class)
                .chatLanguageModel(chatLanguageModel)
                .tools(erpTools)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
                .build();
    }
}
