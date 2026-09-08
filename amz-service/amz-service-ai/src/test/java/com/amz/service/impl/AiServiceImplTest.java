package com.amz.service.impl;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * AiService 响应解析测试（纯函数，无 Spring、无网络）。
 * <p>
 * 回归：chat/completions 返回无 choices 的错误体时，choices.get(0) 直接
 * NPE/IndexOutOfBounds 穿透为 500；现降级返回 null 由调用方转为 failure。
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
