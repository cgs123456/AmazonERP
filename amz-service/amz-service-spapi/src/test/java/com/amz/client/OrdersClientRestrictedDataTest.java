package com.amz.client;

import com.amz.auth.AwsSigV4Signer;
import com.amz.auth.LwaTokenManager;
import com.amz.auth.SpApiUserAgent;
import com.amz.config.SpApiConfig;
import com.amz.connector.RestrictedDataTokenManager;
import com.amz.connector.RestrictedResource;
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
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Orders API：受限字段必须走 RDT")
class OrdersClientRestrictedDataTest {

    private static final long SHOP_ID = 1001L;
    private static final String MARKETPLACE_ID = "ATVPDKIKX0DER";
    private static final String RDT_TOKEN = "Atza|orders-restricted-token";
    private static final String ORDER_ID = "123-1234567-1234567";

    @Test
    @DisplayName("getOrders 使用 buyerInfo + shippingAddress 的 RDT，且不回退 LWA")
    void fetchOrdersWithRestrictedDataUsesExactScope() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(request ->
                new RecordingHttpTransport.Reply(200,
                        "{\"payload\":{\"Orders\":[{\"AmazonOrderId\":\"" + ORDER_ID + "\"}]}}"));
        LwaTokenManager lwa = mock(LwaTokenManager.class);
        RestrictedDataTokenManager rdt = mock(RestrictedDataTokenManager.class);
        List<RestrictedResource> expectedScope = List.of(
                new RestrictedResource("GET", "/orders/v0/orders",
                        List.of("buyerInfo", "shippingAddress")));
        when(rdt.getToken(SHOP_ID, MARKETPLACE_ID, expectedScope)).thenReturn(RDT_TOKEN);

        List<JsonObject> orders = client(transport, lwa, rdt).fetchOrdersWithRestrictedData(
                SHOP_ID, MARKETPLACE_ID, Instant.parse("2026-09-01T00:00:00Z"), List.of("Unshipped"));

        assertEquals(1, orders.size());
        assertEquals(ORDER_ID, orders.get(0).get("AmazonOrderId").getAsString());
        verify(rdt).getToken(SHOP_ID, MARKETPLACE_ID, expectedScope);
        verify(lwa, never()).getToken(any());
        assertEquals(1, transport.requestCount());
        assertEquals(RDT_TOKEN, header(transport.requests().get(0), "x-amz-access-token"));
    }

    @Test
    @DisplayName("getOrderItems 使用订单号精确路径的 buyerInfo RDT")
    void fetchOrderItemsWithRestrictedDataUsesExactOrderPath() {
        RecordingHttpTransport transport = RecordingHttpTransport.of(request ->
                new RecordingHttpTransport.Reply(200,
                        "{\"payload\":{\"OrderItems\":[{\"SellerSku\":\"SKU-1\"}]}}"));
        LwaTokenManager lwa = mock(LwaTokenManager.class);
        RestrictedDataTokenManager rdt = mock(RestrictedDataTokenManager.class);
        String exactPath = "/orders/v0/orders/" + ORDER_ID + "/orderItems";
        List<RestrictedResource> expectedScope = List.of(
                new RestrictedResource("GET", exactPath, List.of("buyerInfo")));
        when(rdt.getToken(SHOP_ID, MARKETPLACE_ID, expectedScope)).thenReturn(RDT_TOKEN);

        List<JsonObject> items = client(transport, lwa, rdt).fetchOrderItemsWithRestrictedData(
                SHOP_ID, MARKETPLACE_ID, ORDER_ID);

        assertEquals(1, items.size());
        assertEquals("SKU-1", items.get(0).get("SellerSku").getAsString());
        verify(rdt).getToken(SHOP_ID, MARKETPLACE_ID, expectedScope);
        verify(lwa, never()).getToken(any());
        assertEquals(1, transport.requestCount());
        assertEquals(RDT_TOKEN, header(transport.requests().get(0), "x-amz-access-token"));
        assertFalse(transport.requests().get(0).uri().getPath().contains("{orderId}"));
    }

    private static OrdersClient client(RecordingHttpTransport transport,
                                       LwaTokenManager lwa,
                                       RestrictedDataTokenManager rdt) {
        SpApiConfig config = new SpApiConfig();
        config.setAppName("AmazonERP");
        config.setAppVersion("1.0-SNAPSHOT");
        config.setLanguage("Java/17.0.20.1");
        SpApiEndpointResolver resolver = SpApiEndpointResolver.officialOnly();
        SpApiRequestFactory requestFactory = new SpApiRequestFactory(
                new AwsSigV4Signer(), new SpApiUserAgent(config), resolver);
        StubCredentialStore credentials = new StubCredentialStore().with(TestCredentials.northAmerica());
        SpiRateLimiter rateLimiter = new SpiRateLimiter();
        ObjectProvider<MeterRegistry> metrics = noMetrics();
        SpApiGateway gateway = new SpApiGateway(
                transport, lwa, credentials, rateLimiter, requestFactory, metrics, null, provider(rdt));
        return new OrdersClient(transport, lwa, credentials, rateLimiter, requestFactory, metrics, gateway);
    }

    private static String header(HttpRequest request, String name) {
        return request.headers().firstValue(name).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RestrictedDataTokenManager> provider(RestrictedDataTokenManager manager) {
        ObjectProvider<RestrictedDataTokenManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(manager);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> noMetrics() {
        return mock(ObjectProvider.class);
    }
}