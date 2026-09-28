package com.amz.connector;

import com.amz.result.ApiError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P0-52a：SP-API 失败响应的类型化字段与本地错误分类。
 * <p>
 * 本测试只证明进程内契约，不证明 Amazon 的真实响应一定包含 requestId 或 errors[0]；
 * 后者必须在真实凭证联调时取证。
 */
@DisplayName("P0-52a 类型化错误与本地/上游分类")
class SpApiCallExceptionTest {

    @Test
    @DisplayName("解析 errors[0].code/message 与 x-amzn-RequestId")
    void parsesPlatformFieldsAndRequestId() {
        SpApiCallException error = SpApiCallException.fromResponse(
                "orders.getOrders", "/orders/v0/orders",
                response(429,
                        "{\"errors\":[{\"code\":\"QuotaExceeded\",\"message\":\"Request is throttled\"}]}",
                        Map.of("x-amzn-RequestId", List.of("req-1"))));

        assertEquals(429, error.getPlatformStatus());
        assertEquals("QuotaExceeded", error.getPlatformCode());
        assertEquals("Request is throttled", error.getPlatformMessage());
        assertEquals("req-1", error.getRequestId());
        assertTrue(error.getMessage().contains("status=429"), error.getMessage());
    }

    @Test
    @DisplayName("平台消息与 requestId 中的凭证值被脱敏")
    void redactsPlatformMessageAndRequestId() {
        SpApiCallException error = SpApiCallException.fromResponse(
                "orders.getOrders", "/orders/v0/orders",
                response(403,
                        "{\"errors\":[{\"code\":\"Unauthorized\",\"message\":\"access_token=secret X-Amz-Signature=deadbeef\"}]}",
                        Map.of("x-amzn-RequestId", List.of("req-1 access_token=secret"))));

        assertFalse(error.getPlatformMessage().contains("secret"), error.getPlatformMessage());
        assertFalse(error.getPlatformMessage().contains("deadbeef"), error.getPlatformMessage());
        assertTrue(error.getPlatformMessage().contains("access_token=***"), error.getPlatformMessage());
        assertFalse(error.getRequestId().contains("secret"), error.getRequestId());
        assertTrue(error.getRequestId().contains("access_token=***"), error.getRequestId());
    }

    @Test
    @DisplayName("传输失败没有平台状态，转换为 ApiError 时保持 null")
    void transportFailureHasNoPlatformStatus() {
        SpApiCallException error = SpApiCallException.transportFailure(
                "s3.downloadDocument", "/report/1.tsv", "connection reset");

        ApiError apiError = ErrorSummary.toApiError(error);

        assertEquals("SPAPI_CALL_FAILED", apiError.getCode());
        assertNull(apiError.getPlatformStatus());
        assertNull(apiError.getPlatformCode());
        assertNull(apiError.getRequestId());
    }

    @Test
    @DisplayName("S3 状态失败只保留状态码与安全路径，不保存响应体")
    void statusFailureKeepsStatusWithoutUrl() {
        SpApiCallException error = SpApiCallException.statusFailure(
                "s3.uploadDocument", "/upload/file.pdf", 403);

        assertEquals(403, error.getPlatformStatus());
        assertFalse(error.getMessage().contains("X-Amz-Signature"), error.getMessage());
        assertFalse(error.getMessage().contains("X-Amz-Credential"), error.getMessage());
    }

    @Test
    @DisplayName("Sentinel/熔断包装链仍能提取 SpApiCallException")
    void findsTypedErrorThroughWrapperChain() {
        SpApiCallException cause = SpApiCallException.fromResponse(
                "orders.getOrders", "/orders/v0/orders",
                response(429,
                        "{\"errors\":[{\"code\":\"QuotaExceeded\",\"message\":\"Request is throttled\"}]}",
                        Map.of("x-amzn-RequestId", List.of("req-2"))));
        RuntimeException wrapped = new RuntimeException("degraded", new IllegalStateException("sentinel", cause));

        ApiError apiError = ErrorSummary.toApiError(wrapped);

        assertEquals("SPAPI_CALL_FAILED", apiError.getCode());
        assertEquals(429, apiError.getPlatformStatus());
        assertEquals("QuotaExceeded", apiError.getPlatformCode());
        assertEquals("req-2", apiError.getRequestId());
    }

    @Test
    @DisplayName("本地错误保留自己的稳定错误码，不伪装成上游故障")
    void localFailureKeepsLocalCode() {
        LocalApiException local = LocalApiException.of(
                "PRESIGNED_URL_INVALID",
                "download request rejected path=/report/1.tsv reason=access_token=secret");

        ApiError apiError = ErrorSummary.toApiError(new RuntimeException("wrapper", local));

        assertEquals("PRESIGNED_URL_INVALID", apiError.getCode());
        assertNull(apiError.getPlatformStatus());
        assertFalse(local.getMessage().contains("secret"), local.getMessage());
    }

    @Test
    @DisplayName("参数校验与未知 marketplace 分类为本地错误")
    void classifiesLocalValidationErrors() {
        ApiError invalid = ErrorSummary.toApiError(new IllegalArgumentException("shopId must be positive"));
        ApiError marketplace = ErrorSummary.toApiError(new UnknownMarketplaceException(
                UnknownMarketplaceException.CODE_UNKNOWN_MARKETPLACE,
                "unknown marketplaceId=BAD"));

        assertEquals("INVALID_REQUEST", invalid.getCode());
        assertEquals(UnknownMarketplaceException.CODE_UNKNOWN_MARKETPLACE, marketplace.getCode());
        assertNull(invalid.getPlatformStatus());
        assertNull(marketplace.getPlatformStatus());
    }

    private static HttpResponse<String> response(int status, String body,
                                                 Map<String, List<String>> headers) {
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
        return response;
    }
}
