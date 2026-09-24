package com.amz.client;

import com.amz.auth.AwsSigV4Signer;
import com.amz.auth.LwaTokenManager;
import com.amz.auth.SpApiUserAgent;
import com.amz.client.dto.ReportInfo;
import com.amz.config.SpApiConfig;
import com.amz.connector.SpApiEndpointResolver;
import com.amz.connector.SpApiRequestFactory;
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
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reports 结算链路桩回放（P0-27 的回归锁）。
 * <p>
 * 覆盖的三件事：
 * <ol>
 *   <li>{@code GET /reports/2021-06-30/reports/{reportId}} 的 {@code reportDocumentId}
 *       必须落到 {@link ReportInfo#getDocumentId()}——这是修复前恒为 null 的那一格；</li>
 *   <li>该断言不是橡皮图章：把响应里的字段换回旧名 {@code resultDocumentId} 时，
 *       {@code documentId} 必须仍为 null（证明夹具真的能区分两个名字）；</li>
 *   <li>{@code downloadDocument} 全链路：文档元数据走带 token 的 SP-API 主机，
 *       随后按元数据里的预签名 URL 下载 <b>GZIP</b> 字节并解压为 TSV，
 *       且该预签名请求不得携带任何凭证（P0-50）。</li>
 * </ol>
 * 全部出站经进程内 {@link RecordingHttpTransport}（零 socket）；证据类型 **E2**（桩回放），
 * 不构成 A5（需凭证联调，见 spec §1.9.1）。
 */
@DisplayName("Reports 结算链路桩回放（P0-27 回归锁）")
class ReportsRealClientStubTest {

    private static final long SHOP_ID = 1001L;
    private static final String TOKEN = "Atza|reports-stub-token";
    private static final String STUB_BASE = "http://127.0.0.1:19099";
    private static final String TOKEN_JSON =
            "{\"access_token\":\"" + TOKEN + "\",\"token_type\":\"bearer\",\"expires_in\":3600}";

    private static final String REPORT_ID = "r-1";
    private static final String DOCUMENT_ID = "doc-9";
    private static final String REPORT_PATH = "/reports/2021-06-30/reports/" + REPORT_ID;
    private static final String DOCUMENT_META_PATH = "/reports/2021-06-30/documents/" + DOCUMENT_ID;

    /** LWA 令牌交换端点（预签名 URL 之外唯一的非 SP-API 主机请求）。 */
    private static final String LWA_PATH = "/auth/o2/token";

    /** 预签名 URL 的主机名用于断言"最后一跳没有打到 SP-API 主机"。 */
    private static final String PRESIGNED_HOST = "amz-test-bucket.s3.amazonaws.com";
    private static final String PRESIGNED_URL =
            "https://" + PRESIGNED_HOST + "/reports/settlement-1?X-Amz-Signature=cafebabe";

    /** 官方 Report 定义的真实字段名（reports_2021-06-30.json）。 */
    private static final String DONE_REPORT_JSON = "{"
            + "\"reportId\":\"" + REPORT_ID + "\","
            + "\"reportType\":\"GET_V2_SETTLEMENT_REPORT_DATA_FLAT_FILE\","
            + "\"processingStatus\":\"DONE\","
            + "\"reportDocumentId\":\"" + DOCUMENT_ID + "\"}";

    /** P0-27 的旧（错误）字段名，仅用于反证夹具的区分能力。 */
    private static final String DONE_REPORT_JSON_WRONG_FIELD = "{"
            + "\"reportId\":\"" + REPORT_ID + "\","
            + "\"reportType\":\"GET_V2_SETTLEMENT_REPORT_DATA_FLAT_FILE\","
            + "\"processingStatus\":\"DONE\","
            + "\"resultDocumentId\":\"" + DOCUMENT_ID + "\"}";

    private static final String DOCUMENT_META_JSON = "{"
            + "\"reportDocumentId\":\"" + DOCUMENT_ID + "\","
            + "\"url\":\"" + PRESIGNED_URL + "\","
            + "\"compressionAlgorithm\":\"GZIP\"}";

    /** 结算原表的一个最小样例（TSV，官方 GET_V2_SETTLEMENT_REPORT_DATA_FLAT_FILE 形态）。 */
    private static final String SETTLEMENT_TSV =
            "settlement-id\tmarketplace-name\tsku\tquantity-purchased\n"
                    + "1234567890\tAmazon.com\tSKU-1\t2\n";

    @Test
    @DisplayName("DONE 报表的 reportDocumentId 落到 ReportInfo.documentId（P0-27）")
    void doneReportMapsOfficialDocumentId() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(request -> {
            String path = request.uri().getPath();
            if (LWA_PATH.equals(path)) {
                return new RecordingHttpTransport.Reply(200, TOKEN_JSON);
            }
            if (REPORT_PATH.equals(path)) {
                return new RecordingHttpTransport.Reply(200, DONE_REPORT_JSON);
            }
            return new RecordingHttpTransport.Reply(404, "{\"errors\":[{\"code\":\"NotFound\"}]}");
        });

        ReportInfo info = client(transport).getReport(SHOP_ID, REPORT_ID);

        assertEquals(REPORT_ID, info.getReportId());
        assertEquals("GET_V2_SETTLEMENT_REPORT_DATA_FLAT_FILE", info.getReportType());
        assertEquals(ReportInfo.STATUS_DONE, info.getProcessingStatus());
        assertTrue(info.isDone());
        assertTrue(info.isTerminal());
        assertEquals(DOCUMENT_ID, info.getDocumentId(),
                "P0-27：报表 DONE 时必须带出官方字段 reportDocumentId，否则结算原表永远下载不到");

        assertEquals(List.of("/auth/o2/token", REPORT_PATH), describe(transport.requests()));
        assertNotNull(transport.requests().get(1).headers().firstValue("x-amz-access-token").orElse(null),
                "SP-API 请求必须携带 x-amz-access-token");
    }

    @Test
    @DisplayName("反证：响应里只给旧字段名 resultDocumentId 时，documentId 必须仍为 null")
    void legacyFieldNameWouldNotSatisfyTheContract() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(request -> {
            String path = request.uri().getPath();
            if (LWA_PATH.equals(path)) {
                return new RecordingHttpTransport.Reply(200, TOKEN_JSON);
            }
            return new RecordingHttpTransport.Reply(200, DONE_REPORT_JSON_WRONG_FIELD);
        });

        ReportInfo info = client(transport).getReport(SHOP_ID, REPORT_ID);

        assertEquals(ReportInfo.STATUS_DONE, info.getProcessingStatus());
        assertNull(info.getDocumentId(),
                "夹具必须能区分 reportDocumentId 与 resultDocumentId，否则上一个测试等于橡皮图章");
    }

    @Test
    @DisplayName("downloadDocument：先取元数据（带 token），再按预签名 URL 下载 GZIP 并解压（不带凭证）")
    void downloadDocumentFollowsPresignedUrlWithoutCredentials() throws Exception {
        byte[] gzipped = gzip(SETTLEMENT_TSV);
        RecordingHttpTransport transport = RecordingHttpTransport.of(request -> {
            String path = request.uri().getPath();
            if (LWA_PATH.equals(path)) {
                return new RecordingHttpTransport.Reply(200, TOKEN_JSON);
            }
            if (DOCUMENT_META_PATH.equals(path)) {
                return new RecordingHttpTransport.Reply(200, DOCUMENT_META_JSON);
            }
            if (PRESIGNED_URL.equals(request.uri().toString())) {
                return RecordingHttpTransport.Reply.ofBytes(200, gzipped);
            }
            return new RecordingHttpTransport.Reply(404, "{\"errors\":[{\"code\":\"NotFound\"}]}");
        });

        String content = client(transport).downloadDocument(SHOP_ID, DOCUMENT_ID);

        assertEquals(SETTLEMENT_TSV, content, "GZIP 结算原表必须按 compressionAlgorithm 解压为 TSV");

        assertEquals(3, transport.requestCount(),
                "请求序列必须是 token → 文档元数据 → 预签名下载，实际：" + describe(transport.requests()));

        HttpRequest metaRequest = transport.requests().get(1);
        assertEquals(DOCUMENT_META_PATH, metaRequest.uri().getPath());
        assertNotNull(metaRequest.headers().firstValue("x-amz-access-token").orElse(null),
                "文档元数据请求是 SP-API 调用，必须带 token");

        HttpRequest downloadRequest = transport.lastRequest();
        assertEquals(PRESIGNED_HOST, downloadRequest.uri().getHost(),
                "最后一跳必须打预签名 URL 的主机，而不是 SP-API 主机");
        assertEquals(PRESIGNED_URL, downloadRequest.uri().toString(),
                "预签名 URL 必须原样使用，禁止改写查询串");
        assertNull(downloadRequest.headers().firstValue("x-amz-access-token").orElse(null),
                "P0-50：预签名请求不得携带 LWA access token");
        assertNull(downloadRequest.headers().firstValue("Authorization").orElse(null),
                "P0-50：预签名请求不得携带 AWS SigV4 Authorization");
        assertNull(downloadRequest.headers().firstValue("x-amz-date").orElse(null),
                "P0-50：预签名请求不得携带 SigV4 的 x-amz-date");
        assertNotNull(downloadRequest.headers().firstValue("user-agent").orElse(null),
                "SP-API 要求所有请求（含预签名 URL）带 user-agent");
    }

    private static ReportsRealClient client(RecordingHttpTransport transport) {
        SpApiConfig config = new SpApiConfig();
        config.setAppName("AmazonERP");
        config.setAppVersion("1.0-SNAPSHOT");
        config.setLanguage("Java/17.0.20.1");
        config.setLwaEndpoint(SpApiEndpointResolver.DEFAULT_LWA_ENDPOINT);
        config.setBaseUrlOverride(STUB_BASE);

        SpApiEndpointResolver resolver = new SpApiEndpointResolver(config, false);
        SpApiRequestFactory requestFactory = new SpApiRequestFactory(
                new AwsSigV4Signer(), new SpApiUserAgent(config), resolver);
        StubCredentialStore credentials = new StubCredentialStore().with(TestCredentials.northAmerica());
        SpApiGateway gateway = new SpApiGateway(transport, new LwaTokenManager(transport, config, resolver),
                credentials, new SpiRateLimiter(), requestFactory, noMetrics());
        return new ReportsRealClient(gateway);
    }

    private static byte[] gzip(String text) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private static List<String> describe(List<HttpRequest> requests) {
        return requests.stream().map(request -> request.uri().getPath()).toList();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> noMetrics() {
        return Mockito.mock(ObjectProvider.class);
    }
}