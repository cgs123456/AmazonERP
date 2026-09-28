package com.amz.controller;

import com.amz.client.OrdersClient;
import com.amz.connector.ConnectorRegistry;
import com.amz.context.UserContext;
import com.amz.credential.ShopCredentialStore;
import com.amz.ratelimit.SpiRateLimiter;
import com.amz.result.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * SP-API Controller 外部请求边界的店铺授权契约。
 * <p>
 * {@link UserContext#isShopAllowed(Long)} 为兼容内部调用会在空 shops 时放行；
 * Controller 不能继承该语义，否则合法 JWT 但没有任何店铺授权的用户可越权触发外部请求。
 */
@DisplayName("SP-API 敏感端点：空店铺授权必须 fail-closed")
class ConnectorControllerShopScopeContractTest {

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("无店铺授权的 VIEWER 不能触发自检出网，且不得调用 OrdersClient")
    void selfTestFailsClosedWithoutShopScope() {
        OrdersClient orders = mock(OrdersClient.class);
        ConnectorController controller = controller(mock(ShopCredentialStore.class), orders);
        UserContext.setRole("VIEWER");
        UserContext.setShops(List.of());

        ConnectorController.SelfTestRequest body = new ConnectorController.SelfTestRequest();
        body.setShopId(1001L);

        Result<Map<String, Object>> result = controller.selfTest(ConnectorRegistry.SPAPI, body);

        assertEquals(400, result.getCode());
        assertNotNull(result.getError());
        assertEquals("FORBIDDEN", result.getError().getCode());
        verifyNoInteractions(orders);
    }

    private static ConnectorController controller(ShopCredentialStore store, OrdersClient orders) {
        return new ConnectorController(
                mock(ConnectorRegistry.class),
                store,
                orders,
                mock(SpiRateLimiter.class));
    }
}
