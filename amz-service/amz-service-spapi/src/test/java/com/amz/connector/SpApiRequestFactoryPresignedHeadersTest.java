package com.amz.connector;

import com.amz.auth.AwsSigV4Signer;
import com.amz.auth.SpApiUserAgent;
import com.amz.config.SpApiConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预签名请求头安全契约：S3 返回的 headers 只允许携带签名所需的业务头，
 * 不得覆盖鉴权、主机、长度或连接管理头，也不得引入 CRLF。
 */
@DisplayName("预签名请求头：fail-closed 与受保护头")
class SpApiRequestFactoryPresignedHeadersTest {

    private static final String PRESIGNED_URL =
            "https://bucket.s3.amazonaws.com/upload/file.pdf"
                    + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                    + "&X-Amz-Credential=AKIAEXAMPLEKEY%2F20260925%2Fus-east-1%2Fs3%2Faws4_request"
                    + "&X-Amz-Date=20260925T000000Z&X-Amz-Expires=300"
                    + "&X-Amz-Signature=deadbeefdeadbeefdeadbeefdeadbeef";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final byte[] BODY = "synthetic".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("鉴权、主机、长度与连接管理头不得由响应 headers 覆盖")
    void protectedHeadersAreRejected() {
        List<String> protectedNames = List.of(
                "Authorization",
                "x-amz-access-token",
                "user-agent",
                "host",
                "content-length",
                "connection",
                "expect",
                "upgrade",
                "x-amz-date",
                "x-amz-security-token");

        for (String name : protectedNames) {
            SpApiRequestFactory factory = factory();
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> factory.presigned("PUT", PRESIGNED_URL, null, BODY, TIMEOUT,
                            Map.of(name, "attacker-value")),
                    name);
            assertTrue(error.getMessage().contains("受保护头"), name + " -> " + error.getMessage());
        }
    }

    @Test
    @DisplayName("header 名或值含 CRLF 时必须拒绝，防止请求头注入")
    void crlfInNameOrValueIsRejected() {
        SpApiRequestFactory factory = factory();
        IllegalArgumentException nameError = assertThrows(IllegalArgumentException.class,
                () -> factory.presigned("PUT", PRESIGNED_URL, null, BODY, TIMEOUT,
                        Map.of("X-Test\r\nX-Evil", "value")));
        assertTrue(nameError.getMessage().contains("非法换行"), nameError.getMessage());

        IllegalArgumentException valueError = assertThrows(IllegalArgumentException.class,
                () -> factory.presigned("PUT", PRESIGNED_URL, null, BODY, TIMEOUT,
                        Map.of("X-Test", "value\r\nX-Evil: injected")));
        assertTrue(valueError.getMessage().contains("非法换行"), valueError.getMessage());
    }

    @Test
    @DisplayName("空 header 值与 contentType 二义性必须拒绝")
    void blankValueAndContentTypeConflictAreRejected() {
        SpApiRequestFactory factory = factory();
        IllegalArgumentException blank = assertThrows(IllegalArgumentException.class,
                () -> factory.presigned("PUT", PRESIGNED_URL, null, BODY, TIMEOUT,
                        Map.of("Content-MD5", "   ")));
        assertTrue(blank.getMessage().contains("不得为空"), blank.getMessage());

        IllegalArgumentException conflict = assertThrows(IllegalArgumentException.class,
                () -> factory.presigned("PUT", PRESIGNED_URL, "application/pdf", BODY, TIMEOUT,
                        Map.of("content-type", "text/plain")));
        assertTrue(conflict.getMessage().contains("重复"), conflict.getMessage());
    }

    @Test
    @DisplayName("允许的 Content-MD5 / SSE 头原样透传，且绝不注入 token 或 Authorization")
    void safeHeadersAreForwardedWithoutTokens() {
        HttpRequest request = factory().presigned("PUT", PRESIGNED_URL, "application/pdf", BODY,
                TIMEOUT, Map.of(
                        "Content-MD5", "YWJjZGVmZ2g=",
                        "x-amz-server-side-encryption", "AES256"));

        assertEquals(PRESIGNED_URL, request.uri().toString());
        assertEquals("YWJjZGVmZ2g=", request.headers().firstValue("Content-MD5").orElse(null));
        assertEquals("AES256", request.headers()
                .firstValue("x-amz-server-side-encryption").orElse(null));
        assertEquals("application/pdf", request.headers().firstValue("Content-Type").orElse(null));
        assertTrue(request.headers().firstValue("user-agent").isPresent());
        assertTrue(request.headers().firstValue("x-amz-access-token").isEmpty());
        assertTrue(request.headers().firstValue("Authorization").isEmpty());
        assertFalse(request.headers().firstValue("host").isPresent(),
                "host 必须由 HTTP 客户端按目标 URL 生成，不能被响应头覆盖");
    }

    private static SpApiRequestFactory factory() {
        SpApiConfig config = new SpApiConfig();
        config.setAppName("AmazonERP");
        config.setAppVersion("1.0-SNAPSHOT");
        config.setLanguage("Java/17.0.20.1");
        config.setLwaEndpoint(SpApiEndpointResolver.DEFAULT_LWA_ENDPOINT);
        SpApiEndpointResolver resolver = new SpApiEndpointResolver(config, false);
        return new SpApiRequestFactory(new AwsSigV4Signer(), new SpApiUserAgent(config), resolver);
    }
}
