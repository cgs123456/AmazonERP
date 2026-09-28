package com.amz.auth;

import com.amz.config.SpApiConfig;
import com.amz.connector.HttpTransport;
import com.amz.testsupport.RecordingHttpTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("LWA grantless：client_credentials、缓存隔离与失败关闭")
class LwaTokenManagerGrantlessTest {

    private static final String SCOPE = "sellingpartnerapi::notifications";
    private static final String TOKEN_JSON =
            "{\"access_token\":\"Atza|grantless-token\",\"token_type\":\"bearer\",\"expires_in\":3600}";

    @Test
    @DisplayName("Notifications grantless 使用 client_credentials，表单不得混入卖家 refresh_token")
    void sendsClientCredentialsGrant() {
        RecordingHttpTransport transport = RecordingHttpTransport.json(TOKEN_JSON);
        LwaTokenManager manager = manager(transport);

        assertEquals("Atza|grantless-token", manager.getGrantlessToken("client-1", "secret-1", SCOPE));

        HttpRequest request = transport.lastRequest();
        assertEquals("POST", request.method());
        assertEquals("https://api.amazon.com/auth/o2/token", request.uri().toString());
        Map<String, String> form = form(RecordingHttpTransport.bodyText(request));
        assertEquals("client_credentials", form.get("grant_type"));
        assertEquals(SCOPE, form.get("scope"));
        assertEquals("client-1", form.get("client_id"));
        assertEquals("secret-1", form.get("client_secret"));
        assertFalse(form.containsKey("refresh_token"), "grantless 请求绝不能混入卖家 refresh_token");
    }

    @Test
    @DisplayName("grantless token 按 clientId + clientSecret + scope 缓存，跨 scope 不串用")
    void cachesByCredentialAndScope() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(request -> {
            Map<String, String> form = form(RecordingHttpTransport.bodyText(request));
            return new RecordingHttpTransport.Reply(200,
                    "{\"access_token\":\"token-" + form.get("scope") + "\","
                            + "\"token_type\":\"bearer\",\"expires_in\":3600}");
        });
        LwaTokenManager manager = manager(transport);

        assertEquals("token-sellingpartnerapi::notifications",
                manager.getGrantlessToken("client-1", "secret-1", SCOPE));
        assertEquals("token-sellingpartnerapi::notifications",
                manager.getGrantlessToken("client-1", "secret-1", SCOPE));
        assertEquals(1, transport.requestCount(), "同一凭证与 scope 必须命中缓存");

        assertEquals("token-sellingpartnerapi::reports",
                manager.getGrantlessToken("client-1", "secret-1", "sellingpartnerapi::reports"));
        assertEquals(2, transport.requestCount(), "不同 scope 必须隔离缓存");
    }

    @Test
    @DisplayName("grantless token 在相同 clientId、不同 clientSecret 时不得串用")
    void isolatesDifferentClientSecrets() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(request -> {
            Map<String, String> form = form(RecordingHttpTransport.bodyText(request));
            return new RecordingHttpTransport.Reply(200,
                    "{\"access_token\":\"token-" + form.get("client_secret") + "\","
                            + "\"token_type\":\"bearer\",\"expires_in\":3600}");
        });
        LwaTokenManager manager = manager(transport);

        assertEquals("token-secret-A", manager.getGrantlessToken("client-1", "secret-A", SCOPE));
        assertEquals("token-secret-B", manager.getGrantlessToken("client-1", "secret-B", SCOPE));
        assertEquals(2, transport.requestCount());
    }

    @Test
    @DisplayName("精确失效只移除对应 grantless 缓存，下一次调用重新换取")
    void invalidateGrantlessForcesRefresh() {
        RecordingHttpTransport transport = RecordingHttpTransport.json(TOKEN_JSON);
        LwaTokenManager manager = manager(transport);

        manager.getGrantlessToken("client-1", "secret-1", SCOPE);
        manager.invalidateGrantless("client-1", "secret-1", SCOPE);
        manager.getGrantlessToken("client-1", "secret-1", SCOPE);

        assertEquals(2, transport.requestCount());
    }

    @Test
    @DisplayName("clientId/clientSecret/scope 空白必须在出站前失败")
    void blankInputsFailBeforeTransport() {
        RecordingHttpTransport transport = RecordingHttpTransport.json(TOKEN_JSON);
        LwaTokenManager manager = manager(transport);

        assertThrows(IllegalArgumentException.class,
                () -> manager.getGrantlessToken(" ", "secret-1", SCOPE));
        assertThrows(IllegalArgumentException.class,
                () -> manager.getGrantlessToken("client-1", "", SCOPE));
        assertThrows(IllegalArgumentException.class,
                () -> manager.getGrantlessToken("client-1", "secret-1", " "));
        assertEquals(0, transport.requestCount());
    }

    private static LwaTokenManager manager(HttpTransport transport) {
        SpApiConfig config = new SpApiConfig();
        config.setLwaEndpoint("https://api.amazon.com/auth/o2/token");
        return new LwaTokenManager(transport, config);
    }

    private static Map<String, String> form(String body) {
        return Arrays.stream(body.split("&"))
                .map(part -> part.split("=", 2))
                .collect(Collectors.toMap(
                        pair -> decode(pair[0]),
                        pair -> decode(pair.length > 1 ? pair[1] : "")));
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}