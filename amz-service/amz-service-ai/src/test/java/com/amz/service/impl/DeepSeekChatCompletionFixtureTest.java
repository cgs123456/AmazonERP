package com.amz.service.impl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DeepSeek /chat/completions 契约 fixture 测试（离线）：
 * fixture 必须是 OpenAI 兼容官方形状（choices[0].message.content），
 * 且能被 {@link AiServiceImpl#extractContent} 真实解析出 content —— 这正是
 * 真实客户端的解析路径。fixture 由
 * {@code tools/contract-fixtures/generate_b_bucket_fixtures.py} 从官方响应形状生成。
 */
@DisplayName("DeepSeek chat-completion 契约 fixture（OpenAI 兼容形状）")
class DeepSeekChatCompletionFixtureTest {

    @Test
    @DisplayName("fixture 是 OpenAI 兼容形状并带 synthetic 标记")
    void fixtureIsOpenAiCompatibleShape() throws Exception {
        JsonObject fixture = fixture();

        assertTrue(fixture.get("synthetic").getAsBoolean(), "必须显式标记 synthetic=true");
        assertEquals("chat.completion", fixture.get("object").getAsString());
        assertEquals("deepseek-chat", fixture.get("model").getAsString());
        assertTrue(fixture.has("choices"));
        assertEquals(1, fixture.getAsJsonArray("choices").size());

        JsonObject choice = fixture.getAsJsonArray("choices").get(0).getAsJsonObject();
        assertEquals(0, choice.get("index").getAsInt());
        assertEquals("assistant", choice.getAsJsonObject("message").get("role").getAsString());
        assertTrue(choice.getAsJsonObject("message").get("content").getAsString().startsWith("SYNTHETIC"));
        assertEquals("stop", choice.get("finish_reason").getAsString());
        assertTrue(fixture.getAsJsonObject("usage").get("total_tokens").getAsInt() > 0);
    }

    @Test
    @DisplayName("extractContent 能从官方形状 fixture 提取 content（真实解析路径）")
    void extractContentParsesFixture() throws Exception {
        JsonObject fixture = fixture();
        String content = AiServiceImpl.extractContent(fixture);
        assertNotNull(content);
        assertTrue(content.startsWith("SYNTHETIC"));
    }

    @Test
    @DisplayName("choices 缺失/空数组/无 content 时 extractContent 返回 null（不 NPE）")
    void extractContentStaysNullSafe() {
        JsonObject empty = new JsonObject();
        assertEquals(null, AiServiceImpl.extractContent(empty));

        JsonObject noChoices = JsonParser.parseString("{\"error\": {\"message\": \"rate limited\"}}").getAsJsonObject();
        assertEquals(null, AiServiceImpl.extractContent(noChoices));

        JsonObject emptyChoices = JsonParser.parseString("{\"choices\": []}").getAsJsonObject();
        assertEquals(null, AiServiceImpl.extractContent(emptyChoices));

        JsonObject nullContent = JsonParser.parseString(
                "{\"choices\": [{\"message\": {\"content\": null}}]}").getAsJsonObject();
        assertEquals(null, AiServiceImpl.extractContent(nullContent));
    }

    private static JsonObject fixture() throws Exception {
        try (InputStream in = DeepSeekChatCompletionFixtureTest.class
                .getResourceAsStream("/contracts/deepseek-chat-completion.json")) {
            assertNotNull(in, "fixture missing: /contracts/deepseek-chat-completion.json");
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
