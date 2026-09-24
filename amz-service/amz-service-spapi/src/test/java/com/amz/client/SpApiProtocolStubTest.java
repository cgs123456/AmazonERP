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
import com.google.gson.JsonParser;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SP-API 协议桩回放：Feeds 四步全链路 + 429 退避/限流头回填 + Reports 文档下载。
 * <p>
 * 覆盖的操作序列（与官方 Feeds v2021-06-30 用例指南一致）：
 * <ol>
 *   <li>{@code POST /feeds/2021-06-30/documents} → {@code feedDocumentId} + 预签名上传 URL；</li>
 *   <li>{@code PUT <预签名 URL>} 上传内容（<b>不得</b>携带任何凭证）；</li>
 *   <li>{@code POST /feeds/2021-06-30/feeds}（引用 {@code inputFeedDocumentId} + marketplaceIds）；</li>
 *   <li>{@code GET /feeds/2021-06-30/feeds/{feedId}} 查询处理状态；</li>
 *   <li>{@code GET <预签名结果文档 URL>} 下载结果报告（P0-50：同样不带凭证）。</li>
 * </ol>
 * 全部出站经进程内 {@link RecordingHttpTransport}（零 socket）；证据类型 **E2**（桩回放）。
 * 本类不断言真实网络栈/Credentials/账号配额，**不**构成 A5 证据。
 */
@DisplayName("SP-API 协议桩回放（Feeds 全链路 / 429 回填 / 文档下载）")
class SpApiProtocolStubTest {

    private static final long SHOP_ID = 1001L;
    private static final String MARKETPLACE_ID = "ATVPDKIKX0DER";
    private static final String TOKEN = "Atza|protocol-stub-token";
    private static final String STUB_BASE = "http://127.0.0.1:19099";
    private static final String UPLOAD_URL =
            "https://amz-test-bucket.s3.amazonaws.com/feeds/upload-1?X-Amz-Signature=deadbeef";
    private static final String RESULT_URL =
            "https://amz-test-bucket.s3.amazonaws.com/feeds/result-1?X-Amz-Signature=cafebabe";
    private static final String TOKEN_JSON = "{\"access_token\":\"" + TOKEN + "\",\"token_type\":\"bearer\",\"expires_in\":3600}";

    private SpApiConfig config() {
        SpApiConfig config = new SpApiConfig();
        config.setAppName("AmazonERP");
        config.setAppVersion("1.0-SNAPSHOT");
        config.setLanguage("Java/17.0.20.1");
        config.setLwaEndpoint(SpApiEndpointResolver.DEFAULT_LWA_ENDPOINT);
        config.setBaseUrlOverride(STUB_BASE);
        return config;
    }

    private static SpApiEndpointResolver stubResolver(SpApiConfig config) {
        return new SpApiEndpointResolver(config, false);
    }

    private static SpApiRequestFactory requestFactory(SpApiConfig config, SpApiEndpointResolver resolver) {
        return new SpApiRequestFactory(new AwsSigV4Signer(), new SpApiUserAgent(config), resolver);
    }

    private static FeedsClient feedsClient(RecordingHttpTransport transport, SpApiConfig config,
                                           SpApiEndpointResolver resolver, SpiRateLimiter rateLimiter) {
        StubCredentialStore credentials = new StubCredentialStore().with(TestCredentials.northAmerica());
        return new FeedsClient(transport, new LwaTokenManager(transport, config, resolver), credentials,
                rateLimiter, requestFactory(config, resolver), noMetrics());
    }

    @Test
    @DisplayName("Feeds 四步全链路：请求序列、路径、必带头、幂等体全部锁定")
    void feedsHappyPathLocksRequestSequence() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(request -> {
            String path = request.uri().getPath();
            switch (path) {
                case "/auth/o2/token":
                    return new RecordingHttpTransport.Reply(200, TOKEN_JSON);
                case "/feeds/2021-06-30/documents":
                    return new RecordingHttpTransport.Reply(201,
                            "{\"feedDocumentId\":\"doc-1\",\"url\":\"" + UPLOAD_URL + "\"}");
                case "/feeds/2021-06-30/feeds":
                    return new RecordingHttpTransport.Reply(202, "{\"feedId\":\"feed-1\"}");
                case "/feeds/2021-06-30/feeds/feed-1":
                    return new RecordingHttpTransport.Reply(200,
                            "{\"feedId\":\"feed-1\",\"processingStatus\":\"DONE\","
                                    + "\"resultFeedDocumentId\":\"result-1\"}");
                default:
                    if ("amz-test-bucket.s3.amazonaws.com".equals(request.uri().getHost())) {
                        return new RecordingHttpTransport.Reply(200, "");
                    }
                    throw new AssertionError("未预置的请求：" + request.method() + " " + request.uri());
            }
        });
        SpApiConfig config = config();
        SpApiEndpointResolver resolver = stubResolver(config);
        FeedsClient client = feedsClient(transport, config, resolver, new SpiRateLimiter());

        String feedId = client.submitFeed(SHOP_ID, MARKETPLACE_ID, "{\"header\":{\"sellerId\":\"A1TEST\"}}");
        JsonObject status = client.getFeedStatus(SHOP_ID, feedId);

        assertEquals("feed-1", feedId);
        assertEquals("DONE", status.get("processingStatus").getAsString());
        assertEquals("result-1", status.get("resultFeedDocumentId").getAsString());

        List<HttpRequest> requests = transport.requests();
        assertEquals(5, requests.size(), "LWA + 3 次 SP-API + 1 次 S3 上传：" + describe(requests));

        assertEquals("POST", requests.get(0).method());
        assertEquals("/auth/o2/token", requests.get(0).uri().getPath(), "第 1 步必须是 LWA token 交换");
        assertEquals("POST", requests.get(1).method());
        assertEquals("/feeds/2021-06-30/documents", requests.get(1).uri().getPath());
        assertEquals("PUT", requests.get(2).method());
        assertEquals(UPLOAD_URL, requests.get(2).uri().toString(), "上传必须原样使用预签名 URL");
        assertEquals("POST", requests.get(3).method());
        assertEquals("/feeds/2021-06-30/feeds", requests.get(3).uri().getPath());
        assertEquals("GET", requests.get(4).method());
        assertEquals("/feeds/2021-06-30/feeds/feed-1", requests.get(4).uri().getPath());

        // 三个 SP-API 请求：走覆盖基址 + 必带头（user-agent / x-amz-access-token / x-amz-date）
        for (HttpRequest request : List.of(requests.get(1), requests.get(3), requests.get(4))) {
            assertEquals("http", request.uri().getScheme());
            assertEquals("127.0.0.1", request.uri().getHost(), "覆盖生效时全部指向桩基址");
            assertEquals(19099, request.uri().getPort());
            assertNotNull(request.headers().firstValue("user-agent").orElse(null), "P0-35 必填头");
            assertEquals(TOKEN, request.headers().firstValue("x-amz-access-token").orElse(null));
            assertNotNull(request.headers().firstValue("x-amz-date").orElse(null));
        }

        // 请求体契约
        assertEquals("{\"contentType\":\"application/json\"}",
                RecordingHttpTransport.bodyText(requests.get(1)));
        JsonObject createFeedBody = JsonParser.parseString(
                RecordingHttpTransport.bodyText(requests.get(3))).getAsJsonObject();
        assertEquals("JSON_LISTINGS_FEED", createFeedBody.get("feedType").getAsString());
        assertEquals("doc-1", createFeedBody.get("inputFeedDocumentId").getAsString());
        assertEquals(1, createFeedBody.getAsJsonArray("marketplaceIds").size());
        assertEquals(MARKETPLACE_ID, createFeedBody.getAsJsonArray("marketplaceIds").get(0).getAsString());
        assertEquals("{\"header\":{\"sellerId\":\"A1TEST\"}}",
                RecordingHttpTransport.bodyText(requests.get(2)), "上传内容必须原样发送");

        // P0-50：预签名上传不得携带任何凭证
        HttpRequest upload = requests.get(2);
        assertNull(upload.headers().firstValue("x-amz-access-token").orElse(null));
        assertNull(upload.headers().firstValue("Authorization").orElse(null));
        assertNotNull(upload.headers().firstValue("user-agent").orElse(null));
    }

    @Test
    @DisplayName("429：指数退避后重试同一请求，并回填 x-amzn-RateLimit-Limit 到本地限流桶")
    void throttlingRetriesAndBackfillsRateLimitHeader() {
        AtomicInteger documentCalls = new AtomicInteger();
        RecordingHttpTransport transport = RecordingHttpTransport.of(request -> {
            String path = request.uri().getPath();
            switch (path) {
                case "/auth/o2/token":
                    return new RecordingHttpTransport.Reply(200, TOKEN_JSON);
                case "/feeds/2021-06-30/documents":
                    if (documentCalls.incrementAndGet() == 1) {
                        return RecordingHttpTransport.Reply.withHeader(429,
                                "{\"errors\":[{\"code\":\"QuotaExceeded\"}]}",
                                "x-amzn-RateLimit-Limit", "0.1");
                    }
                    return new RecordingHttpTransport.Reply(201,
                            "{\"feedDocumentId\":\"doc-1\",\"url\":\"" + UPLOAD_URL + "\"}");
                case "/feeds/2021-06-30/feeds":
                    return new RecordingHttpTransport.Reply(202, "{\"feedId\":\"feed-1\"}");
                default:
                    if ("amz-test-bucket.s3.amazonaws.com".equals(request.uri().getHost())) {
                        return new RecordingHttpTransport.Reply(200, "");
                    }
                    throw new AssertionError("未预置的请求：" + request.method() + " " + request.uri());
            }
        });
        SpApiConfig config = config();
        SpApiEndpointResolver resolver = stubResolver(config);
        SpiRateLimiter rateLimiter = Mockito.spy(new SpiRateLimiter());
        FeedsClient client = feedsClient(transport, config, resolver, rateLimiter);

        assertEquals("feed-1", client.submitFeed(SHOP_ID, MARKETPLACE_ID, "{\"header\":{}}"));

        assertEquals(2, documentCalls.get(), "429 后必须重试同一请求（不得跳过步骤）");
        Mockito.verify(rateLimiter).updateLimit("feeds", "0.1");
        long documentRequests = transport.requests().stream()
                .filter(request -> "/feeds/2021-06-30/documents".equals(request.uri().getPath()))
                .count();
        assertEquals(2, documentRequests);
    }

    @Test
    @DisplayName("Reports 结果文档下载：预签名 URL 原样 GET，不带任何凭证")
    void reportDocumentDownloadUsesPresignedUrlWithoutCredentials() {
        RecordingHttpTransport transport = RecordingHttpTransport.json("report-body");
        SpApiConfig config = config();
        SpApiEndpointResolver resolver = stubResolver(config);
        StubCredentialStore credentials = new StubCredentialStore().with(TestCredentials.northAmerica());
        SpApiGateway gateway = new SpApiGateway(transport, new LwaTokenManager(transport, config, resolver),
                credentials, new SpiRateLimiter(), requestFactory(config, resolver), noMetrics());

        byte[] body = gateway.downloadBytes(RESULT_URL);

        assertEquals("report-body", new String(body, StandardCharsets.UTF_8));
        HttpRequest request = transport.lastRequest();
        assertEquals("GET", request.method());
        assertEquals(RESULT_URL, request.uri().toString(), "查询串必须原样保留（预签名参数不可改写）");
        assertNull(request.headers().firstValue("x-amz-access-token").orElse(null));
        assertNull(request.headers().firstValue("Authorization").orElse(null));
        assertNotNull(request.headers().firstValue("user-agent").orElse(null));
    }

    @Test
    @DisplayName("Reports 文档下载 404：显式失败，不得把错误页当报告返回")
    void reportDocumentDownloadFailsOn404() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(
                request -> new RecordingHttpTransport.Reply(404, "<?xml version=\"1.0\"?><Error/>"));
        SpApiConfig config = config();
        SpApiEndpointResolver resolver = stubResolver(config);
        SpApiGateway gateway = new SpApiGateway(transport, new LwaTokenManager(transport, config, resolver),
                new StubCredentialStore(), new SpiRateLimiter(), requestFactory(config, resolver), noMetrics());

        RuntimeException e = assertThrows(RuntimeException.class, () -> gateway.downloadBytes(RESULT_URL));
        assertTrue(e.getMessage().contains("404"), e.getMessage());
    }

    @Test
    @DisplayName("401：驱逐 LWA 缓存，下一次调用重新交换 token（不得在 TTL 内持续 401）")
    void unauthorizedInvalidatesLwaCache() {
        AtomicInteger tokenCalls = new AtomicInteger();
        AtomicInteger reportsCalls = new AtomicInteger();
        RecordingHttpTransport transport = RecordingHttpTransport.of(request -> {
            String path = request.uri().getPath();
            if ("/auth/o2/token".equals(path)) {
                tokenCalls.incrementAndGet();
                return new RecordingHttpTransport.Reply(200, TOKEN_JSON);
            }
            reportsCalls.incrementAndGet();
            return new RecordingHttpTransport.Reply(401,
                    "{\"errors\":[{\"code\":\"Unauthorized\",\"message\":\"token expired\"}]}");
        });
        SpApiConfig config = config();
        SpApiEndpointResolver resolver = stubResolver(config);
        StubCredentialStore credentials = new StubCredentialStore().with(TestCredentials.northAmerica());
        SpApiGateway gateway = new SpApiGateway(transport, new LwaTokenManager(transport, config, resolver),
                credentials, new SpiRateLimiter(), requestFactory(config, resolver), noMetrics());

        for (int i = 0; i < 2; i++) {
            RuntimeException e = assertThrows(RuntimeException.class, () -> gateway.callJson(
                    "GET", gateway.resolveShop(SHOP_ID, MARKETPLACE_ID), "reports",
                    "/reports/2021-06-30/reports", null, null));
            assertTrue(e.getMessage().contains("401"), e.getMessage());
        }

        assertEquals(2, reportsCalls.get());
        assertEquals(2, tokenCalls.get(), "401 后必须驱逐缓存并重新交换 token");
    }

    @Test
    @DisplayName("缺少 LWA access_token：请求在构造阶段失败（不发送无凭证请求）")
    void missingTokenFailsBeforeSending() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(request -> {
            if ("/auth/o2/token".equals(request.uri().getPath())) {
                return new RecordingHttpTransport.Reply(200, "{\"expires_in\":3600}");
            }
            return new RecordingHttpTransport.Reply(200, "{}");
        });
        SpApiConfig config = config();
        SpApiEndpointResolver resolver = stubResolver(config);
        FeedsClient client = feedsClient(transport, config, resolver, new SpiRateLimiter());

        assertThrows(RuntimeException.class,
                () -> client.submitFeed(SHOP_ID, MARKETPLACE_ID, "{\"header\":{}}"));
        List<String> paths = transport.requests().stream()
                .map(request -> request.uri().getPath())
                .toList();
        assertEquals(List.of("/auth/o2/token"), paths, "缺 token 时不得发出任何 SP-API 请求：" + paths);
    }

    private static String describe(List<HttpRequest> requests) {
        StringBuilder sb = new StringBuilder();
        for (HttpRequest request : requests) {
            if (sb.length() > 0) {
                sb.append(" -> ");
            }
            sb.append(request.method()).append(' ').append(request.uri().getPath());
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> noMetrics() {
        return Mockito.mock(ObjectProvider.class);
    }
}
