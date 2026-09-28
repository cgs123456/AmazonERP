package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.client.SellersClient;
import com.amz.connector.SpApiCallException;
import com.amz.result.Result;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Sellers 只读接口契约")
class SellersControllerContractTest {

    private static final long SHOP_ID = 1001L;
    private static final String MARKETPLACE_ID = "ATVPDKIKX0DER";

    @Test
    @DisplayName("成功查询返回客户端校验后的 payload")
    void returnsValidatedPayload() {
        SellersClient client = mock(SellersClient.class);
        JsonObject payload = JsonParser.parseString("{\"payload\":[]}").getAsJsonObject();
        when(client.getMarketplaceParticipations(SHOP_ID, MARKETPLACE_ID)).thenReturn(payload);

        Result<JsonObject> result = controller(client)
                .getMarketplaceParticipations(SHOP_ID, MARKETPLACE_ID);

        assertEquals(200, result.getCode());
        assertSame(payload, result.getData());
        verify(client).getMarketplaceParticipations(SHOP_ID, MARKETPLACE_ID);
    }

    @Test
    @DisplayName("marketplaceId 为空时使用凭证登记的 marketplace，不误报参数错误")
    void blankMarketplaceUsesCredentialMarketplace() {
        SellersClient client = mock(SellersClient.class);
        JsonObject payload = JsonParser.parseString("{\"payload\":[]}").getAsJsonObject();
        when(client.getMarketplaceParticipations(SHOP_ID, null)).thenReturn(payload);

        Result<JsonObject> result = controller(client)
                .getMarketplaceParticipations(SHOP_ID, "  ");

        assertEquals(200, result.getCode());
        assertSame(payload, result.getData());
        verify(client).getMarketplaceParticipations(SHOP_ID, null);
    }

    @Test
    @DisplayName("shopId 为空时在调用上游前失败")
    void nullShopIdFailsBeforeClientCall() {
        SellersClient client = mock(SellersClient.class);

        Result<JsonObject> result = controller(client)
                .getMarketplaceParticipations(null, MARKETPLACE_ID);

        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().contains("shopId"), result.getMessage());
        verify(client, never()).getMarketplaceParticipations(any(), any());
    }

    @Test
    @DisplayName("上游 403 / 429 / 5xx 显式失败，不返回空成功")
    void upstreamFailureIsExplicit() {
        SellersClient client = mock(SellersClient.class);
        when(client.getMarketplaceParticipations(SHOP_ID, MARKETPLACE_ID))
                .thenThrow(SpApiCallException.statusFailure(
                        "sellers.getMarketplaceParticipations",
                        "/sellers/v1/marketplaceParticipations", 403));

        Result<JsonObject> result = controller(client)
                .getMarketplaceParticipations(SHOP_ID, MARKETPLACE_ID);

        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().contains("status=403"), result.getMessage());
        assertFalse(result.getMessage().contains("success"));
    }

    @Test
    @DisplayName("端点只读、带店铺隔离且允许三类已认证角色")
    void endpointIsReadOnlyAndShopScoped() throws Exception {
        Method method = SellersController.class.getMethod("getMarketplaceParticipations",
                Long.class, String.class);

        RequestMapping classMapping = SellersController.class.getAnnotation(RequestMapping.class);
        assertTrue(classMapping != null, "SellersController 必须有稳定路由前缀");
        assertEquals(List.of("/spapi/sellers"), List.of(classMapping.value()));
        GetMapping methodMapping = method.getAnnotation(GetMapping.class);
        assertTrue(methodMapping != null, "Sellers 查询必须是只读 GET 端点");
        assertEquals(List.of("/marketplace-participations"), List.of(methodMapping.value()));
        assertTrue(method.isAnnotationPresent(ShopScoped.class));
        RequireRole role = method.getAnnotation(RequireRole.class);
        assertTrue(role != null, "只读端点也必须显式限定已认证角色");
        assertEquals(List.of("VIEWER", "OPERATOR", "ADMIN"), List.of(role.value()));
        assertEquals(java.util.List.of("!mock"),
                java.util.List.of(SellersController.class.getAnnotation(Profile.class).value()));
    }

    private static SellersController controller(SellersClient client) {
        SellersController controller = new SellersController();
        ReflectionTestUtils.setField(controller, "client", client);
        return controller;
    }
}
