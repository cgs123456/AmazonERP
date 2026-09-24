package com.amz.client;

import com.amz.auth.AwsSigV4Signer;
import com.amz.auth.LwaTokenManager;
import com.amz.auth.SpApiUserAgent;
import com.amz.config.SpApiConfig;
import com.amz.connector.SpApiEndpointResolver;
import com.amz.connector.SpApiRequestFactory;
import com.amz.ratelimit.SpiRateLimiter;
import com.amz.testsupport.RecordingHttpTransport;
import com.amz.testsupport.StubCredentialStore;
import com.amz.testsupport.TestCredentials;
import com.google.gson.JsonObject;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.net.http.HttpRequest;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-35 / P0-38 / P0-50：SP-API 出站**请求头契约**（跨 4 个客户端 + 网关）。
 * <p>
 * 官方依据（一手）：{@code connecting-to-the-selling-partner-api} 页逐字——
 * “You must include a {@code user-agent} header in every request to the SP-API.”
 * 因此**每一个**发往 SP-API 主机的请求都必须带合法 user-agent（≤500 字符、含 App 名/版本/语言）。
 * <p>
 * P0-50：预签名 S3 URL（Feeds 上传 / Report 文档下载）**不是** SP-API 主机，
 * 绝不能携带 LWA access token 或 AWS 签名——否则等于把凭证交给第三方存储桶。
 * <p>
 * 测试用进程内传输桩，零 socket；证据上限 **E2**（契约构造）。
 */
@DisplayName("P0-35/P0-38/P0-50 SP-API 出站请求头契约")
class SpApiRequiredHeaderContractTest {

    private static final long SHOP_ID = 1001L;
    private static final String MARKETPLACE_ID = "ATVPDKIKX0DER";
    private static final String SPAPI_HOST_PREFIX = "sellingpartnerapi-";
    private static final String S3_URL =
            "https://amz-test-bucket.s3.amazonaws.com/reports/doc-1?X-Amz-Signature=deadbeef";
    private static final String TOKEN = "Atza|header-contract-token";

    private RecordingHttpTransport transport;
    private StubCredentialStore credentials;
    private SpApiGateway gateway;
    private OrdersClient ordersClient;
    private FeedsClient feedsClient;
    private FbaInventoryClient inventoryClient;

    @BeforeEach
    void setUp() {
        transport = RecordingHttpTransport.of(this::respond);
        credentials = new StubCredentialStore();

        SpApiConfig config = new SpApiConfig();
        config.setAppName("AmazonERP");
        config.setAppVersion("1.0-SNAPSHOT");
        config.setLanguage("Java/17.0.20.1");

        LwaTokenManager tokenManager = new LwaTokenManager(transport, config);
        SpApiRequestFactory requestFactory = new SpApiRequestFactory(
                new AwsSigV4Signer(), new SpApiUserAgent(config), SpApiEndpointResolver.officialOnly());
        SpiRateLimiter rateLimiter = new SpiRateLimiter();
        ObjectProvider<MeterRegistry> metrics = noMetrics();

        gateway = new SpApiGateway(transport, tokenManager, credentials, rateLimiter, requestFactory, metrics);
        ordersClient = new OrdersClient(transport, tokenManager, credentials, rateLimiter, requestFactory, metrics);
        feedsClient = new FeedsClient(transport, tokenManager, credentials, rateLimiter, requestFactory, metrics);
        inventoryClient = new FbaInventoryClient(transport, tokenManager, credentials, rateLimiter,
                requestFactory, metrics);
    }

    @Test
    @DisplayName("每个 SP-API 请求都带合法 user-agent 与 x-amz-access-token（覆盖 6 个调用点）")
    void everySpApiCallCarriesRequiredHeaders() {
        credentials.with(TestCredentials.northAmerica());

        driveAllSpApiCallSites();

        List<HttpRequest> spApiRequests = spApiRequests();
        assertTrue(spApiRequests.size() >= 7,
                "样本不足以覆盖全部调用点，仅记录到 " + spApiRequests.size() + " 个 SP-API 请求");
        for (HttpRequest request : spApiRequests) {
            assertValidUserAgent(request);
            assertEquals(TOKEN, request.headers().firstValue("x-amz-access-token").orElse(null),
                    "SP-API 请求必须带 LWA access token：" + request.uri());
        }
    }

    @Test
    @DisplayName("无 AWS 密钥：不带 Authorization 仍可调用，且保留 x-amz-date")
    void unsignedCallWhenShopHasNoAwsKeys() {
        credentials.with(TestCredentials.northAmerica());

        gateway.callJson("GET", gateway.resolveShop(SHOP_ID, MARKETPLACE_ID), "reports",
                "/reports/2021-09-01/reports", "reportTypes=GET_FLAT_FILE_ALL_ORDERS_DATA_BY_ORDER_DATE_GENERAL", null);

        HttpRequest request = transport.lastRequest();
        assertNull(request.headers().firstValue("Authorization").orElse(null),
                "缺 AK/SK 时不得带 Authorization：" + request.uri());
        assertNotNull(request.headers().firstValue("x-amz-date").orElse(null),
                "未签名调用仍需 x-amz-date（官方示例形态）");
        assertNotNull(request.headers().firstValue("x-amz-access-token").orElse(null));
        assertValidUserAgent(request);
    }

    @Test
    @DisplayName("有 AWS 密钥：签名作用域用 AWS region（us-east-1），不得直接写分组码 NA")
    void signedCallUsesAwsRegionInScope() {
        credentials.with(TestCredentials.withAwsKeys(TestCredentials.northAmerica()));

        gateway.callJson("GET", gateway.resolveShop(SHOP_ID, MARKETPLACE_ID), "reports",
                "/reports/2021-09-01/reports", null, null);

        String authorization = transport.lastRequest().headers().firstValue("Authorization").orElse(null);
        assertNotNull(authorization, "有 AK/SK 时应生成 SigV4 Authorization");
        assertTrue(authorization.contains("/us-east-1/execute-api/aws4_request"), authorization);
        assertFalse(authorization.contains("/NA/execute-api/"), authorization);
    }

    @Test
    @DisplayName("预签名 S3 请求（下载/上传）绝不携带 token 或签名，但仍标识 user-agent")
    void presignedS3RequestsNeverLeakCredentials() {
        credentials.with(TestCredentials.withAwsKeys(TestCredentials.northAmerica()));

        gateway.downloadBytes(S3_URL);
        feedsClient.submitFeed(SHOP_ID, MARKETPLACE_ID, "{\"header\":{}}");

        List<HttpRequest> s3Requests = transport.requests().stream()
                .filter(request -> request.uri().getHost().contains("s3.amazonaws.com"))
                .toList();
        assertEquals(2, s3Requests.size(), "应记录到 1 次 S3 下载 + 1 次 S3 上传：" + s3Requests.stream()
                .map(request -> request.method() + " " + request.uri()).toList());
        for (HttpRequest request : s3Requests) {
            assertNull(request.headers().firstValue("x-amz-access-token").orElse(null),
                    "预签名 URL 不得携带 LWA token：" + request.uri());
            assertNull(request.headers().firstValue("Authorization").orElse(null),
                    "预签名 URL 不得携带 AWS 签名：" + request.uri());
            assertValidUserAgent(request);
        }
    }

    @Test
    @DisplayName("SP-API 请求不得携带空 token；LWA 端点调用本身不带 user-agent 之外的额外凭证头")
    void tokenIsNeverBlank() {
        credentials.with(TestCredentials.northAmerica());

        inventoryClient.fetchAllInventory(SHOP_ID, MARKETPLACE_ID);

        for (HttpRequest request : transport.requests()) {
            String token = request.headers().firstValue("x-amz-access-token").orElse(null);
            if (token != null) {
                assertFalse(token.isBlank(), "token 不得为空：" + request.uri());
            }
        }
    }

    private void driveAllSpApiCallSites() {
        gateway.callJson("GET", gateway.resolveShop(SHOP_ID, MARKETPLACE_ID), "reports",
                "/reports/2021-09-01/reports", null, null);
        ordersClient.fetchOrders(SHOP_ID, MARKETPLACE_ID, Instant.parse("2026-09-01T00:00:00Z"), null);
        ordersClient.fetchOrderItems(SHOP_ID, MARKETPLACE_ID, "123-1234567-1234567");
        feedsClient.submitFeed(SHOP_ID, MARKETPLACE_ID, "{\"header\":{}}");
        feedsClient.getFeedStatus(SHOP_ID, "feed-1");
        inventoryClient.fetchAllInventory(SHOP_ID, MARKETPLACE_ID);
    }

    private List<HttpRequest> spApiRequests() {
        return transport.requests().stream()
                .filter(request -> request.uri().getHost().startsWith(SPAPI_HOST_PREFIX))
                .toList();
    }

    private static void assertValidUserAgent(HttpRequest request) {
        String userAgent = request.headers().firstValue("user-agent").orElse(null);
        assertNotNull(userAgent, "缺少官方必填 user-agent 头：" + request.uri());
        assertTrue(userAgent.length() <= 500, "user-agent 超过 500 字符：" + userAgent);
        assertTrue(userAgent.startsWith("AmazonERP/"), "user-agent 应以 App 名开头：" + userAgent);
        assertTrue(userAgent.contains(" (Language="), "user-agent 必须含语言属性：" + userAgent);
        assertFalse(userAgent.contains("null"), "user-agent 含 null：" + userAgent);
    }

    private RecordingHttpTransport.Reply respond(HttpRequest request) {
        String uri = request.uri().toString();
        String host = request.uri().getHost();
        if ("api.amazon.com".equals(host)) {
            return new RecordingHttpTransport.Reply(200,
                    "{\"access_token\":\"" + TOKEN + "\",\"token_type\":\"bearer\",\"expires_in\":3600}");
        }
        if (host.contains("s3.amazonaws.com")) {
            return new RecordingHttpTransport.Reply(200, "");
        }
        if (uri.contains("/feeds/2021-06-30/documents")) {
            return new RecordingHttpTransport.Reply(201,
                    "{\"feedDocumentId\":\"doc-1\",\"url\":\"" + S3_URL + "\"}");
        }
        if (uri.contains("/feeds/2021-06-30/feeds/feed-1")) {
            return new RecordingHttpTransport.Reply(200, "{\"processingStatus\":\"IN_QUEUE\"}");
        }
        if (uri.contains("/feeds/2021-06-30/feeds")) {
            return new RecordingHttpTransport.Reply(202, "{\"feedId\":\"feed-1\"}");
        }
        if (uri.contains("/orderItems")) {
            return new RecordingHttpTransport.Reply(200, "{\"payload\":{\"OrderItems\":[]}}");
        }
        if (uri.contains("/orders/v0/orders")) {
            return new RecordingHttpTransport.Reply(200, "{\"payload\":{\"Orders\":[]}}");
        }
        if (uri.contains("/fba/inventory/v1/summaries")) {
            return new RecordingHttpTransport.Reply(200, "{\"payload\":{\"inventorySummaries\":[]}}");
        }
        if (uri.contains("/reports/2021-09-01/reports")) {
            return new RecordingHttpTransport.Reply(200, "{\"payload\":{\"reports\":[]}}");
        }
        return new RecordingHttpTransport.Reply(200, "{\"payload\":{}}");
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> noMetrics() {
        return Mockito.mock(ObjectProvider.class);
    }
}
