package com.amz.client;

import com.amz.http.ResilientHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("1688 closeOrder 异常与业务拒绝语义")
class Alibaba1688CloseOrderSemanticsTest {

    private ResilientHttpClient http;
    private Alibaba1688RealClient client;

    @BeforeEach
    void setUp() {
        http = mock(ResilientHttpClient.class);
        Alibaba1688TokenManager tokenManager = mock(Alibaba1688TokenManager.class);
        when(tokenManager.getAccessToken()).thenReturn("token");

        client = new Alibaba1688RealClient();
        ReflectionTestUtils.setField(client, "appKey", "test-key");
        ReflectionTestUtils.setField(client, "appSecret", "test-secret");
        ReflectionTestUtils.setField(client, "gateway",
                "https://gw.open.1688.com/openapi");
        ReflectionTestUtils.setField(client, "http", http);
        ReflectionTestUtils.setField(client, "tokenManager", tokenManager);
    }

    @Test
    @DisplayName("平台明确返回 success=false 时返回业务失败")
    void businessRejectionReturnsFalse() {
        when(http.post(eq("1688"), anyString(), anyMap(), anyString()))
                .thenReturn("{\"result\":{\"success\":false}}");

        assertFalse(client.closeOrder("ORDER-1"));
    }

    @Test
    @DisplayName("平台返回 success=true 时返回成功")
    void businessSuccessReturnsTrue() {
        when(http.post(eq("1688"), anyString(), anyMap(), anyString()))
                .thenReturn("{\"result\":{\"success\":true}}");

        assertTrue(client.closeOrder("ORDER-1"));
    }

    @Test
    @DisplayName("响应无法解析时必须抛错，不能伪装成业务拒绝")
    void malformedResponseThrows() {
        when(http.post(eq("1688"), anyString(), anyMap(), anyString()))
                .thenReturn("not-json");

        assertThrows(RuntimeException.class, () -> client.closeOrder("ORDER-1"));
    }

    @Test
    @DisplayName("success 字段缺失时必须失败关闭，不能默认成功")
    void missingBusinessSuccessThrows() {
        when(http.post(eq("1688"), anyString(), anyMap(), anyString()))
                .thenReturn("{\"result\":{\"orderId\":\"ORDER-1\"}}");

        assertThrows(RuntimeException.class, () -> client.closeOrder("ORDER-1"));
    }

    @Test
    @DisplayName("顶层业务错误必须抛错，不能伪装成关闭成功")
    void topLevelBusinessErrorThrows() {
        when(http.post(eq("1688"), anyString(), anyMap(), anyString()))
                .thenReturn("{\"error_code\":\"ILLEGAL_ARGUMENT\",\"error_message\":\"invalid order\"}");

        assertThrows(RuntimeException.class, () -> client.closeOrder("ORDER-1"));
    }

    @Test
    @DisplayName("网络或传输异常必须抛错，允许调用方重试")
    void transportFailureThrows() {
        when(http.post(eq("1688"), anyString(), anyMap(), anyString()))
                .thenThrow(new RuntimeException("connection reset"));

        assertThrows(RuntimeException.class, () -> client.closeOrder("ORDER-1"));
    }
}