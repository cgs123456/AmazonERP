package com.amz.controller;

import com.amz.client.FeedsClient;
import com.amz.client.OrdersClient;
import com.amz.client.SpApiOperationClient;
import com.amz.client.SpApiOperationSpec;
import com.amz.connector.LocalApiException;
import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialStore;
import com.amz.result.Result;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B 桶缺凭据 fail-closed 断言（2026-10-08 补齐，B 类「代码已对、仅缺测试」清单）。
 * <p>
 * 这批端点的**代码路径**早已 fail-closed——{@code SpApiGateway.resolveShop} 缺凭证时抛
 * {@link LocalApiException}{@code (CREDENTIAL_MISSING)}，controller 的
 * {@code ErrorSummary.toApiError} 也会保留该 code——但此前只有 finance 域有
 * 「缺凭证 → 400 + 点名」的断言（FinancialDataControllerErrorTextTest），本批端点全靠
 * 「应该没问题」。本测试把它们逐个钉住：client/凭据仓库抛缺凭证异常 →
 * 响应必须 400 + 带 CREDENTIAL_MISSING/MARKETPLACE_MISSING code，且不得返回成功载荷。
 * <p>
 * {@code syncOrders} 额外钉 MARKETPLACE_MISSING 分支：「没凭证」与「凭证缺站点」
 * 修复动作不同，必须可区分（与 SpapiController 源码口径一致）。
 */
@DisplayName("B 桶缺凭据 fail-closed：SP-API 端点必须点名，不得返回成功载荷")
class SpapiMissingCredentialFailClosedTest {

    private static final long SHOP = 1001L;
    private static final String MP = "ATVPDKIKX0DER";

    private static LocalApiException credentialMissing() {
        return LocalApiException.of(LocalApiException.CODE_CREDENTIAL_MISSING,
                "No credential found for shopId=" + SHOP);
    }

    private static LocalApiException marketplaceMissing() {
        return LocalApiException.of(LocalApiException.CODE_MARKETPLACE_MISSING,
                "marketplaceId missing for shopId=" + SHOP);
    }

    @AfterEach
    void clearUserContext() {
        com.amz.context.UserContext.clear();
    }

    // ---------------- POST /spapi/feeds/submit ----------------

    @Test
    @DisplayName("feeds submit：client 抛 CREDENTIAL_MISSING → 400 + code，不返回 feedId")
    void feedsSubmitSurfacesCredentialMissing() {
        // submit 的店铺归属校验（isShopAllowedByUserOrTrustedService）先于 client 调用，
        // 不给登录上下文会先被 FORBIDDEN 拦下，到不了缺凭证分支。
        com.amz.context.UserContext.setUserId(7);
        com.amz.context.UserContext.setRole("OPERATOR");
        com.amz.context.UserContext.setShops(java.util.List.of(SHOP));
        FeedsClient feedsClient = mock(FeedsClient.class);
        when(feedsClient.submitFeed(anyLong(), anyString(), anyString()))
                .thenThrow(credentialMissing());
        FeedsController controller = new FeedsController();
        ReflectionTestUtils.setField(controller, "feedsClient", feedsClient);

        FeedsController.FeedSubmitRequest request = new FeedsController.FeedSubmitRequest();
        request.setShopId(SHOP);
        request.setMarketplaceId(MP);
        request.setContent("op=POST /listings/xyz");

        Result<String> result = controller.submit(request);

        assertEquals(400, result.getCode());
        assertEquals(LocalApiException.CODE_CREDENTIAL_MISSING, result.getError().getCode(),
                "错误载荷必须带 CREDENTIAL_MISSING code（P0-52a 口径）");
        assertTrue(result.getMessage().contains("credential"), result.getMessage());
    }

    // ---------------- POST /spapi/sync/orders ----------------

    @Test
    @DisplayName("syncOrders：店铺无凭证 → 400 + CREDENTIAL_MISSING，ordersClient 不被调用")
    void syncOrdersSurfacesCredentialMissing() {
        ShopCredentialStore store = mock(ShopCredentialStore.class);
        when(store.get(SHOP)).thenReturn(null);
        OrdersClient ordersClient = mock(OrdersClient.class);
        SpapiController controller = new SpapiController();
        ReflectionTestUtils.setField(controller, "shopCredentialStore", store);
        ReflectionTestUtils.setField(controller, "ordersClient", ordersClient);

        Result<Integer> result = controller.syncOrders(SHOP);

        assertEquals(400, result.getCode());
        assertEquals(LocalApiException.CODE_CREDENTIAL_MISSING, result.getError().getCode());
        verify(ordersClient, never()).fetchOrders(anyLong(), anyString(), any(), any());
    }

    @Test
    @DisplayName("syncOrders：凭证存在但缺 marketplaceId → 400 + MARKETPLACE_MISSING（与缺凭证可区分）")
    void syncOrdersSurfacesMarketplaceMissing() {
        ShopCredentialStore store = mock(ShopCredentialStore.class);
        ShopCredential credential = new ShopCredential();
        credential.setShopId(SHOP);
        when(store.get(SHOP)).thenReturn(credential);
        OrdersClient ordersClient = mock(OrdersClient.class);
        SpapiController controller = new SpapiController();
        ReflectionTestUtils.setField(controller, "shopCredentialStore", store);
        ReflectionTestUtils.setField(controller, "ordersClient", ordersClient);

        Result<Integer> result = controller.syncOrders(SHOP);

        assertEquals(400, result.getCode());
        assertEquals(LocalApiException.CODE_MARKETPLACE_MISSING, result.getError().getCode());
        verify(ordersClient, never()).fetchOrders(anyLong(), anyString(), any(), any());
    }

    // ---------------- POST /spapi/messaging/send ----------------

    @Test
    @DisplayName("messaging send：client 抛 CREDENTIAL_MISSING → 400 + code，不返回成功载荷")
    void messagingSendSurfacesCredentialMissing() {
        com.amz.client.AmazonMessagingRealClient client =
                mock(com.amz.client.AmazonMessagingRealClient.class);
        when(client.sendMessage(anyLong(), anyString(), anyString(), any(), any()))
                .thenThrow(credentialMissing());
        MessagingController controller = new MessagingController();
        ReflectionTestUtils.setField(controller, "client", client);

        Result<Map<String, Object>> result = controller.send(
                SHOP, "111-0000000-0000000", MP, "INVOICE", Map.of("text", "hi"));

        assertEquals(400, result.getCode());
        assertEquals(LocalApiException.CODE_CREDENTIAL_MISSING, result.getError().getCode());
    }

    // ---------------- POST /spapi/uploads ----------------

    @Test
    @DisplayName("uploads：client 抛 CREDENTIAL_MISSING → 400 + code，不返回 uploadId")
    void uploadsSurfacesCredentialMissing() {
        com.amz.client.AmazonUploadsRealClient client =
                mock(com.amz.client.AmazonUploadsRealClient.class);
        when(client.createUploadDestinationAndUpload(anyLong(), anyString(), anyString(),
                anyString(), any())).thenThrow(credentialMissing());
        UploadsController controller = new UploadsController();
        ReflectionTestUtils.setField(controller, "client", client);
        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.json", "application/json", "{}".getBytes());

        Result<String> result = controller.upload(SHOP, MP, "DOCUMENT", null, file);

        assertEquals(400, result.getCode());
        assertEquals(LocalApiException.CODE_CREDENTIAL_MISSING, result.getError().getCode());
    }

    // ---------------- POST /spapi/operations/{operationId} ----------------

    @Test
    @DisplayName("operations：client 抛 CREDENTIAL_MISSING → 400 + code，不返回成功载荷")
    void operationsSurfacesCredentialMissing() {
        SpApiOperationClient operationClient = mock(SpApiOperationClient.class);
        when(operationClient.execute(anyLong(), anyString(), any(SpApiOperationSpec.class),
                any(), any(), any())).thenThrow(credentialMissing());
        SpApiOperationController controller = new SpApiOperationController(operationClient);
        com.amz.context.UserContext.setUserId(7);
        com.amz.context.UserContext.setRole("OPERATOR");
        com.amz.context.UserContext.setShops(java.util.List.of(SHOP));

        SpApiOperationController.OperationExecutionRequest req =
                new SpApiOperationController.OperationExecutionRequest();
        req.setShopId(SHOP);
        req.setMarketplaceId(MP);
        // operationId 必须是目录里真实注册的（"getOrders" 不在 SpApiOperationCatalog，
        // 会先以 INVALID_REQUEST 拒绝，到不了 client）。
        req.setQueryParameters(java.util.Map.of("notificationTypes", "TEST_NOTIFICATION"));

        Result<JsonObject> result = controller.execute("notifications.getSubscriptions", req);

        assertEquals(400, result.getCode());
        assertEquals(LocalApiException.CODE_CREDENTIAL_MISSING, result.getError().getCode());
    }
}
