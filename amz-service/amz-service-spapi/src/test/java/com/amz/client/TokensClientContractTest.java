package com.amz.client;

import com.amz.auth.AwsSigV4Signer;
import com.amz.auth.LwaTokenManager;
import com.amz.auth.SpApiUserAgent;
import com.amz.config.SpApiConfig;
import com.amz.connector.RestrictedResource;
import com.amz.connector.SpApiCallException;
import com.amz.connector.SpApiEndpointResolver;
import com.amz.connector.SpApiRequestFactory;
import com.amz.ratelimit.SpiRateLimiter;
import com.amz.testsupport.RecordingHttpTransport;
import com.amz.testsupport.StubCredentialStore;
import com.amz.testsupport.TestCredentials;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.net.http.HttpRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@DisplayName("Tokens API restrictedDataToken 官方契约")
class TokensClientContractTest {

    private static final long SHOP_ID = 1001L;
    private static final String MARKETPLACE_ID = "ATVPDKIKX0DER";
    private static final String STUB_BASE = "http://127.0.0.1:19099";
    private static final String PATH = "/tokens/2021-03-01/restrictedDataToken";
    private static final String OPERATION_ID = "tokens.createRestrictedDataToken";
    private static final String LWA_TOKEN = "Atza|normal-lwa-token";
    private static final String RDT_TOKEN = "Atza|restricted-data-token";
    private static final Instant NOW = Instant.parse("2026-09-26T06:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String LWA_JSON = "{\"access_token\":\"" + LWA_TOKEN
            + "\",\"token_type\":\"bearer\",\"expires_in\":3600}";

    @Test
    @DisplayName("使用官方 POST 路径、LWA 鉴权并解析 token 与绝对过期时间")
    void usesOfficialContractWithLwaAuthentication() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(request -> {
            String path = request.uri().getPath();
            if ("/auth/o2/token".equals(path)) {
                return new RecordingHttpTransport.Reply(200, LWA_JSON);
            }
            if (PATH.equals(path)) {
                return new RecordingHttpTransport.Reply(200,
                        "{\"restrictedDataToken\":\"" + RDT_TOKEN + "\",\"expiresIn\":3600}");
            }
            throw new AssertionError("unexpected request: " + request.method() + " " + request.uri());
        });
        List<RestrictedResource> resources = List.of(
                new RestrictedResource("GET", "/orders/v0/orders", List.of("buyerInfo", "shippingAddress")),
                new RestrictedResource("GET", "/orders/v0/orders/123-1234567-1234567", List.of("buyerInfo")));

        TokensClient.RestrictedDataToken token = client(transport).requestToken(
                SHOP_ID, MARKETPLACE_ID, resources);

        assertEquals(RDT_TOKEN, token.token());
        assertEquals(NOW.plusSeconds(3600), token.expiresAt());
        assertFalse(token.toString().contains(RDT_TOKEN), token.toString());

        List<HttpRequest> requests = transport.requests();
        assertEquals(2, requests.size(), "LWA exchange followed by Tokens API call");
        assertEquals("/auth/o2/token", requests.get(0).uri().getPath());
        HttpRequest request = requests.get(1);
        assertEquals("POST", request.method());
        assertEquals(PATH, request.uri().getPath());
        assertEquals(LWA_TOKEN, request.headers().firstValue("x-amz-access-token").orElse(null));
        assertNotEquals(RDT_TOKEN, request.headers().firstValue("x-amz-access-token").orElse(null));
        assertTrue(request.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));

        JsonObject body = JsonParser.parseString(RecordingHttpTransport.bodyText(request)).getAsJsonObject();
        JsonArray actualResources = body.getAsJsonArray("restrictedResources");
        assertEquals(2, actualResources.size());
        assertEquals("GET", actualResources.get(0).getAsJsonObject().get("method").getAsString());
        assertEquals("/orders/v0/orders", actualResources.get(0).getAsJsonObject().get("path").getAsString());
        assertEquals("buyerInfo", actualResources.get(0).getAsJsonObject()
                .getAsJsonArray("dataElements").get(0).getAsString());
        assertEquals("shippingAddress", actualResources.get(0).getAsJsonObject()
                .getAsJsonArray("dataElements").get(1).getAsString());
    }

    @Test
    @DisplayName("资源数量为空或超过 50 时在出站前失败")
    void validatesResourceCountBeforeNetwork() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(request -> {
            throw new AssertionError("invalid resource set must not reach the network");
        });

        assertThrows(IllegalArgumentException.class,
                () -> client(transport).requestToken(SHOP_ID, MARKETPLACE_ID, List.of()));

        List<RestrictedResource> tooMany = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            tooMany.add(new RestrictedResource("GET", "/orders/v0/orders/" + i, List.of()));
        }
        assertThrows(IllegalArgumentException.class,
                () -> client(transport).requestToken(SHOP_ID, MARKETPLACE_ID, tooMany));
        assertEquals(0, transport.requestCount());
    }

    @Test
    @DisplayName("响应缺字段、空 token、非法 expiresIn 时显式失败且不回显 token")
    void invalidResponsesFailWithoutLeakingToken() {
        assertInvalidResponse("{\"expiresIn\":3600}", "restrictedDataToken");
        assertInvalidResponse("{\"restrictedDataToken\":\"   \",\"expiresIn\":3600}", "restrictedDataToken");
        assertInvalidResponse("{\"restrictedDataToken\":\"" + RDT_TOKEN + "\"}", "expiresIn");
        assertInvalidResponse("{\"restrictedDataToken\":\"" + RDT_TOKEN + "\",\"expiresIn\":0}", "expiresIn");
        assertInvalidResponse("{\"restrictedDataToken\":\"" + RDT_TOKEN + "\",\"expiresIn\":-1}", "expiresIn");
        assertInvalidResponse("{\"restrictedDataToken\":\"" + RDT_TOKEN + "\",\"expiresIn\":\"3600\"}", "expiresIn");
    }

    @Test
    @DisplayName("Tokens API 非 200 原样失败，不伪造 token")
    void nonSuccessStatusFails() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(request -> {
            if ("/auth/o2/token".equals(request.uri().getPath())) {
                return new RecordingHttpTransport.Reply(200, LWA_JSON);
            }
            return new RecordingHttpTransport.Reply(403,
                    "{\"errors\":[{\"code\":\"Unauthorized\",\"message\":\"denied\"}]}");
        });

        SpApiCallException error = assertThrows(SpApiCallException.class,
                () -> client(transport).requestToken(SHOP_ID, MARKETPLACE_ID,
                        List.of(new RestrictedResource("GET", "/orders/v0/orders", List.of()))));

        assertEquals(OPERATION_ID, error.getOperationId());
        assertEquals(PATH, error.getPath());
        assertEquals(403, error.getPlatformStatus());
    }

    private static void assertInvalidResponse(String response, String field) {
        RecordingHttpTransport transport = RecordingHttpTransport.of(request -> {
            if ("/auth/o2/token".equals(request.uri().getPath())) {
                return new RecordingHttpTransport.Reply(200, LWA_JSON);
            }
            return new RecordingHttpTransport.Reply(200, response);
        });

        SpApiCallException error = assertThrows(SpApiCallException.class,
                () -> client(transport).requestToken(SHOP_ID, MARKETPLACE_ID,
                        List.of(new RestrictedResource("GET", "/orders/v0/orders", List.of()))));

        assertEquals(0, error.getPlatformStatus(), "invalid payload is a local contract failure");
        assertTrue(error.getDiagnostic().contains(field), error.getDiagnostic());
        assertFalse(error.getMessage().contains(RDT_TOKEN), error.getMessage());
        assertFalse(error.getDiagnostic().contains(RDT_TOKEN), error.getDiagnostic());
    }

    private static TokensClient client(RecordingHttpTransport transport) {
        SpApiConfig config = new SpApiConfig();
        config.setBaseUrlOverride(STUB_BASE);
        config.setLwaEndpoint(STUB_BASE + "/auth/o2/token");
        SpApiEndpointResolver resolver = new SpApiEndpointResolver(config, false);
        SpApiRequestFactory requestFactory = new SpApiRequestFactory(
                new AwsSigV4Signer(), new SpApiUserAgent(config), resolver);
        SpApiGateway gateway = new SpApiGateway(
                transport,
                new LwaTokenManager(transport, config, resolver),
                new StubCredentialStore().with(TestCredentials.northAmerica()),
                mock(SpiRateLimiter.class),
                requestFactory,
                noMetrics());
        return new TokensClient(gateway, CLOCK);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> noMetrics() {
        return mock(ObjectProvider.class);
    }
}
