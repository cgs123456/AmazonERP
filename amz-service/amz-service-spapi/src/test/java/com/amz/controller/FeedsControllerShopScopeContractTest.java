package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.client.FeedsClient;
import com.amz.context.UserContext;
import com.amz.result.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("FeedsController 外部请求：空店铺授权必须 fail-closed")
class FeedsControllerShopScopeContractTest {

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("Feed 提交只允许 OPERATOR/ADMIN，VIEWER 不得写平台")
    void submitRequiresElevatedRole() throws Exception {
        Method method = FeedsController.class.getMethod("submit", FeedsController.FeedSubmitRequest.class);
        RequireRole requireRole = method.getAnnotation(RequireRole.class);

        assertNotNull(requireRole);
        List<String> roles = List.of(requireRole.value());
        assertTrue(roles.contains("OPERATOR"));
        assertTrue(roles.contains("ADMIN"));
        assertFalse(roles.contains("VIEWER"));
    }

    @Test
    @DisplayName("无店铺授权的 VIEWER 不能提交 Feed，且不得调用 FeedsClient")
    void submitFailsClosedWithoutShopScope() {
        FeedsClient feedsClient = mock(FeedsClient.class);
        FeedsController controller = controller(feedsClient);
        UserContext.setRole("VIEWER");
        UserContext.setShops(List.of());

        FeedsController.FeedSubmitRequest request = new FeedsController.FeedSubmitRequest();
        request.setShopId(1001L);
        request.setMarketplaceId("ATVPDKIKX0DER");
        request.setContent("{}");

        Result<String> result = controller.submit(request);

        assertEquals(400, result.getCode());
        assertNotNull(result.getError());
        assertEquals("FORBIDDEN", result.getError().getCode());
        verifyNoInteractions(feedsClient);
    }

    @Test
    @DisplayName("无店铺授权的 VIEWER 不能查询 Feed 状态，且不得调用 FeedsClient")
    void statusFailsClosedWithoutShopScope() {
        FeedsClient feedsClient = mock(FeedsClient.class);
        FeedsController controller = controller(feedsClient);
        UserContext.setRole("VIEWER");
        UserContext.setShops(List.of());

        Result<Map<String, Object>> result = controller.status(1001L, "feed-1");

        assertEquals(400, result.getCode());
        assertNotNull(result.getError());
        assertEquals("FORBIDDEN", result.getError().getCode());
        verifyNoInteractions(feedsClient);
    }

    @Test
    @DisplayName("无店铺授权的 VIEWER 不能查询 Feed processing report，且不得调用 FeedsClient")
    void resultFailsClosedWithoutShopScope() {
        FeedsClient feedsClient = mock(FeedsClient.class);
        FeedsController controller = controller(feedsClient);
        UserContext.setRole("VIEWER");
        UserContext.setShops(List.of());

        Result<Map<String, Object>> result = controller.result(1001L, "feed-1");

        assertEquals(400, result.getCode());
        assertNotNull(result.getError());
        assertEquals("FORBIDDEN", result.getError().getCode());
        verifyNoInteractions(feedsClient);
    }
    private static FeedsController controller(FeedsClient feedsClient) {
        FeedsController controller = new FeedsController();
        ReflectionTestUtils.setField(controller, "feedsClient", feedsClient);
        return controller;
    }
}


