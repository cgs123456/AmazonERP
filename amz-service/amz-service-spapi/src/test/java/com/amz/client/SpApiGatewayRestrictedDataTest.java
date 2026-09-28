package com.amz.client;

import com.amz.auth.AwsSigV4Signer;
import com.amz.auth.LwaTokenManager;
import com.amz.auth.SpApiUserAgent;
import com.amz.config.SpApiConfig;
import com.amz.credential.ShopCredential;
import com.amz.connector.LocalApiException;
import com.amz.connector.RestrictedDataTokenManager;
import com.amz.connector.RestrictedResource;
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

import java.net.http.HttpRequest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("SP-API Gateway：LWA/RDT 显式分流与失败关闭")
class SpApiGatewayRestrictedDataTest {

    private static final long SHOP_ID = 1001L;
    private static final String MARKETPLACE_ID = "ATVPDKIKX0DER";
    private static final String LWA_TOKEN = "Atza|lwa-token";
    private static final String RDT_TOKEN = "Atza|restricted-token";
    private static final String GRANTLESS_TOKEN = "Atza|grantless-token";
    private static final String STUB_BASE = "http://127.0.0.1:19099";
    private static final String TOKEN_JSON = "{\"access_token\":\"" + LWA_TOKEN
            + "\",\"token_type\":\"bearer\",\"expires_in\":3600}";
    private static final String RDT_JSON = "{\"restrictedDataToken\":\"" + RDT_TOKEN
            + "\",\"expiresIn\":3600}";
    private static final List<RestrictedResource> RESOURCES = List.of(
            new RestrictedResource("GET", "/orders/v0/orders", List.of("buyerInfo")));

    @Test
    @DisplayName("默认调用保持 LWA，不接受隐式 RDT")
    void defaultCallUsesLwa() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(
                request -> new RecordingHttpTransport.Reply(200, "{}"));
        LwaTokenManager lwa = mock(LwaTokenManager.class);
        when(lwa.getToken(any())).thenReturn(LWA_TOKEN);
        RestrictedDataTokenManager rdt = mock(RestrictedDataTokenManager.class);
        SpApiGateway gateway = gateway(transport, lwa, rdt);

        gateway.callJson("GET", gateway.resolveShop(SHOP_ID, MARKETPLACE_ID),
                "orders.getOrders", "/orders/v0/orders", null, null);

        assertEquals(LWA_TOKEN, header(transport.requests().get(0), "x-amz-access-token"));
        verify(rdt, never()).getToken(any(), any(), any());
    }

    @Test
    @DisplayName("受限调用显式取 RDT，业务请求头不得回落 LWA")
    void restrictedCallUsesExplicitRdt() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(
                request -> new RecordingHttpTransport.Reply(200, "{}"));
        LwaTokenManager lwa = mock(LwaTokenManager.class);
        when(lwa.getToken(any())).thenReturn(LWA_TOKEN);
        RestrictedDataTokenManager rdt = mock(RestrictedDataTokenManager.class);
        when(rdt.getToken(SHOP_ID, MARKETPLACE_ID, RESOURCES)).thenReturn(RDT_TOKEN);
        SpApiGateway gateway = gateway(transport, lwa, rdt);

        gateway.callJsonWithRestrictedData("GET", gateway.resolveShop(SHOP_ID, MARKETPLACE_ID),
                "orders.getOrders", "/orders/v0/orders", null, null,
                RESOURCES, null, 200);

        assertEquals(RDT_TOKEN, header(transport.requests().get(0), "x-amz-access-token"));
        verify(lwa, never()).getToken(any());
    }

    @Test
    @DisplayName("RDT 获取失败时业务请求数为 0，不允许静默降级")
    void rdtFailureDoesNotSendBusinessRequest() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(
                request -> new RecordingHttpTransport.Reply(200, "{}"));
        LwaTokenManager lwa = mock(LwaTokenManager.class);
        when(lwa.getToken(any())).thenReturn(LWA_TOKEN);
        RestrictedDataTokenManager rdt = mock(RestrictedDataTokenManager.class);
        when(rdt.getToken(SHOP_ID, MARKETPLACE_ID, RESOURCES)).thenThrow(
                LocalApiException.of("RESTRICTED_DATA_DISABLED", "disabled for test"));
        SpApiGateway gateway = gateway(transport, lwa, rdt);

        assertThrows(LocalApiException.class, () -> gateway.callJsonWithRestrictedData(
                "GET", gateway.resolveShop(SHOP_ID, MARKETPLACE_ID),
                "orders.getOrders", "/orders/v0/orders", null, null,
                RESOURCES, null, 200));

        assertTrue(transport.requests().isEmpty(), "RDT 获取失败时不得发送业务请求");
        verify(lwa, never()).getToken(any());
    }

    @Test
    @DisplayName("RDT 401/403 只失效对应资源缓存，不清 LWA，也不回退")
    void restrictedUnauthorizedInvalidatesOnlyRdt() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(
                request -> new RecordingHttpTransport.Reply(403, "{\"errors\":[]}"));
        LwaTokenManager lwa = mock(LwaTokenManager.class);
        when(lwa.getToken(any())).thenReturn(LWA_TOKEN);
        RestrictedDataTokenManager rdt = mock(RestrictedDataTokenManager.class);
        when(rdt.getToken(SHOP_ID, MARKETPLACE_ID, RESOURCES)).thenReturn(RDT_TOKEN);
        SpApiGateway gateway = gateway(transport, lwa, rdt);

        assertThrows(RuntimeException.class, () -> gateway.callJsonWithRestrictedData(
                "GET", gateway.resolveShop(SHOP_ID, MARKETPLACE_ID),
                "orders.getOrders", "/orders/v0/orders", null, null,
                RESOURCES, null, 200));

        verify(rdt).invalidate(SHOP_ID, MARKETPLACE_ID, RESOURCES);
        verify(lwa, never()).invalidate(any(ShopCredential.class));
    }

    @Test
    @DisplayName("普通 LWA 401/403 只失效 LWA，不触碰 RDT")
    void lwaUnauthorizedInvalidatesOnlyLwa() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(
                request -> new RecordingHttpTransport.Reply(401, "{\"errors\":[]}"));
        LwaTokenManager lwa = mock(LwaTokenManager.class);
        when(lwa.getToken(any())).thenReturn(LWA_TOKEN);
        RestrictedDataTokenManager rdt = mock(RestrictedDataTokenManager.class);
        SpApiGateway gateway = gateway(transport, lwa, rdt);

        assertThrows(RuntimeException.class, () -> gateway.callJson(
                "GET", gateway.resolveShop(SHOP_ID, MARKETPLACE_ID),
                "orders.getOrders", "/orders/v0/orders", null, null));

        verify(lwa).invalidate(any(ShopCredential.class));
        verify(rdt, never()).invalidate(any(), any(), any());
    }

    @Test
    @DisplayName("Tokens API 自身始终使用普通 LWA token，避免 RDT 递归")
    void tokensApiAlwaysUsesLwa() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(request -> {
            if ("/auth/o2/token".equals(request.uri().getPath())) {
                return new RecordingHttpTransport.Reply(200, TOKEN_JSON);
            }
            return new RecordingHttpTransport.Reply(200, RDT_JSON);
        });
        SpApiConfig config = config();
        SpApiEndpointResolver resolver = new SpApiEndpointResolver(config, false);
        StubCredentialStore credentials = new StubCredentialStore().with(TestCredentials.northAmerica());
        RestrictedDataTokenManager rdt = mock(RestrictedDataTokenManager.class);
        SpApiGateway gateway = new SpApiGateway(
                transport,
                new LwaTokenManager(transport, config, resolver),
                credentials,
                new SpiRateLimiter(),
                requestFactory(config, resolver),
                noMetrics(),
                null,
                provider(rdt));

        new TokensClient(gateway).requestToken(SHOP_ID, MARKETPLACE_ID, RESOURCES);

        HttpRequest tokenRequest = transport.requests().stream()
                .filter(request -> TokensClient.PATH_RESTRICTED_DATA_TOKEN.equals(request.uri().getPath()))
                .findFirst().orElseThrow();
        assertEquals(LWA_TOKEN, header(tokenRequest, "x-amz-access-token"));
        verify(rdt, never()).getToken(any(), any(), any());
    }

    @Test
    @DisplayName("Notifications grantless 调用显式取 client_credentials token，不回落到卖家 LWA")
    void grantlessCallUsesClientCredentialsToken() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(
                request -> new RecordingHttpTransport.Reply(200, "{}"));
        LwaTokenManager lwa = mock(LwaTokenManager.class);
        when(lwa.getGrantlessToken(any(), any(), any())).thenReturn(GRANTLESS_TOKEN);
        RestrictedDataTokenManager rdt = mock(RestrictedDataTokenManager.class);
        SpApiGateway gateway = gateway(transport, lwa, rdt);

        gateway.callJsonWithStatusUsingGrantlessLwa(
                "GET", gateway.resolveShop(SHOP_ID, MARKETPLACE_ID),
                "notifications.getDestinations", "/notifications/v1/destinations",
                null, null, 200);

        assertEquals(GRANTLESS_TOKEN, header(transport.requests().get(0), "x-amz-access-token"));
        verify(lwa, never()).getToken(any());
        verify(rdt, never()).getToken(any(), any(), any());
    }

    @Test
    @DisplayName("grantless token 获取失败时业务请求数为 0，不允许静默降级")
    void grantlessFailureDoesNotSendBusinessRequest() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(
                request -> new RecordingHttpTransport.Reply(200, "{}"));
        LwaTokenManager lwa = mock(LwaTokenManager.class);
        when(lwa.getGrantlessToken(any(), any(), any())).thenThrow(
                LocalApiException.of("LWA_AUTH_FAILED", "grantless denied for test"));
        SpApiGateway gateway = gateway(transport, lwa, mock(RestrictedDataTokenManager.class));

        assertThrows(LocalApiException.class, () -> gateway.callJsonWithStatusUsingGrantlessLwa(
                "GET", gateway.resolveShop(SHOP_ID, MARKETPLACE_ID),
                "notifications.getDestinations", "/notifications/v1/destinations",
                null, null, 200));

        assertTrue(transport.requests().isEmpty(), "grantless token 获取失败时不得发送业务请求");
    }

    @Test
    @DisplayName("grantless 401/403 只失效 grantless token，不触碰卖家 LWA 或 RDT")
    void grantlessUnauthorizedInvalidatesOnlyGrantlessToken() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(
                request -> new RecordingHttpTransport.Reply(401, "{\"errors\":[]}"));
        LwaTokenManager lwa = mock(LwaTokenManager.class);
        when(lwa.getGrantlessToken(any(), any(), any())).thenReturn(GRANTLESS_TOKEN);
        RestrictedDataTokenManager rdt = mock(RestrictedDataTokenManager.class);
        SpApiGateway gateway = gateway(transport, lwa, rdt);

        assertThrows(RuntimeException.class, () -> gateway.callJsonWithStatusUsingGrantlessLwa(
                "GET", gateway.resolveShop(SHOP_ID, MARKETPLACE_ID),
                "notifications.getDestinations", "/notifications/v1/destinations",
                null, null, 200));

        verify(lwa).invalidateGrantless(any(), any(), any());
        verify(lwa, never()).invalidate(any(ShopCredential.class));
        verify(rdt, never()).invalidate(any(), any(), any());
    }
    @Test
    @DisplayName("Notifications 中需要卖家授权的操作不得走 grantless LWA")
    void grantlessEntryPointRejectsSellerAuthorizedNotificationsOperations() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(
                request -> new RecordingHttpTransport.Reply(200, "{}"));
        LwaTokenManager lwa = mock(LwaTokenManager.class);
        when(lwa.getGrantlessToken(any(), any(), any())).thenReturn(GRANTLESS_TOKEN);
        SpApiGateway gateway = gateway(transport, lwa, mock(RestrictedDataTokenManager.class));

        for (String operationId : List.of(
                "notifications.getSubscriptions",
                "notifications.getSubscription",
                "notifications.createSubscription")) {
            assertThrows(LocalApiException.class, () -> gateway.callJsonWithStatusUsingGrantlessLwa(
                    "GET", gateway.resolveShop(SHOP_ID, MARKETPLACE_ID),
                    operationId, "/notifications/v1/subscriptions", null, null, 200),
                    operationId + " 不在官方 grantless allowlist");
        }

        assertTrue(transport.requests().isEmpty(), "被拒绝的 grantless 操作不得出站");
        verify(lwa, never()).getGrantlessToken(any(), any(), any());
    }
    private static SpApiGateway gateway(RecordingHttpTransport transport,
                                        LwaTokenManager lwa,
                                        RestrictedDataTokenManager rdt) {
        SpApiConfig config = config();
        SpApiEndpointResolver resolver = new SpApiEndpointResolver(config, false);
        return new SpApiGateway(
                transport,
                lwa,
                new StubCredentialStore().with(TestCredentials.northAmerica()),
                new SpiRateLimiter(),
                requestFactory(config, resolver),
                noMetrics(),
                null,
                provider(rdt));
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

    private static SpApiRequestFactory requestFactory(SpApiConfig config,
                                                       SpApiEndpointResolver resolver) {
        return new SpApiRequestFactory(new AwsSigV4Signer(), new SpApiUserAgent(config), resolver);
    }

    private static String header(HttpRequest request, String name) {
        return request.headers().firstValue(name).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RestrictedDataTokenManager> provider(
            RestrictedDataTokenManager manager) {
        ObjectProvider<RestrictedDataTokenManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(manager);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> noMetrics() {
        return mock(ObjectProvider.class);
    }
}
