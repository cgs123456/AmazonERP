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
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * P0-53（<b>写侧</b>）：预签名<b>上传</b> URL 不得进入异常文本与日志链。
 * <p>
 * 与 {@link SpApiGatewayDownloadErrorTest}（读侧下载）成对：上传预签名 URL 携带的是<b>写权限</b>，
 * 泄露后可被用于覆盖 Feed 文档；入口在 {@code FeedsClient.uploadDocument}。
 * 本类锁定三件事：① 异常文本只含对象路径 + 已脱敏原因；② 签名/凭证参数不出现；
 * ③ <b>不链 cause</b>——controller 的 {@code log.error(..., e)} 会打印整条异常链，
 * 链上原异常（{@code URI.create} 拒绝时的 JDK 文本）仍带完整 URL。
 * <p>
 * 全部出站经进程内 {@link RecordingHttpTransport}（零 socket）；证据类型 **E2**（桩回放）。
 * 不覆盖真实 S3 行为，**不**构成 A5 证据。
 */
@DisplayName("P0-53（写侧）预签名上传 URL 不进异常文本（FeedsClient.uploadDocument）")
class FeedsClientUploadUrlLeakTest {

    private static final long SHOP_ID = 1001L;
    private static final String MARKETPLACE_ID = "ATVPDKIKX0DER";
    private static final String STUB_BASE = "http://127.0.0.1:19099";
    private static final String TOKEN_JSON =
            "{\"access_token\":\"Atza|feeds-leak-stub\",\"token_type\":\"bearer\",\"expires_in\":3600}";

    /** 路径故意含空格：{@code URI.create} 会抛异常，且 JDK 会把完整 URL 回显进异常文本。 */
    private static final String MALFORMED_UPLOAD_URL =
            "https://amz-test-bucket.s3.amazonaws.com/feeds/up load-1"
                    + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                    + "&X-Amz-Credential=AKIAEXAMPLEKEY%2F20260924%2Fus-east-1%2Fs3%2Faws4_request"
                    + "&X-Amz-Date=20260924T000000Z&X-Amz-Expires=300"
                    + "&X-Amz-Signature=deadbeefdeadbeefdeadbeefdeadbeef";

    @Test
    @DisplayName("createFeedDocument 返回不可解析的上传 URL：不泄露签名，且不链 cause")
    void malformedUploadUrlDoesNotLeakSignature() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(request -> {
            String path = request.uri().getPath();
            if ("/auth/o2/token".equals(path)) {
                return new RecordingHttpTransport.Reply(200, TOKEN_JSON);
            }
            if ("/feeds/2021-06-30/documents".equals(path)) {
                return new RecordingHttpTransport.Reply(201,
                        "{\"feedDocumentId\":\"doc-1\",\"url\":\"" + MALFORMED_UPLOAD_URL + "\"}");
            }
            throw new AssertionError("上传失败后不得继续发请求：" + request.method() + " " + request.uri());
        });

        FeedsClient client = client(transport);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> client.submitFeed(SHOP_ID, MARKETPLACE_ID, "{\"header\":{}}"));

        String text = error.getMessage();
        assertTrue(text.startsWith("upload request rejected path="), text);
        assertTrue(text.contains("(unparseable url)"), text);
        assertFalse(text.contains("deadbeef"), text);
        assertFalse(text.contains("AKIAEXAMPLEKEY"), text);
        // 保留的是参数**键名**与对象路径（非机密）；**值**必须掩成 ***（与读侧同口径）
        assertTrue(text.contains("X-Amz-Signature=***"), text);
        assertTrue(text.contains("X-Amz-Credential=***"), text);
        assertTrue(text.contains("X-Amz-Expires=300"), text);
        assertNull(error.getCause(),
                "不得链 cause：log.error(..., e) 会打印整条链，原异常文本里是完整预签名 URL");
        assertEquals(2, transport.requestCount(), "失败必须停在上传步骤（LWA + 建文档），不得继续提交 Feed");
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

    /** 未配置指标注册表：recordThrottle 退化为无指标（软依赖口径）。 */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> noMetrics() {
        return mock(ObjectProvider.class);
    }
}