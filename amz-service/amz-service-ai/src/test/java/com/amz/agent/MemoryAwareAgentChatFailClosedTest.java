package com.amz.agent;

import com.amz.model.UserPreference;
import com.amz.result.Result;
import com.amz.service.AiService;
import com.amz.service.MemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 记忆化 Agent 编排的失败传播契约（B 桶补测，2026-10-08）。
 * <p>
 * {@code POST /ai/agent/memory/chat} 的缺凭据链是间接的：{@code AiServiceImpl.agentChat}
 * 缺 key 返回 failure → 编排层 {@code MemoryAwareAgentService.chat} 必须把失败原样上抛，
 * 且**不得把 assistant 消息写进记忆**——否则记忆库里会沉淀「LLM 其实没回答」的假对话，
 * 下一轮还会被当作真实历史注入提示词，假数据开始自我繁殖。
 */
@DisplayName("记忆化 Agent 编排：LLM 失败原样上抛，不写假回复进记忆")
class MemoryAwareAgentChatFailClosedTest {

    private MemoryAwareAgentService service;
    private AiService aiService;
    private MemoryService memoryService;
    private MultiLangPromptBuilder promptBuilder;

    @BeforeEach
    void setUp() {
        aiService = mock(AiService.class);
        memoryService = mock(MemoryService.class);
        promptBuilder = mock(MultiLangPromptBuilder.class);
        service = new MemoryAwareAgentService();
        ReflectionTestUtils.setField(service, "aiService", aiService);
        ReflectionTestUtils.setField(service, "memoryService", memoryService);
        ReflectionTestUtils.setField(service, "promptBuilder", promptBuilder);
        when(promptBuilder.build(any(com.amz.model.LanguageEnum.class))).thenReturn("SYS");
        when(memoryService.getOrCreatePreference(anyLong())).thenReturn(new UserPreference());
        when(memoryService.listRecentMemories(anyString(), anyInt())).thenReturn(List.of());
    }

    @Test
    @DisplayName("LLM 缺 key 失败 → 400 透传 DEEPSEEK_API_KEY 原因，assistant 不写库")
    void llmFailurePropagatesAndWritesNoAssistantMemory() {
        when(aiService.agentChat(any()))
                .thenReturn(Result.failure("DeepSeek 未配置：请设置 DEEPSEEK_API_KEY"));

        Result<String> result = service.chat(7L, "帮我看看订单");

        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().contains("DEEPSEEK_API_KEY"),
                "根因必须原样上抛，不能吞成笼统失败：" + result.getMessage());
        // 用户消息照写（真实发生过），但 assistant 假回复绝不落库
        verify(memoryService, never()).saveMemory(anyString(), anyLong(), eq("assistant"), any());
    }

    @Test
    @DisplayName("LLM 成功返回自然语言 → assistant 正常写库（对照组，防测试断言过宽）")
    void llmSuccessStillWritesAssistantMemory() {
        when(aiService.agentChat(any())).thenReturn(Result.success("你好"));

        Result<String> result = service.chat(7L, "hi");

        assertEquals(200, result.getCode());
        verify(memoryService).saveMemory("sess-7", 7L, "assistant", "你好");
    }
}
