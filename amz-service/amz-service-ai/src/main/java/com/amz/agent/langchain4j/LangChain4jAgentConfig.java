package com.amz.agent.langchain4j;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LangChain4j Agent 配置。
 * <p>
 * 当 deepseek.api-key 未配置时，chatLanguageModel 和 erpAgent Bean 不会被创建，
 * 调用方需处理 Bean 不存在的情况（@Autowired(required = false)）。
 */
@Slf4j
@Configuration
public class LangChain4jAgentConfig {

    @Value("${deepseek.api-key:}")
    private String apiKey;

    @Value("${deepseek.api-url:https://api.deepseek.com/v1}")
    private String baseUrl;

    @Value("${deepseek.model-name:deepseek-chat}")
    private String modelName;

    @Value("${deepseek.temperature:0.7}")
    private double temperature;

    @Value("${deepseek.timeout:60}")
    private long timeoutSeconds;

    @Autowired(required = false)
    private ErpTools erpTools;

    /**
     * DeepSeek 兼容 OpenAI 接口的 ChatModel。
     * 仅当 deepseek.api-key 非空时才创建 Bean。
     */
    @Bean
    @ConditionalOnExpression("'${deepseek.api-key:}'.trim().length() > 0")
    public ChatModel chatLanguageModel() {
        log.info("初始化 LangChain4j OpenAiChatModel: baseUrl={}, model={}", baseUrl, modelName);
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    /**
     * ERP Agent AiServices 代理 Bean。
     * 使用 chatMemoryProvider 按会话隔离，避免多用户串扰。
     * 仅当 chatLanguageModel Bean 存在时才创建。
     */
    @Bean
    @ConditionalOnExpression("'${deepseek.api-key:}'.trim().length() > 0")
    public ErpAgentInterface erpAgent(ChatModel chatLanguageModel) {
        log.info("初始化 LangChain4j ErpAgent (AiServices 代理)");
        var builder = AiServices.builder(ErpAgentInterface.class)
                .chatModel(chatLanguageModel)
                .chatMemoryProvider(sessionId -> MessageWindowChatMemory.builder()
                        .id(sessionId)
                        .maxMessages(20)
                        .build());
        if (erpTools != null) {
            builder.tools(erpTools);
        }
        return builder.build();
    }
}
