package com.amz.client;

import com.amz.auth.LwaTokenManager;
import com.amz.connector.LocalApiException;
import com.amz.connector.SpApiCallException;
import com.amz.connector.SpApiRequestFactory;
import com.amz.credential.ShopCredentialStore;
import com.amz.ratelimit.SpiRateLimiter;
import com.amz.testsupport.RecordingHttpTransport;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P0-53（写侧）：预签名上传 URL 不得进入异常文本或异常链。
 *
 * <p>Uploads API 返回的 URL 带写权限；传输驱动可能把完整 URL 放进
 * {@link RuntimeException} 或 checked exception 的消息。网关必须统一脱敏，
 * 并避免 controller 的 {@code log.error(..., e)} 沿 cause 打出原始 URL。
 */
@DisplayName("P0-53 预签名 URL 不出现在异常文本（SpApiGateway.uploadBytes）")
class SpApiGatewayUploadErrorTest {

    private static final String PRESIGNED_URL =
            "https://bucket.s3.amazonaws.com/upload/legal-disclosure.pdf"
                    + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                    + "&X-Amz-Credential=AKIAEXAMPLEKEY%2F20260925%2Fus-east-1%2Fs3%2Faws4_request"
                    + "&X-Amz-Date=20260925T000000Z&X-Amz-Expires=300&X-Amz-SignedHeaders=host"
                    + "&X-Amz-Signature=deadbeefdeadbeefdeadbeefdeadbeef";

    @Test
    @DisplayName("传输层 RuntimeException 携带完整 URL 时也必须脱敏且不链 cause")
    void runtimeTransportFailureDoesNotLeakQuery() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(request -> {
            throw new IllegalStateException("PUT " + PRESIGNED_URL + " failed");
        });
        SpApiGateway gateway = gatewayWith(transport, passthroughRequestFactory());

        SpApiCallException error = assertThrows(SpApiCallException.class,
                () -> gateway.uploadBytes(PRESIGNED_URL, Map.of(), "application/pdf", new byte[] {1}));

        String text = error.getMessage();
        assertEquals("s3.uploadDocument", error.getOperationId());
        assertEquals("/upload/legal-disclosure.pdf", error.getPath());
        assertEquals(-1, error.getPlatformStatus());
        assertTrue(text.contains("path=/upload/legal-disclosure.pdf"), text);
        assertFalse(text.contains("deadbeef"), text);
        assertFalse(text.contains("AKIAEXAMPLEKEY"), text);
        assertTrue(text.contains("X-Amz-Signature=***"), text);
        assertNull(error.getCause(),
                "不得链 cause：日志打印异常链时仍会泄露传输驱动消息中的完整 URL");
    }

    @Test
    @DisplayName("checked IOException 携带完整 URL 时也必须脱敏且不链 cause")
    void checkedTransportFailureDoesNotLeakQuery() {
        RecordingHttpTransport transport = RecordingHttpTransport.failing(
                new IOException("PUT " + PRESIGNED_URL + " failed"));
        SpApiGateway gateway = gatewayWith(transport, passthroughRequestFactory());

        SpApiCallException error = assertThrows(SpApiCallException.class,
                () -> gateway.uploadBytes(PRESIGNED_URL, Map.of(), "application/pdf", new byte[] {1}));

        String text = error.getMessage();
        assertEquals("s3.uploadDocument", error.getOperationId());
        assertEquals("/upload/legal-disclosure.pdf", error.getPath());
        assertEquals(-1, error.getPlatformStatus());
        assertTrue(text.contains("path=/upload/legal-disclosure.pdf"), text);
        assertFalse(text.contains("deadbeef"), text);
        assertFalse(text.contains("AKIAEXAMPLEKEY"), text);
        assertTrue(text.contains("X-Amz-Signature=***"), text);
        assertNull(error.getCause(),
                "不得链 cause：日志打印异常链时仍会泄露传输驱动消息中的完整 URL");
    }

    @Test
    @DisplayName("URI 构造失败只保留占位路径与脱敏原因")
    void malformedUrlDoesNotLeakQuery() {
        String rawUrl = "https://bucket.s3.amazonaws.com/upload/up load.pdf"
                + "?X-Amz-Credential=AKIAEXAMPLEKEY%2F20260925%2Fus-east-1%2Fs3%2Faws4_request"
                + "&X-Amz-Signature=deadbeefdeadbeefdeadbeefdeadbeef";
        SpApiRequestFactory factory = mock(SpApiRequestFactory.class);
        when(factory.presigned(any(), any(), any(), any(), any(), any())).thenThrow(
                new IllegalArgumentException("Illegal character in path: " + rawUrl));
        SpApiGateway gateway = gatewayWith(RecordingHttpTransport.json("{}"), factory);

        LocalApiException error = assertThrows(LocalApiException.class,
                () -> gateway.uploadBytes(rawUrl, Map.of(), "application/pdf", new byte[] {1}));

        assertEquals("PRESIGNED_URL_INVALID", error.getCode());
        String text = error.getMessage();
        assertTrue(text.startsWith("upload request rejected path=(unparseable url)"), text);
        assertFalse(text.contains("deadbeef"), text);
        assertFalse(text.contains("AKIAEXAMPLEKEY"), text);
        assertTrue(text.contains("X-Amz-Signature=***"), text);
        assertNull(error.getCause(), "不得把携带完整 URL 的原异常挂到 cause 链");
    }

    private static SpApiGateway gatewayWith(RecordingHttpTransport transport,
                                            SpApiRequestFactory requestFactory) {
        return new SpApiGateway(
                transport,
                mock(LwaTokenManager.class),
                mock(ShopCredentialStore.class),
                mock(SpiRateLimiter.class),
                requestFactory,
                mock(ObjectProvider.class));
    }

    /** 预签名分支按传入 URL 原样构造 PUT 请求，不注入 token 或 AWS 签名。 */
    private static SpApiRequestFactory passthroughRequestFactory() {
        SpApiRequestFactory factory = mock(SpApiRequestFactory.class);
        when(factory.presigned(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> HttpRequest.newBuilder(URI.create(invocation.getArgument(1)))
                        .timeout(Duration.ofSeconds(60))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(invocation.getArgument(3)))
                        .header("user-agent", "test-agent")
                        .build());
        return factory;
    }
}
