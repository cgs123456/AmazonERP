package com.amz.controller;

import com.amz.client.FeedsClient;
import com.amz.client.OrdersClient;
import com.amz.connector.SpApiCallException;
import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialStore;
import com.amz.context.UserContext;
import com.amz.result.Result;
import com.amz.scheduler.InventorySyncScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P0-52a 边界层验收：原先只回固定文案（{@code "sync failed"} / {@code "feed submit failed"}）的四处端点，
 * 必须把平台错误（HTTP status + {@code errors[].code}）带到响应里。
 * <p>
 * 为什么重要：接上真实 SP-API 后，这两个端点是运维手工排障的第一入口；
 * 只回固定文案时，运维只能捞服务日志（弱证据），且无法区分「凭证错 / 被限流 / 平台 5xx」。
 * <p>
 * 同时锁定向后兼容：原前缀（{@code "sync failed"} 等）保留，只在尾部追加诊断信息，
 * 避免前端或调用方失败文案匹配失效。
 * <p>
 * 证据类型 E1（自证，下游组件被桩件替换）。
 */
@DisplayName("P0-52a 边界层：固定文案端点不再吞掉平台错误")
class ControllerErrorTextContractTest {

    private static final String QUOTA_BODY =
            "{\"errors\":[{\"code\":\"QuotaExceeded\",\"message\":\"Request is throttled\"}]}";

    @BeforeEach
    void authenticateOperatorForShop() {
        UserContext.setUserId(1);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(1001L));
    }

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    private static SpApiCallException throttled(String operation) {
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(429);
        when(response.body()).thenReturn(QUOTA_BODY);
        when(response.headers()).thenReturn(HttpHeaders.of(
                Map.of("x-amzn-RequestId", List.of("req-1")), (name, value) -> true));
        return SpApiCallException.fromResponse(operation, "/orders/v0/orders", response);
    }

    private static void assertStructuredThrottle(Result<?> result) {
        assertNotNull(result.getError(), result.getMessage());
        assertEquals("SPAPI_CALL_FAILED", result.getError().getCode());
        assertEquals(429, result.getError().getPlatformStatus());
        assertEquals("QuotaExceeded", result.getError().getPlatformCode());
        assertEquals("Request is throttled", result.getError().getPlatformMessage());
        assertEquals("req-1", result.getError().getRequestId());
    }

    private static SpapiController spapiControllerWith(ShopCredentialStore store, OrdersClient ordersClient) {
        SpapiController controller = new SpapiController();
        ReflectionTestUtils.setField(controller, "shopCredentialStore", store);
        ReflectionTestUtils.setField(controller, "ordersClient", ordersClient);
        return controller;
    }

    @Test
    @DisplayName("订单手动同步失败：响应含 status=429 与 QuotaExceeded，前缀不变")
    void ordersSyncSurfacesPlatformCode() {
        ShopCredential credential = new ShopCredential();
        credential.setShopId(1001L);
        credential.setMarketplaceId("ATVPDKIKX0DER");
        ShopCredentialStore store = mock(ShopCredentialStore.class);
        when(store.get(1001L)).thenReturn(credential);
        OrdersClient ordersClient = mock(OrdersClient.class);
        SpApiCallException upstreamError = throttled("fetchOrders");
        when(ordersClient.fetchOrders(any(), any(), any(), any())).thenThrow(upstreamError);

        Result<Integer> result = spapiControllerWith(store, ordersClient).syncOrders(1001L);

        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().startsWith("sync failed"), result.getMessage());
        assertTrue(result.getMessage().contains("status=429"), result.getMessage());
        assertTrue(result.getMessage().contains("QuotaExceeded"), result.getMessage());
        assertStructuredThrottle(result);
    }

    @Test
    @DisplayName("库存手动同步失败：响应含 status=429 与 QuotaExceeded，前缀不变")
    void inventorySyncSurfacesPlatformCode() {
        InventorySyncScheduler scheduler = mock(InventorySyncScheduler.class);
        SpApiCallException upstreamError = throttled("syncShopInventory");
        when(scheduler.syncShopInventory(1001L)).thenThrow(upstreamError);
        InventoryController controller = new InventoryController();
        ReflectionTestUtils.setField(controller, "inventorySyncScheduler", scheduler);

        Result<Integer> result = controller.sync(1001L);

        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().startsWith("sync failed"), result.getMessage());
        assertTrue(result.getMessage().contains("status=429"), result.getMessage());
        assertTrue(result.getMessage().contains("QuotaExceeded"), result.getMessage());
        assertStructuredThrottle(result);
    }

    @Test
    @DisplayName("Feed 提交失败：响应含平台错误码，前缀不变")
    void feedSubmitSurfacesPlatformCode() {
        FeedsClient feedsClient = mock(FeedsClient.class);
        SpApiCallException upstreamError = throttled("createFeedDocument");
        when(feedsClient.submitFeed(any(), any(), any())).thenThrow(upstreamError);
        FeedsController controller = new FeedsController();
        ReflectionTestUtils.setField(controller, "feedsClient", feedsClient);

        FeedsController.FeedSubmitRequest request = new FeedsController.FeedSubmitRequest();
        request.setShopId(1001L);
        request.setMarketplaceId("ATVPDKIKX0DER");
        request.setContent("{\"header\":{}}");

        Result<String> result = controller.submit(request);

        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().startsWith("feed submit failed"), result.getMessage());
        assertTrue(result.getMessage().contains("QuotaExceeded"), result.getMessage());
        assertStructuredThrottle(result);
    }

    @Test
    @DisplayName("Feed 状态查询失败：响应含平台错误码，前缀不变")
    void feedStatusSurfacesPlatformCode() {
        FeedsClient feedsClient = mock(FeedsClient.class);
        SpApiCallException upstreamError = throttled("getFeed");
        when(feedsClient.getFeedStatus(any(), any())).thenThrow(upstreamError);
        FeedsController controller = new FeedsController();
        ReflectionTestUtils.setField(controller, "feedsClient", feedsClient);

        Result<Map<String, Object>> result = controller.status(1001L, "feed-1");

        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().startsWith("feed status failed"), result.getMessage());
        assertTrue(result.getMessage().contains("QuotaExceeded"), result.getMessage());
        assertStructuredThrottle(result);
    }

    @Test
    @DisplayName("上游把预签名 URL 写进异常文本（URI 构造失败形态）：边界层仍掩掉签名")
    void presignedUrlInUpstreamMessageIsRedacted() {
        FeedsClient feedsClient = mock(FeedsClient.class);
        when(feedsClient.submitFeed(any(), any(), any())).thenThrow(new IllegalArgumentException(
                "Illegal character in query at index 120: https://bucket.s3.amazonaws.com/feed/upload"
                        + "?X-Amz-Credential=AKIAEXAMPLEKEY%2F20260924%2Fus-east-1%2Fs3%2Faws4_request"
                        + "&X-Amz-Signature=deadbeefdeadbeefdeadbeefdeadbeef"));
        FeedsController controller = new FeedsController();
        ReflectionTestUtils.setField(controller, "feedsClient", feedsClient);

        FeedsController.FeedSubmitRequest request = new FeedsController.FeedSubmitRequest();
        request.setShopId(1001L);
        request.setMarketplaceId("ATVPDKIKX0DER");
        request.setContent("{\"header\":{}}");

        Result<String> result = controller.submit(request);

        String message = result.getMessage();
        assertTrue(message.contains("/feed/upload"), message);
        assertFalse(message.contains("deadbeef"), message);
        assertFalse(message.contains("AKIAEXAMPLEKEY"), message);
        assertEquals("INVALID_REQUEST", result.getError().getCode());
    }
}
