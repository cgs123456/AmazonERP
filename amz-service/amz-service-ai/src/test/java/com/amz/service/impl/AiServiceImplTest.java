package com.amz.service.impl;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AiService 响应解析测试（纯函数，无 Spring、无网络）。
 * <p>
 * 回归：chat/completions 返回无 choices 的错误体时，choices.get(0) 直接
 * NPE/IndexOutOfBounds 穿透为 500；现降级返回 null 由调用方转为 failure。
 * <p>
 * 另补 B 桶缺凭据断言（2026-10-08）：{@code /ai/chat}、{@code /ai/agent/chat} 在
 * DEEPSEEK_API_KEY 为空时必须点名失败，不得发请求或返回假结果。
 */
@DisplayName("AiService 响应解析测试")
class AiServiceImplTest {

    @Test
    @DisplayName("正常响应提取首条 content")
    void extractContentOk() {
        String body = "{\"choices\":[{\"message\":{\"content\":\"hello\"}}]}";
        assertEquals("hello", AiServiceImpl.extractContent(
                JsonParser.parseString(body).getAsJsonObject()));
    }

    @Test
    @DisplayName("agentChat(null) 返回失败而非 NPE")
    void agentChatNullDtoFails() {
        com.amz.result.Result<String> result = new AiServiceImpl().agentChat(null);
        assertEquals(400, result.getCode());
    }

    @Test
    @DisplayName("chat：缺 DEEPSEEK_API_KEY → 点名失败，不发请求")
    void chatFailsClosedWithoutApiKey() {
        AiServiceImpl service = new AiServiceImpl();
        ReflectionTestUtils.setField(service, "apiKey", "");

        com.amz.result.Result<String> result = service.chat("hi");

        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().contains("DEEPSEEK_API_KEY"), result.getMessage());
    }

    @Test
    @DisplayName("agentChat：缺 DEEPSEEK_API_KEY → 点名失败（先于 messages 校验）")
    void agentChatFailsClosedWithoutApiKey() {
        AiServiceImpl service = new AiServiceImpl();
        ReflectionTestUtils.setField(service, "apiKey", "  ");
        com.amz.model.dto.AgentChatDto dto = new com.amz.model.dto.AgentChatDto();
        dto.setMessages(java.util.List.of(new com.amz.model.dto.AgentChatDto.Message()));

        com.amz.result.Result<String> result = service.agentChat(dto);

        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().contains("DEEPSEEK_API_KEY"),
                "缺 key 与 messages 为空是两种失败，缺 key 时必须点名配置项：" + result.getMessage());
    }

    @Test
    @DisplayName("错误体/空 choices/空 content 一律返回 null")
    void extractContentDegradesToNull() {
        assertNull(AiServiceImpl.extractContent(null));
        assertNull(AiServiceImpl.extractContent(
                JsonParser.parseString("{\"error\":{\"message\":\"rate limited\"}}").getAsJsonObject()));
        assertNull(AiServiceImpl.extractContent(
                JsonParser.parseString("{\"choices\":[]}").getAsJsonObject()));
        assertNull(AiServiceImpl.extractContent(
                JsonParser.parseString("{\"choices\":[{\"message\":{}}]}").getAsJsonObject()));
    }
}
