package com.amz.auth;

import com.amz.config.SpApiConfig;
import com.amz.credential.ShopCredential;
import com.amz.testsupport.RecordingHttpTransport;
import com.amz.testsupport.TestCredentials;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LWA（Login with Amazon）token 交换契约。
 * <p>
 * 依据（一手）：官方 {@code authorizing-access-to-sp-api} / LWA token 交换说明——
 * {@code POST https://api.amazon.com/auth/o2/token}，
 * {@code Content-Type: application/x-www-form-urlencoded}，
 * 表单含 {@code grant_type=refresh_token}、{@code refresh_token}、{@code client_id}、{@code client_secret}。
 * <p>
 * 本测试用进程内传输桩替换 JDK HttpClient，因此在本仓库沙箱（无法构造 HttpClient）内也可执行；
 * 证据上限 **E2**（契约构造），不产生 E3/E4/E5。
 */
@DisplayName("LWA token 交换契约（endpoint / 表单 / 解析 / 失败面）")
class LwaTokenExchangeContractTest {

    private static final String TOKEN_ENDPOINT = "https://api.amazon.com/auth/o2/token";

    private static SpApiConfig config() {
        SpApiConfig config = new SpApiConfig();
        config.setLwaEndpoint(TOKEN_ENDPOINT);
        return config;
    }

    @Test
    @DisplayName("POST /auth/o2/token，表单四要素齐全且全部 URL 编码")
    void postsOfficialTokenForm() {
        RecordingHttpTransport transport = RecordingHttpTransport.json(
                "{\"access_token\":\"Atza|fake-token\",\"expires_in\":3600}");
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

        // 编码证据：原始 body 必须是 URL 编码形态，明文 refresh_token（含 "|"）不得出现
        assertTrue(body.contains("grant_type=refresh_token"), body);
        assertTrue(body.contains("refresh_token="
                + URLEncoder.encode(credential.getRefreshToken(), StandardCharsets.UTF_8)), body);
        assertFalse(body.contains("|"), "表单必须 URL 编码：" + body);
    }

    @Test
    @DisplayName("解析 access_token / expires_in，并在有效期内命中缓存（不重复出站）")
    void parsesTokenAndCachesIt() {
        RecordingHttpTransport transport = RecordingHttpTransport.json(
                "{\"access_token\":\"Atza|cached\",\"expires_in\":7200}");
        LwaTokenManager manager = new LwaTokenManager(transport, config());
        ShopCredential credential = TestCredentials.europe();

        assertEquals("Atza|cached", manager.getToken(credential));
        assertEquals("Atza|cached", manager.getToken(credential));
        assertEquals(1, transport.requestCount(), "缓存命中不得再次请求 LWA");
    }

    @Test
    @DisplayName("缺少 access_token 必须失败（禁止伪造空 token 继续调用）")
    void missingAccessTokenFailsClosed() {
        RecordingHttpTransport transport = RecordingHttpTransport.json("{\"expires_in\":3600}");
        LwaTokenManager manager = new LwaTokenManager(transport, config());

        assertThrows(RuntimeException.class, () -> manager.getToken(TestCredentials.farEast()));
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
