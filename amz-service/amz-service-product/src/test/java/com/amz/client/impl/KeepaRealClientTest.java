package com.amz.client.impl;

import com.amz.exception.ConnectorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KeepaRealClientTest {

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> response;

    private KeepaRealClient client;

    @BeforeEach
    void setUp() {
        client = new KeepaRealClient(httpClient, "https://api.keepa.test/product");
    }

    @Test
    @DisplayName("未配置 API key：显式 NOT_CONFIGURED，且不发起 HTTP 请求")
    void missingApiKeyFailsClosed() {
        ConnectorException ex = assertThrows(ConnectorException.class,
                () -> client.getPriceHistory("B000TEST", 1));

        assertEquals(ConnectorException.Reason.NOT_CONFIGURED, ex.getReason());
        assertEquals("Keepa", ex.getConnector());
        verifyNoInteractions(httpClient);
    }

    @Test
    @DisplayName("HTTP 500：显式 CALL_FAILED，不返回 null")
    void httpFailureIsExplicit() throws Exception {
        ReflectionTestUtils.setField(client, "apiKey", "secret");
        when(response.statusCode()).thenReturn(500);
        when(httpClient.send(any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);

        ConnectorException ex = assertThrows(ConnectorException.class,
                () -> client.getRankHistory("B000TEST", 1));

        assertEquals(ConnectorException.Reason.CALL_FAILED, ex.getReason());
    }

    @Test
    @DisplayName("HTTP 200 且 JSON 合法：原样返回对端响应")
    void successfulResponseIsReturned() throws Exception {
        ReflectionTestUtils.setField(client, "apiKey", "secret");
        String body = "{\"products\":[{\"asin\":\"B000TEST\"}]}";
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body);
        when(httpClient.send(any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);

        assertEquals(body, client.getCompetitorAnalysis("B000TEST", 1));
    }
}
