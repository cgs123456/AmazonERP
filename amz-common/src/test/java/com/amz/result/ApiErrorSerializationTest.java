package com.amz.result;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-52a：结构化错误的 JSON 契约。
 * <p>
 * 成功响应不得出现 {@code error} 字段；失败响应必须保留本地错误码、平台 HTTP 状态、
 * 平台错误码、平台消息与 requestId，供前端、运维和审计消费。
 */
@DisplayName("P0-52a 结构化错误 JSON 契约（ApiError）")
class ApiErrorSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("成功 Result 不输出 error 字段")
    void successOmitsErrorField() throws Exception {
        String json = objectMapper.writeValueAsString(Result.success("ok"));

        assertFalse(json.contains("\"error\""), json);
    }

    @Test
    @DisplayName("失败 Result 输出完整 ApiError 字段")
    void failureSerializesApiError() throws Exception {
        ApiError error = new ApiError(
                "SPAPI_CALL_FAILED", 429, "QuotaExceeded", "Request is throttled", "req-1");

        String json = objectMapper.writeValueAsString(Result.failure("sync failed", error));
        JsonNode node = objectMapper.readTree(json).path("error");

        assertEquals("SPAPI_CALL_FAILED", node.path("code").asText());
        assertEquals(429, node.path("platformStatus").asInt());
        assertEquals("QuotaExceeded", node.path("platformCode").asText());
        assertEquals("Request is throttled", node.path("platformMessage").asText());
        assertEquals("req-1", node.path("requestId").asText());
        assertTrue(json.contains("\"code\":400"), json);
    }
}
