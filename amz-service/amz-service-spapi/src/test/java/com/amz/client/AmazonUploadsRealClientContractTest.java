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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@DisplayName("Amazon Uploads 官方契约（201 / contentMD5 / 预签名 PUT fail-closed）")
class AmazonUploadsRealClientContractTest {

    private static final long SHOP_ID = 1001L;
    private static final String MARKETPLACE_ID = "ATVPDKIKX0DER";
    private static final String TOKEN = "Atza|uploads-contract-token";
    private static final String TOKEN_JSON =
            "{\"access_token\":\"" + TOKEN + "\",\"token_type\":\"bearer\",\"expires_in\":3600}";
    private static final String RESOURCE =
            "/messaging/v1/orders/123-1234567-1234567/messages/legalDisclosure";
    private static final String PRESIGNED_URL =
            "https://amz-test-bucket.s3.amazonaws.com/upload/legal-disclosure.pdf"
                    + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                    + "&X-Amz-Credential=AKIAEXAMPLEKEY%2F20260925%2Fus-east-1%2Fs3%2Faws4_request"
                    + "&X-Amz-Date=20260925T000000Z&X-Amz-Expires=300"
                    + "&X-Amz-SignedHeaders=content-md5%3Bhost"
                    + "&X-Amz-Signature=deadbeefdeadbeefdeadbeefdeadbeef";

    @Test
    @DisplayName("创建上传目的地：官方 POST 路径、marketplaceIds + 32 位小写 hex contentMD5、仅接受 201")
    void createDestinationUsesOfficialContract() {
        byte[] content = "synthetic invoice bytes".getBytes(StandardCharsets.UTF_8);
        String md5 = md5Hex(content);
        RecordingHttpTransport transport = RecordingHttpTransport.of(request -> {
            if ("/auth/o2/token".equals(request.uri().getPath())) {
                return new RecordingHttpTransport.Reply(200, TOKEN_JSON);
            }
            return new RecordingHttpTransport.Reply(201,
                    "{\"payload\":{\"uploadDestinationId\":\"dest-1\",\"url\":\"" + PRESIGNED_URL
                            + "\",\"headers\":{\"Content-MD5\":\"abc==\"}}}");
        });

        JsonObject response = client(transport).createUploadDestinationForResource(
                SHOP_ID, MARKETPLACE_ID, md5, RESOURCE, "application/pdf");

        assertEquals("dest-1", response.getAsJsonObject("payload")
                .get("uploadDestinationId").getAsString());
        HttpRequest request = transport.requests().stream()
                .filter(item -> "POST".equals(item.method())
                && item.uri().getPath().startsWith("/uploads/"))
                .findFirst()
                .orElseThrow();
        assertEquals("/uploads/2020-11-01/uploadDestinations"
                + "/messaging/v1/orders/123-1234567-1234567/messages/legalDisclosure",
                request.uri().getPath());
        assertEquals("contentMD5=" + md5 + "&contentType=application%2Fpdf&marketplaceIds=" + MARKETPLACE_ID, request.uri().getRawQuery());
        assertEquals(TOKEN, request.headers().firstValue("x-amz-access-token").orElse(null));
        assertTrue(request.headers().firstValue("user-agent").isPresent());
    }

    @Test
    @DisplayName("resource 前导斜杠归一化，内部 / 分隔符不得被编码为 %2F")
    void resourceLeadingSlashIsNormalizedWithoutEncodingSeparators() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(request -> {
            if ("/auth/o2/token".equals(request.uri().getPath())) {
                return new RecordingHttpTransport.Reply(200, TOKEN_JSON);
            }
            return new RecordingHttpTransport.Reply(201, "{\"payload\":{}}");
        });
        AmazonUploadsRealClient client = client(transport);
        String md5 = md5Hex(new byte[] {1});

        client.createUploadDestinationForResource(SHOP_ID, MARKETPLACE_ID, md5, RESOURCE, null);
        client.createUploadDestinationForResource(SHOP_ID, MARKETPLACE_ID, md5,
                "messaging/v1/orders/123-1234567-1234567/messages/legalDisclosure", null);

        List<HttpRequest> posts = transport.requests().stream()
                .filter(item -> "POST".equals(item.method())
                && item.uri().getPath().startsWith("/uploads/"))
                .toList();
        assertEquals(2, posts.size());
        for (HttpRequest request : posts) {
            assertEquals("/uploads/2020-11-01/uploadDestinations"
                    + "/messaging/v1/orders/123-1234567-1234567/messages/legalDisclosure",
                    request.uri().getPath());
            assertFalse(request.uri().getRawPath().contains("%2F"), request.uri().getRawPath());
        }
    }

    @Test
    @DisplayName("非法 resource 在发请求前失败：空白、查询/片段、穿越、反斜杠、空段、控制字符")
    void invalidResourceFailsClosedBeforeSending() {
        RecordingHttpTransport transport = RecordingHttpTransport.json("{}");
        AmazonUploadsRealClient client = client(transport);
        String md5 = md5Hex(new byte[] {1});

        for (String resource : List.of(" ", "foo?bar", "foo#bar", "foo/../bar", "foo\\bar",
                "foo//bar", "foo bar", "foo\nbar", "/")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> client.createUploadDestinationForResource(
                            SHOP_ID, MARKETPLACE_ID, md5, resource, null),
                    resource);
            assertTrue(error.getMessage().contains("resource"), resource);
        }
        assertEquals(0, transport.requestCount(), "非法 resource 不得发出 token 或业务请求");
    }

    @Test
    @DisplayName("marketplaceId 缺失时在发请求前失败，不使用凭证默认站点兜底")
    void missingMarketplaceIdFailsClosedBeforeSending() {
        RecordingHttpTransport transport = RecordingHttpTransport.json("{}");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> client(transport).createUploadDestinationForResource(
                        SHOP_ID, " ", md5Hex(new byte[] {1}), RESOURCE, null));

        assertTrue(error.getMessage().contains("marketplaceId"), error.getMessage());
        assertEquals(0, transport.requestCount());
    }

    @Test
    @DisplayName("contentMD5 非法时在发请求前失败")
    void invalidContentMd5FailsClosedBeforeSending() {
        RecordingHttpTransport transport = RecordingHttpTransport.json("{}");
        AmazonUploadsRealClient client = client(transport);

        for (String md5 : List.of("", " ", "abc", "g".repeat(32), "a".repeat(31))) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> client.createUploadDestinationForResource(
                            SHOP_ID, MARKETPLACE_ID, md5, RESOURCE, null),
                    md5);
            assertTrue(error.getMessage().contains("contentMD5"), error.getMessage());
        }
        assertEquals(0, transport.requestCount());
    }

    @Test
    @DisplayName("content 为 null/空时在发请求前失败")
    void missingContentFailsClosedBeforeSending() {
        RecordingHttpTransport transport = RecordingHttpTransport.json("{}");
        AmazonUploadsRealClient client = client(transport);

        for (byte[] content : new byte[][] {null, new byte[0]}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> client.createUploadDestinationAndUpload(
                            SHOP_ID, MARKETPLACE_ID, RESOURCE, "application/pdf", content));
            assertTrue(error.getMessage().contains("content"), error.getMessage());
        }
        assertEquals(0, transport.requestCount());
    }

    @Test
    @DisplayName("创建上传目的地返回非 201 时抛错，不得伪装成功")
    void createDestinationFailsClosedOnNon201() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(request -> {
            if ("/auth/o2/token".equals(request.uri().getPath())) {
                return new RecordingHttpTransport.Reply(200, TOKEN_JSON);
            }
            return new RecordingHttpTransport.Reply(400,
                    "{\"errors\":[{\"message\":\"bad resource\"}]}");
        });

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> client(transport).createUploadDestinationForResource(
                        SHOP_ID, MARKETPLACE_ID, md5Hex(new byte[] {1}), RESOURCE, null));

        assertTrue(error.getMessage().contains("400"), error.getMessage());
    }

    @Test
    @DisplayName("闭环：原始二进制字节 PUT 到响应 URL，透传 S3 headers，且不带 LWA/AWS 凭证")
    void createAndUploadUsesReturnedUrlHeadersAndRawBytes() {
        byte[] content = new byte[] {0, 1, 2, (byte) 0xff, 10, 13, 42};
        String md5 = md5Hex(content);
        RecordingHttpTransport transport = RecordingHttpTransport.of(request -> {
            if ("/auth/o2/token".equals(request.uri().getPath())) {
                return new RecordingHttpTransport.Reply(200, TOKEN_JSON);
            }
            if ("POST".equals(request.method())) {
                return new RecordingHttpTransport.Reply(201,
                        "{\"payload\":{\"uploadDestinationId\":\"dest-binary\","
                                + "\"url\":\"" + PRESIGNED_URL + "\","
                                + "\"headers\":{\"Content-MD5\":\"YWJjZGVmZ2g=\","
                                + "\"x-amz-server-side-encryption\":\"AES256\"}}}");
            }
            return new RecordingHttpTransport.Reply(200, "");
        });

        String destinationId = client(transport).createUploadDestinationAndUpload(
                SHOP_ID, MARKETPLACE_ID, RESOURCE, "application/pdf", content);

        assertEquals("dest-binary", destinationId);
        HttpRequest create = transport.requests().stream()
                .filter(item -> "POST".equals(item.method())
                && item.uri().getPath().startsWith("/uploads/"))
                .findFirst()
                .orElseThrow();
        assertTrue(create.uri().getRawQuery().contains("contentMD5=" + md5),
                create.uri().getRawQuery());
        HttpRequest upload = transport.requests().stream()
                .filter(item -> "PUT".equals(item.method()))
                .findFirst()
                .orElseThrow();
        assertEquals(PRESIGNED_URL, upload.uri().toString());
        assertEquals("YWJjZGVmZ2g=", upload.headers().firstValue("Content-MD5").orElse(null));
        assertEquals("AES256", upload.headers()
                .firstValue("x-amz-server-side-encryption").orElse(null));
        assertEquals("application/pdf", upload.headers().firstValue("Content-Type").orElse(null));
        assertTrue(upload.headers().firstValue("x-amz-access-token").isEmpty());
        assertTrue(upload.headers().firstValue("Authorization").isEmpty());
        assertArrayEquals(content, RecordingHttpTransport.bodyBytes(upload));
    }

    @Test
    @DisplayName("预签名 PUT 非 200 时抛错，异常文本不泄露签名查询参数")
    void uploadFailureDoesNotLeakPresignedQuery() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(request -> {
            if ("/auth/o2/token".equals(request.uri().getPath())) {
                return new RecordingHttpTransport.Reply(200, TOKEN_JSON);
            }
            if ("POST".equals(request.method())) {
                return new RecordingHttpTransport.Reply(201,
                        "{\"payload\":{\"uploadDestinationId\":\"dest-403\","
                                + "\"url\":\"" + PRESIGNED_URL + "\","
                                + "\"headers\":{\"Content-MD5\":\"YWJj\"}}}");
            }
            return new RecordingHttpTransport.Reply(403,
                    "<Error><Code>SignatureDoesNotMatch</Code></Error>");
        });

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> client(transport).createUploadDestinationAndUpload(
                        SHOP_ID, MARKETPLACE_ID, RESOURCE, "application/pdf",
                        new byte[] {1, 2, 3}));

        String text = error.getMessage();
        assertTrue(text.contains("status=403"), text);
        assertTrue(text.contains("/upload/legal-disclosure.pdf"), text);
        assertFalse(text.contains("deadbeef"), text);
        assertFalse(text.contains("AKIAEXAMPLEKEY"), text);
        assertFalse(text.contains("X-Amz-Signature"), text);
        assertFalse(text.contains("X-Amz-Credential"), text);
    }

    private static AmazonUploadsRealClient client(RecordingHttpTransport transport) {
        SpApiConfig config = new SpApiConfig();
        config.setAppName("AmazonERP");
        config.setAppVersion("1.0-SNAPSHOT");
        config.setLanguage("Java/17.0.20.1");
        config.setLwaEndpoint(SpApiEndpointResolver.DEFAULT_LWA_ENDPOINT);
        config.setBaseUrlOverride("http://127.0.0.1:18080");
        SpApiEndpointResolver resolver = new SpApiEndpointResolver(config, false);
        SpApiRequestFactory factory = new SpApiRequestFactory(
                new AwsSigV4Signer(), new SpApiUserAgent(config), resolver);
        SpApiGateway gateway = new SpApiGateway(
                transport,
                new LwaTokenManager(transport, config, resolver),
                new StubCredentialStore().with(TestCredentials.northAmerica()),
                new SpiRateLimiter(),
                factory,
                noMetrics());
        return new AmazonUploadsRealClient(gateway);
    }

    private static String md5Hex(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(content);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >> 4) & 0xf, 16));
                hex.append(Character.forDigit(value & 0xf, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> noMetrics() {
        return mock(ObjectProvider.class);
    }
}