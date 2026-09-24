package com.amz.client;

import com.amz.auth.LwaTokenManager;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-53：预签名文档 URL 不得进入异常文本（否则会被边界层透出到 HTTP 响应与错误日志）。
 * <p>
 * 两个方向同时锁定：① 出站请求<b>仍然</b>使用完整预签名 URL（为脱敏而删查询参数会让下载直接 403）；
 * ② 异常文本只保留「状态码 + 对象路径」，不含签名与凭证查询参数。
 * <p>
 * 证据类型 E2（进程内桩，零 socket）。不覆盖真实 S3 行为；
 * 请求头口径（user-agent / 无 token）由 {@link SpApiRequiredHeaderContractTest} 用真实工厂断言，本类用 mock 工厂只锁 URL 原样传递。
 */
@DisplayName("P0-53 预签名 URL 不出现在异常文本（SpApiGateway.downloadBytes）")
class SpApiGatewayDownloadErrorTest {

    private static final String PRESIGNED_URL =
            "https://bucket.s3.amazonaws.com/report/2026-09-24/settlement.tsv"
                    + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                    + "&X-Amz-Credential=AKIAEXAMPLEKEY%2F20260924%2Fus-east-1%2Fs3%2Faws4_request"
                    + "&X-Amz-Date=20260924T000000Z&X-Amz-Expires=300&X-Amz-SignedHeaders=host"
                    + "&X-Amz-Signature=deadbeefdeadbeefdeadbeefdeadbeef";

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

    /** 真实工厂的预签名分支：按传入 URL 原样构造请求（不签名、不加 token）。 */
    private static SpApiRequestFactory passthroughRequestFactory() {
        SpApiRequestFactory factory = mock(SpApiRequestFactory.class);
        when(factory.presigned(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> HttpRequest.newBuilder(URI.create(invocation.getArgument(1)))
                        .timeout(Duration.ofSeconds(60))
                        .GET()
                        .build());
        return factory;
    }

    @Test
    @DisplayName("403 时异常文本含状态码与对象路径，但不含签名/凭证参数")
    void doesNotLeakPresignedQuery() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(
                request -> new RecordingHttpTransport.Reply(403,
                        "<Error><Code>AccessDenied</Code><Message>Request has expired</Message></Error>"));
        SpApiGateway gateway = gatewayWith(transport, passthroughRequestFactory());

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> gateway.downloadBytes(PRESIGNED_URL));

        String text = error.getMessage();
        assertTrue(text.contains("status=403"), text);
        assertTrue(text.contains("/report/2026-09-24/settlement.tsv"), text);
        assertFalse(text.contains("deadbeef"), text);
        assertFalse(text.contains("AKIAEXAMPLEKEY"), text);
        assertFalse(text.contains("X-Amz-Signature"), text);
        assertFalse(text.contains("X-Amz-Credential"), text);
    }

    @Test
    @DisplayName("出站请求仍使用完整预签名 URL，且不带 token / Authorization（P0-50 回归）")
    void stillSendsFullPresignedUrlWithoutTokens() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(
                request -> new RecordingHttpTransport.Reply(200, "ok"));
        SpApiRequestFactory factory = passthroughRequestFactory();
        SpApiGateway gateway = gatewayWith(transport, factory);

        assertEquals("ok", new String(gateway.downloadBytes(PRESIGNED_URL), StandardCharsets.UTF_8));

        HttpRequest sent = transport.lastRequest();
        assertEquals("GET", sent.method());
        assertEquals(PRESIGNED_URL, sent.uri().toString(),
                "查询串必须原样交给唯一构造点（为脱敏而改写 URL 会让 S3 直接 403）");
        assertTrue(sent.headers().firstValue("x-amz-access-token").isEmpty());
        assertTrue(sent.headers().firstValue("Authorization").isEmpty());
        // 网关自身不拼头：预签名分支必须走 requestFactory.presigned（P0-50 单一口径）
        verify(factory).presigned("GET", PRESIGNED_URL, null, null, Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("传输层失败也不泄露 URL 查询参数")
    void transportFailureDoesNotLeakQuery() {
        RecordingHttpTransport transport = RecordingHttpTransport.failing(
                new IOException("connection reset"));
        SpApiGateway gateway = gatewayWith(transport, passthroughRequestFactory());

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> gateway.downloadBytes(PRESIGNED_URL));

        String text = error.getMessage();
        assertFalse(text.contains("deadbeef"), text);
        assertFalse(text.contains("X-Amz-Signature"), text);
    }

    @Test
    @DisplayName("URI 构造失败：只留占位路径与脱敏原因，且不链 cause（日志侧不再带完整 URL）")
    void uriConstructionFailureKeepsUrlOutOfLogs() {
        String rawUrl = "https://bucket.s3.amazonaws.com/report/up load"
                + "?X-Amz-Credential=AKIAEXAMPLEKEY%2F20260924%2Fus-east-1%2Fs3%2Faws4_request"
                + "&X-Amz-Signature=deadbeefdeadbeefdeadbeefdeadbeef";
        SpApiRequestFactory factory = mock(SpApiRequestFactory.class);
        when(factory.presigned(any(), any(), any(), any(), any())).thenThrow(
                new IllegalArgumentException("Illegal character in path at index 45: " + rawUrl));
        SpApiGateway gateway = gatewayWith(RecordingHttpTransport.json("{}"), factory);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> gateway.downloadBytes(rawUrl));

        String text = error.getMessage();
        assertTrue(text.startsWith("download request rejected path="), text);
        assertTrue(text.contains("(unparseable url)"), text);
        assertFalse(text.contains("deadbeef"), text);
        assertFalse(text.contains("AKIAEXAMPLEKEY"), text);
        // 口径：异常文本保留 JDK 的原因短语（含 URL 骨架）与参数**键名**——它们不是机密；
        // 机密是**值**，必须掩成 ***。403/传输失败路径不拼 URL，因此那两条可断言整串键名都不出现。
        assertTrue(text.contains("X-Amz-Signature=***"), text);
        assertTrue(text.contains("X-Amz-Credential=***"), text);
        assertNull(error.getCause(),
                "不得链 cause：log.error(..., e) 会打印整条异常链，原异常文本里是完整预签名 URL");
    }
}