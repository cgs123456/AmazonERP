package com.amz.connector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-52a / P0-53：错误文本收敛与脱敏。
 * <p>
 * 本测试锁定三件事：① 根因（平台 status + errors body）必须活到边界层；
 * ② 预签名 URL / 令牌 / 密钥类值不得出现在给用户看的文本里；
 * ③ 文本必须有长度上限且永不返回 null/空串。
 * <p>
 * 证据类型 E1（自证）。本测试不证明 Amazon 接受我们的请求——那是 A5 联调的范围。
 */
@DisplayName("P0-52a/P0-53 错误文本收敛与脱敏（ErrorSummary）")
class ErrorSummaryTest {

    private static final String ERRORS_BODY =
            "{\"errors\":[{\"code\":\"Unauthorized\",\"message\":\"Access to requested resource is denied.\"}]}";

    private static final String PRESIGNED_URL =
            "https://bucket.s3.amazonaws.com/report/2026-09-24/settlement.tsv"
                    + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                    + "&X-Amz-Credential=AKIAEXAMPLEKEY%2F20260924%2Fus-east-1%2Fs3%2Faws4_request"
                    + "&X-Amz-Date=20260924T000000Z&X-Amz-Expires=300&X-Amz-SignedHeaders=host"
                    + "&X-Amz-Signature=deadbeefdeadbeefdeadbeefdeadbeef";

    /** 自引用 cause：任何未设上限的下钻实现都会在这里死循环。 */
    private static final class SelfCause extends RuntimeException {
        SelfCause(String message) {
            super(message);
        }

        @Override
        public Throwable getCause() {
            return this;
        }
    }

    @Test
    @DisplayName("null 入参返回 unknown error，不抛异常")
    void nullError() {
        assertEquals("unknown error", ErrorSummary.of(null));
    }

    @Test
    @DisplayName("单层异常：保留 HTTP status 与平台 errors body")
    void keepsStatusAndPlatformBody() {
        String text = ErrorSummary.of(new RuntimeException(
                "SP-API call failed method=GET path=/orders/v0/orders status=403 body=" + ERRORS_BODY));

        assertTrue(text.contains("status=403"), text);
        assertTrue(text.contains("\"code\":\"Unauthorized\""), text);
        assertTrue(text.contains("Access to requested resource is denied."), text);
    }

    @Test
    @DisplayName("异常链：取最深层根因，不被 degraded 包装吞掉")
    void prefersRootCause() {
        RuntimeException platform = new RuntimeException(
                "fetchOrders failed shopId=1 status=429 body={\"errors\":[{\"code\":\"QuotaExceeded\"}]}");
        RuntimeException degraded = new RuntimeException(
                "fetchOrders degraded (circuit-breaker/exception) shopId=1 marketplaceId=ATVPDKIKX0DER",
                platform);

        String text = ErrorSummary.of(degraded);

        assertTrue(text.contains("status=429"), text);
        assertTrue(text.contains("QuotaExceeded"), text);
        assertFalse(text.contains("degraded"), text);
    }

    @Test
    @DisplayName("根因无消息：回退到异常类名，不返回 null/空串")
    void fallsBackToClassName() {
        String text = ErrorSummary.of(new RuntimeException());

        assertTrue(text.contains("RuntimeException"), text);
        assertFalse(text.isEmpty());
    }

    @Test
    @DisplayName("多行/缩进压成单行（日志与响应各占一行）")
    void collapsesWhitespace() {
        String text = ErrorSummary.of(new RuntimeException("line1\n\tline2\r\n  line3"));

        assertEquals("RuntimeException: line1 line2 line3", text);
    }

    @Test
    @DisplayName("超长文本截断到 MAX_LENGTH，且上限不超过 1000")
    void truncatesLongText() {
        String text = ErrorSummary.of(new RuntimeException("x".repeat(5000)));

        assertEquals(ErrorSummary.MAX_LENGTH, text.length());
        assertTrue(text.endsWith("…"), text);
        assertTrue(ErrorSummary.MAX_LENGTH <= 1000, "上限必须保持有界");
    }

    @Test
    @DisplayName("预签名 URL：签名与凭证被掩掉，过期时间与对象路径保留")
    void redactsPresignedUrl() {
        String text = ErrorSummary.of(new RuntimeException("download failed status=403 url=" + PRESIGNED_URL));

        assertFalse(text.contains("deadbeef"), text);
        assertFalse(text.contains("AKIAEXAMPLEKEY"), text);
        assertTrue(text.contains("X-Amz-Signature=***"), text);
        assertTrue(text.contains("X-Amz-Credential=***"), text);
        assertTrue(text.contains("X-Amz-Expires=300"), text);
        assertTrue(text.contains("/report/2026-09-24/settlement.tsv"), text);
        assertTrue(text.contains("status=403"), text);
    }

    @Test
    @DisplayName("请求头里的令牌与口令被掩掉")
    void redactsHeaderSecrets() {
        String text = ErrorSummary.of(new RuntimeException(
                "x-amz-access-token: Atza|IwEBIB7secret password=hunter2"));

        assertFalse(text.contains("Atza|IwEBIB7secret"), text);
        assertFalse(text.contains("hunter2"), text);
        assertTrue(text.contains("x-amz-access-token: ***"), text);
        assertTrue(text.contains("password=***"), text);
    }

    @Test
    @DisplayName("Authorization: Bearer/Basic 令牌被掩掉")
    void redactsBearerToken() {
        String text = ErrorSummary.redact("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig");

        assertEquals("Authorization: ***", text);
    }

    @Test
    @DisplayName("平台 errors 文本逐字保留（不改大小写、不吞字段）")
    void preservesPlatformErrorTextVerbatim() {
        String text = ErrorSummary.of(new RuntimeException(ERRORS_BODY));

        assertEquals("RuntimeException: " + ERRORS_BODY, text);
    }

    @Test
    @DisplayName("自引用 cause 不死循环（下钻有上限）")
    void survivesSelfReferencingCause() {
        String text = ErrorSummary.of(new SelfCause("self"));

        assertTrue(text.contains("SelfCause"), text);
        assertTrue(text.contains("self"), text);
    }

    @Test
    @DisplayName("脱敏幂等：重复调用不改变结果")
    void redactIsIdempotent() {
        String once = ErrorSummary.redact("url=" + PRESIGNED_URL + " status=403");

        assertEquals(once, ErrorSummary.redact(once));
    }

    @Test
    @DisplayName("objectPath：只回对象路径，查询串整体丢弃（缺失/非法 URL 退化为占位符）")
    void objectPathKeepsOnlyPath() {
        String path = ErrorSummary.objectPath(PRESIGNED_URL);

        assertEquals("/report/2026-09-24/settlement.tsv", path);
        assertFalse(path.contains("X-Amz-"), path);
        assertEquals("(no url)", ErrorSummary.objectPath(null));
        assertEquals("(no url)", ErrorSummary.objectPath("   "));
        assertEquals("(no path)", ErrorSummary.objectPath("https://bucket.s3.amazonaws.com"));
        assertEquals("(unparseable url)",
                ErrorSummary.objectPath("https://bucket.s3.amazonaws.com/report/up load?x=1"));
    }
}