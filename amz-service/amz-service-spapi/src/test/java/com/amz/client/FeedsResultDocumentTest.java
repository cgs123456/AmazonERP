package com.amz.client;

import com.amz.auth.AwsSigV4Signer;
import com.amz.auth.LwaTokenManager;
import com.amz.auth.SpApiUserAgent;
import com.amz.config.SpApiConfig;
import com.amz.connector.SpApiEndpointResolver;
import com.amz.connector.SpApiRequestFactory;
import com.amz.model.FeedIssue;
import com.amz.model.FeedResult;
import com.amz.ratelimit.SpiRateLimiter;
import com.amz.testsupport.RecordingHttpTransport;
import com.amz.testsupport.StubCredentialStore;
import com.amz.testsupport.TestCredentials;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feeds processing report 下载与解析契约。
 * <p>
 * 夹具是 SYNTHETIC 数据，仅形状参考官方 list-feed processing report schema v2。
 * 证据类型 E2（进程内桩回放），不覆盖真实 S3 网络行为。
 */
@DisplayName("Feeds processing report 下载与解析")
class FeedsResultDocumentTest {

    private static final long SHOP_ID = 1001L;
    private static final String MARKETPLACE_ID = "ATVPDKIKX0DER";
    private static final String TOKEN = "Atza|feeds-result-stub-token";
    private static final String STUB_BASE = "http://127.0.0.1:19099";
    private static final String RESULT_URL =
            "https://amz-test-bucket.s3.amazonaws.com/feeds/result-1.gz?X-Amz-Signature=cafebabe";
    private static final String TOKEN_JSON =
            "{\"access_token\":\"" + TOKEN + "\",\"token_type\":\"bearer\",\"expires_in\":3600}";

    @Test
    @DisplayName("DONE 后下载 GZIP processing report，解析 summary 与 issues")
    void downloadsGzipReportAndParsesIssues() throws IOException {
        byte[] gzip = gzip(loadFixture());
        RecordingHttpTransport transport = RecordingHttpTransport.of(request -> {
            String path = request.uri().getPath();
            if ("/auth/o2/token".equals(path)) {
                return new RecordingHttpTransport.Reply(200, TOKEN_JSON);
            }
            if ("/feeds/2021-06-30/feeds/feed-1".equals(path)) {
                return new RecordingHttpTransport.Reply(200,
                        "{\"feedId\":\"feed-1\",\"processingStatus\":\"DONE\","
                                + "\"resultFeedDocumentId\":\"result-doc-1\"}");
            }
            if ("/feeds/2021-06-30/documents/result-doc-1".equals(path)) {
                return new RecordingHttpTransport.Reply(200,
                        "{\"feedDocumentId\":\"result-doc-1\",\"url\":\"" + RESULT_URL + "\","
                                + "\"compressionAlgorithm\":\"GZIP\"}");
            }
            if ("amz-test-bucket.s3.amazonaws.com".equals(request.uri().getHost())) {
                return RecordingHttpTransport.Reply.ofBytes(200, gzip);
            }
            throw new AssertionError("未预置的请求：" + request.method() + " " + request.uri());
        });
        FeedsClient client = client(transport);

        FeedResult result = client.fetchFeedResult(SHOP_ID, "feed-1");

        assertEquals("feed-1", result.getFeedId());
        assertEquals("result-doc-1", result.getResultFeedDocumentId());
        assertEquals(4, result.getMessagesProcessed());
        assertEquals(2, result.getMessagesAccepted());
        assertEquals(2, result.getMessagesInvalid());
        assertEquals(1, result.getErrors());
        assertEquals(1, result.getWarnings());
        assertEquals(2, result.getIssues().size());

        FeedIssue error = result.getIssues().get(0);
        assertEquals(2, error.getRowIndex());
        assertEquals("SKU-B", error.getSellerSku());
        assertEquals("90220", error.getErrorCode());
        assertEquals("ERROR", error.getSeverity());
        assertEquals("SYNTHETIC: invalid attribute for SKU-B", error.getErrorMessage());

        assertFalse(result.isSuccessful(), "存在 ERROR 拒绝行时不得判为成功");
        assertTrue(result.isPartial(), "有成功行也有拒绝行时应判为部分成功");

        List<HttpRequest> requests = transport.requests();
        assertEquals(4, requests.size(), "LWA + getFeed + getFeedDocument + S3 download");
        assertEquals("GET", requests.get(1).method());
        assertEquals("/feeds/2021-06-30/feeds/feed-1", requests.get(1).uri().getPath());
        assertEquals("GET", requests.get(2).method());
        assertEquals("/feeds/2021-06-30/documents/result-doc-1", requests.get(2).uri().getPath());
        assertEquals(TOKEN, requests.get(2).headers().firstValue("x-amz-access-token").orElse(null));

        HttpRequest download = requests.get(3);
        assertEquals("GET", download.method());
        assertEquals(RESULT_URL, download.uri().toString(), "预签名 URL 必须原样使用");
        assertNull(download.headers().firstValue("x-amz-access-token").orElse(null));
        assertNull(download.headers().firstValue("Authorization").orElse(null));
        assertNotNull(download.headers().firstValue("user-agent").orElse(null));
    }

    @Test
    @DisplayName("DONE 但缺少 resultFeedDocumentId 时必须失败，不得返回空成功结果")
    void missingResultDocumentIdFailsClosed() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(request -> {
            if ("/auth/o2/token".equals(request.uri().getPath())) {
                return new RecordingHttpTransport.Reply(200, TOKEN_JSON);
            }
            if ("/feeds/2021-06-30/feeds/feed-2".equals(request.uri().getPath())) {
                return new RecordingHttpTransport.Reply(200,
                        "{\"feedId\":\"feed-2\",\"processingStatus\":\"DONE\"}");
            }
            throw new AssertionError("缺少结果文档 ID 后不得继续请求：" + request.uri());
        });
        FeedsClient client = client(transport);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> client.fetchFeedResult(SHOP_ID, "feed-2"));

        assertTrue(error.getMessage().contains("resultFeedDocumentId"), error.getMessage());
    }

    private static String loadFixture() throws IOException {
        try (var in = FeedsResultDocumentTest.class.getResourceAsStream("/feeds/result-sample.json")) {
            assertNotNull(in, "缺少 /feeds/result-sample.json");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private static FeedsClient client(RecordingHttpTransport transport) {
        SpApiConfig config = config();
        SpApiEndpointResolver resolver = new SpApiEndpointResolver(config, false);
        StubCredentialStore credentials = new StubCredentialStore().with(TestCredentials.northAmerica());
        SpApiRequestFactory requestFactory =
                new SpApiRequestFactory(new AwsSigV4Signer(), new SpApiUserAgent(config), resolver);
        return new FeedsClient(transport, new LwaTokenManager(transport, config, resolver), credentials,
                new SpiRateLimiter(), requestFactory, noMetrics());
    }

    private static SpApiConfig config() {
        SpApiConfig config = new SpApiConfig();
        config.setAppName("AmazonERP");
        config.setAppVersion("1.0-SNAPSHOT");
        config.setLanguage("Java/17.0.20.1");
        config.setLwaEndpoint(SpApiEndpointResolver.DEFAULT_LWA_ENDPOINT);
        config.setBaseUrlOverride(STUB_BASE);
        return config;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> noMetrics() {
        return Mockito.mock(ObjectProvider.class);
    }
}