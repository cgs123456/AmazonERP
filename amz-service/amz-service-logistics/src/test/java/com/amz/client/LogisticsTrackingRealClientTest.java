package com.amz.client;

import com.amz.exception.ConnectorException;
import com.amz.http.ResilientHttpClient;
import com.amz.model.TrackingEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogisticsTrackingRealClientTest {

    @Mock
    private ResilientHttpClient httpClient;

    private LogisticsTrackingProperties properties;
    private LogisticsTrackingRealClient client;

    @BeforeEach
    void setUp() {
        properties = new LogisticsTrackingProperties();
        client = new LogisticsTrackingRealClient();
        ReflectionTestUtils.setField(client, "properties", properties);
        ReflectionTestUtils.setField(client, "httpClient", httpClient);
    }

    @Test
    @DisplayName("总开关关闭：抛 DISABLED，且不发起 HTTP 请求")
    void disabledIsExplicit() {
        ConnectorException ex = assertThrows(ConnectorException.class,
                () -> client.queryTracking("COSU123", "COSCO"));

        assertEquals(ConnectorException.Reason.DISABLED, ex.getReason());
        assertEquals("17TRACK", ex.getConnector());
        verifyNoInteractions(httpClient);
    }

    @Test
    @DisplayName("已开启但缺少 token：抛 NOT_CONFIGURED，且不发起 HTTP 请求")
    void missingApiKeyIsExplicit() {
        properties.setEnabled(true);

        ConnectorException ex = assertThrows(ConnectorException.class,
                () -> client.queryTracking("COSU123", "COSCO"));

        assertEquals(ConnectorException.Reason.NOT_CONFIGURED, ex.getReason());
        verifyNoInteractions(httpClient);
    }

    @Test
    @DisplayName("网络/熔断异常：包装为 CALL_FAILED，不伪装成无轨迹")
    void networkFailureIsExplicit() {
        properties.setEnabled(true);
        properties.setApiKey("token");
        when(httpClient.post(anyString(), anyString(), anyMap(), anyString()))
                .thenThrow(new RuntimeException("timeout"));

        ConnectorException ex = assertThrows(ConnectorException.class,
                () -> client.queryTracking("COSU123", "COSCO"));

        assertEquals(ConnectorException.Reason.CALL_FAILED, ex.getReason());
    }

    @Test
    @DisplayName("成功但 accepted 为空：返回空列表，表示对端确认暂无事件")
    void successfulEmptyResultReturnsEmptyList() {
        properties.setEnabled(true);
        properties.setApiKey("token");
        when(httpClient.post(anyString(), anyString(), anyMap(), anyString()))
                .thenReturn("{\"code\":0,\"data\":{\"accepted\":[],\"rejected\":[]}}");

        List<TrackingEvent> events = client.queryTracking("COSU123", "COSCO");

        assertTrue(events.isEmpty());
    }

    @Test
    @DisplayName("对端业务错误码：抛 CALL_FAILED，不能返回空列表")
    void upstreamErrorCodeIsExplicit() {
        properties.setEnabled(true);
        properties.setApiKey("token");
        when(httpClient.post(anyString(), anyString(), anyMap(), anyString()))
                .thenReturn("{\"code\":401,\"message\":\"invalid token\"}");

        ConnectorException ex = assertThrows(ConnectorException.class,
                () -> client.queryTracking("COSU123", "COSCO"));

        assertEquals(ConnectorException.Reason.CALL_FAILED, ex.getReason());
    }

    @Test
    @DisplayName("运单被 rejected：抛 CALL_FAILED，避免误判为暂无轨迹")
    void rejectedShipmentIsExplicit() {
        properties.setEnabled(true);
        properties.setApiKey("token");
        when(httpClient.post(anyString(), anyString(), anyMap(), anyString()))
                .thenReturn("{\"code\":0,\"data\":{\"accepted\":[],\"rejected\":[{\"number\":\"COSU123\","
                        + "\"error\":{\"code\":-1,\"message\":\"invalid number\"}}]}}");

        ConnectorException ex = assertThrows(ConnectorException.class,
                () -> client.queryTracking("COSU123", "COSCO"));

        assertEquals(ConnectorException.Reason.CALL_FAILED, ex.getReason());
    }
}
