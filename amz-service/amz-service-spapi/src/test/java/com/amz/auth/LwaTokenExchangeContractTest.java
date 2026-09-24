package com.amz.auth;

import com.amz.config.SpApiConfig;
import com.amz.credential.ShopCredential;
import com.amz.testsupport.RecordingHttpTransport;
import com.amz.testsupport.TestCredentials;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LWA（Login with Amazon）token 交换契约。
 * <p>
 * 依据（一手）：官方 {@code authorizing-access-to-sp-api} / LWA token 交换说明——
 * {@code POST https://api.amazon.com/auth/o2/token}，
 * {@code Content-Type: application/x-www-form-urlencoded}，
 * 表单含 {@code grant_type=refresh_token}、{@code refresh_token}、{@code client_id}、{@code client_secret}；
 * 成功响应含 {@code access_token}、{@code token_type=bearer}、{@code expires_in}、{@code refresh_token}。
 * <p>
 * 本测试用进程内传输桩替换 JDK HttpClient，因此在本仓库沙箱（无法构造 HttpClient）内也可执行；
 * 证据上限 **E2**（代码与官方 E3 夹具一致性），不产生 E4/E5，也不替代真实联调。
 */
@DisplayName("LWA token 交换契约（官方夹具 / endpoint / 表单 / 解析 / 失败面）")
class LwaTokenExchangeContractTest {

    private static final String TOKEN_ENDPOINT = "https://api.amazon.com/auth/o2/token";
    private static final String REQUEST_FIXTURE = "refresh-token-request.json";
    private static final String SUCCESS_FIXTURE = "refresh-token-success.json";
    private static final String PROVENANCE_FIXTURE = "provenance.json";

    private static SpApiConfig config() {
        SpApiConfig config = new SpApiConfig();
        config.setLwaEndpoint(TOKEN_ENDPOINT);
        return config;
    }

    @Test
    @DisplayName("官方请求夹具：URL / 方法 / 媒体类型 / 四个表单字段逐项一致")
    void officialRequestFixtureIsHonored() throws IOException {
        JsonObject fixture = fixtureJson(REQUEST_FIXTURE);
        JsonObject expectedHttp = fixture.getAsJsonObject("http");
        JsonObject expectedForm = fixture.getAsJsonObject("form");

        SpApiConfig config = new SpApiConfig();
        config.setLwaEndpoint(expectedHttp.get("url").getAsString());
        RecordingHttpTransport transport = RecordingHttpTransport.json(readFixture(SUCCESS_FIXTURE));
        LwaTokenManager manager = new LwaTokenManager(transport, config);

        ShopCredential credential = TestCredentials.northAmerica();
        credential.setClientId(expectedForm.get("client_id").getAsString());
        credential.setClientSecret(expectedForm.get("client_secret").getAsString());
        credential.setRefreshToken(expectedForm.get("refresh_token").getAsString());

        String expectedToken = fixtureJson(SUCCESS_FIXTURE).get("access_token").getAsString();
        assertEquals(expectedToken, manager.getToken(credential));

        HttpRequest request = transport.lastRequest();
        assertEquals(expectedHttp.get("method").getAsString(), request.method());
        assertEquals(expectedHttp.get("url").getAsString(), request.uri().toString());
        assertEquals(expectedHttp.get("contentTypeMediaType").getAsString(),
                request.headers().firstValue("Content-Type").orElse(null));

        Map<String, String> actualForm = parseForm(RecordingHttpTransport.bodyText(request));
        expectedForm.keySet().forEach(field ->
                assertEquals(expectedForm.get(field).getAsString(), actualForm.get(field),
                        "官方夹具字段不一致：" + field));
        assertFalse(RecordingHttpTransport.bodyText(request).contains("|"),
                "含 | 的 refresh_token 必须 URL 编码");
    }

    @Test
    @DisplayName("官方成功响应夹具：解析 access_token / token_type=bearer / expires_in")
    void officialSuccessFixtureParses() throws IOException {
        JsonObject fixture = fixtureJson(SUCCESS_FIXTURE);
        RecordingHttpTransport transport = RecordingHttpTransport.json(readFixture(SUCCESS_FIXTURE));
        LwaTokenManager manager = new LwaTokenManager(transport, config());

        String token = manager.getToken(TestCredentials.northAmerica());

        assertEquals(fixture.get("access_token").getAsString(), token);
        assertEquals("bearer", fixture.get("token_type").getAsString());
        assertTrue(fixture.get("access_token").getAsString().getBytes(StandardCharsets.UTF_8).length <= 2048);
        assertTrue(fixture.get("refresh_token").getAsString().getBytes(StandardCharsets.UTF_8).length <= 2048);
        assertEquals(1, transport.requestCount());
    }

    @Test
    @DisplayName("夹具 provenance：字节数与 SHA-256 必须和实际文件一致")
    void fixtureProvenanceHashesMatch() throws IOException {
        JsonObject provenance = fixtureJson(PROVENANCE_FIXTURE);
        JsonObject files = provenance.getAsJsonObject("files");

        assertFixtureDigest(files, REQUEST_FIXTURE);
        assertFixtureDigest(files, SUCCESS_FIXTURE);
        assertEquals(19403, provenance.getAsJsonObject("source").get("snapshotBytes").getAsInt());
        assertEquals(64, provenance.getAsJsonObject("source")
                .get("snapshotSha256").getAsString().length());
    }

    @Test
    @DisplayName("POST /auth/o2/token，表单四要素齐全且全部 URL 编码")
    void postsOfficialTokenForm() {
        RecordingHttpTransport transport = RecordingHttpTransport.json(tokenResponse("Atza|fake-token", 3600));
        LwaTokenManager manager = new LwaTokenManager(transport, config());
        ShopCredential credential = TestCredentials.northAmerica();

        assertEquals("Atza|fake-token", manager.getToken(credential));

        assertEquals(1, transport.requestCount(), "首次取 token 应恰好一次出站请求");
        HttpRequest request = transport.lastRequest();
        assertEquals("POST", request.method());
        assertEquals(TOKEN_ENDPOINT, request.uri().toString());
        assertEquals("application/x-www-form-urlencoded",
                request.headers().firstValue("Content-Type").orElse(null));

        String body = RecordingHttpTransport.bodyText(request);
        Map<String, String> parts = parseForm(body);
        assertEquals("refresh_token", parts.get("grant_type"));
        assertEquals(credential.getRefreshToken(), parts.get("refresh_token"));
        assertEquals(credential.getClientId(), parts.get("client_id"));
        assertEquals(credential.getClientSecret(), parts.get("client_secret"));

        assertTrue(body.contains("grant_type=refresh_token"), body);
        assertTrue(body.contains("refresh_token="
                + URLEncoder.encode(credential.getRefreshToken(), StandardCharsets.UTF_8)), body);
        assertFalse(body.contains("|"), "表单必须 URL 编码：" + body);
    }

    @Test
    @DisplayName("解析 access_token / expires_in，并在有效期内命中缓存（不重复出站）")
    void parsesTokenAndCachesIt() {
        RecordingHttpTransport transport = RecordingHttpTransport.json(tokenResponse("Atza|cached", 7200));
        LwaTokenManager manager = new LwaTokenManager(transport, config());
        ShopCredential credential = TestCredentials.europe();

        assertEquals("Atza|cached", manager.getToken(credential));
        assertEquals("Atza|cached", manager.getToken(credential));
        assertEquals(1, transport.requestCount(), "缓存命中不得再次请求 LWA");
    }

    @Test
    @DisplayName("缺少 access_token 必须失败（禁止伪造空 token 继续调用）")
    void missingAccessTokenFailsClosed() {
        RecordingHttpTransport transport = RecordingHttpTransport.json(
                "{\"token_type\":\"bearer\",\"expires_in\":3600}");
        LwaTokenManager manager = new LwaTokenManager(transport, config());

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> manager.getToken(TestCredentials.farEast()));
        assertTrue(e.getMessage().contains("access_token"), e.getMessage());
    }

    @Test
    @DisplayName("缺少 expires_in 必须失败（不得静默假定 3600 秒）")
    void missingExpiresInFailsClosed() {
        RecordingHttpTransport transport = RecordingHttpTransport.json(
                "{\"access_token\":\"Atza|no-expiry\",\"token_type\":\"bearer\"}");
        LwaTokenManager manager = new LwaTokenManager(transport, config());

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> manager.getToken(TestCredentials.northAmerica()));
        assertTrue(e.getMessage().contains("expires_in"), e.getMessage());
    }

    @Test
    @DisplayName("缺少 token_type 必须失败（官方成功响应不得省略）")
    void missingTokenTypeFailsClosed() {
        RecordingHttpTransport transport = RecordingHttpTransport.json(
                "{\"access_token\":\"Atza|no-type\",\"expires_in\":3600}");
        LwaTokenManager manager = new LwaTokenManager(transport, config());

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> manager.getToken(TestCredentials.northAmerica()));
        assertTrue(e.getMessage().contains("token_type"), e.getMessage());
    }

    @Test
    @DisplayName("token_type 非 bearer 必须失败（拒绝非官方 token 类型）")
    void invalidTokenTypeFailsClosed() {
        RecordingHttpTransport transport = RecordingHttpTransport.json(
                "{\"access_token\":\"Atza|wrong-type\",\"token_type\":\"mac\",\"expires_in\":3600}");
        LwaTokenManager manager = new LwaTokenManager(transport, config());

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> manager.getToken(TestCredentials.northAmerica()));
        assertTrue(e.getMessage().contains("token_type"), e.getMessage());
    }

    @Test
    @DisplayName("非 200 响应必须抛出，且错误信息保留状态码（不得静默降级）")
    void non200FailsClosed() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(
                request -> new RecordingHttpTransport.Reply(400,
                        "{\"error\":\"invalid_grant\",\"error_description\":\"refresh token is invalid\"}"));
        LwaTokenManager manager = new LwaTokenManager(transport, config());

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> manager.getToken(TestCredentials.northAmerica()));
        assertTrue(e.getMessage().contains("400"), e.getMessage());
    }

    @Test
    @DisplayName("传输层 IOException 必须抛出（不得吞掉后返回过期/空 token）")
    void transportFailurePropagates() {
        RecordingHttpTransport transport = RecordingHttpTransport.failing(new IOException("connect refused"));
        LwaTokenManager manager = new LwaTokenManager(transport, config());

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> manager.getToken(TestCredentials.northAmerica()));
        assertTrue(e.getMessage().contains(TestCredentials.FAKE_CLIENT_ID), e.getMessage());
        assertEquals(1, transport.requestCount());
    }

    private static String tokenResponse(String token, int expiresIn) {
        return "{\"access_token\":\"" + token + "\",\"token_type\":\"bearer\",\"expires_in\":"
                + expiresIn + "}";
    }

    private static void assertFixtureDigest(JsonObject files, String name) throws IOException {
        JsonObject metadata = files.getAsJsonObject(name);
        assertNotNull(metadata, "provenance 缺少 " + name);
        byte[] bytes = readFixture(name).getBytes(StandardCharsets.UTF_8);
        assertEquals(metadata.get("bytes").getAsInt(), bytes.length, name + " 字节数不一致");
        assertEquals(metadata.get("sha256").getAsString(), sha256Hex(bytes), name + " SHA-256 不一致");
    }

    private static JsonObject fixtureJson(String name) throws IOException {
        return JsonParser.parseString(readFixture(name)).getAsJsonObject();
    }

    private static String readFixture(String name) throws IOException {
        String resource = "/contracts/lwa-token/" + name;
        try (InputStream in = Objects.requireNonNull(
                LwaTokenExchangeContractTest.class.getResourceAsStream(resource),
                "缺少测试夹具：" + resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> parts = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            parts.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return parts;
    }
}