package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.client.OrdersClient;
import com.amz.connector.ConnectorRegistry;
import com.amz.connector.SpApiTokenSource;
import com.amz.context.UserContext;
import com.amz.credential.ShopCredentialStore;
import com.amz.outbox.SpApiCallOutboxService;
import com.amz.outbox.SpApiCallOutboxService.OutboxView;
import com.amz.outbox.SpApiOutboxReplayExecutor;
import com.amz.ratelimit.SpiRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Outbox API 边界契约：列表只返回安全字段、店铺过滤必须在查询层完成，
 * 非法筛选显式失败，人工重放必须校验店铺归属。
 */
@DisplayName("SP-API Outbox API：安全视图、店铺隔离与人工重放")
class ConnectorControllerOutboxContractTest {

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("列表把授权店铺下推到查询，响应不含查询串或请求/响应密文")
    void listPushesShopFilterDownAndOmitsSensitiveFields() throws Exception {
        SpApiCallOutboxService outbox = mock(SpApiCallOutboxService.class);
        when(outbox.list("FAILED", 50, List.of(1001L)))
                .thenReturn(List.of(view(7L, 1001L)));
        UserContext.setShops(List.of(1001L));

        MockMvc mockMvc = mockMvc(controller(outbox, mock(SpApiOutboxReplayExecutor.class)));
        mockMvc.perform(get("/spapi/connectors/outbox")
                        .param("status", "FAILED")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(7))
                .andExpect(jsonPath("$.data[0].shopId").value(1001))
                .andExpect(jsonPath("$.data[0].requestQuery").doesNotExist())
                .andExpect(jsonPath("$.data[0].requestBodyEncrypted").doesNotExist())
                .andExpect(jsonPath("$.data[0].responseBodyEncrypted").doesNotExist());

        verify(outbox).list("FAILED", 50, List.of(1001L));
    }

    @Test
    @DisplayName("非法状态筛选显式失败，不能以空列表伪装成功")
    void invalidStatusFailsExplicitly() throws Exception {
        SpApiCallOutboxService outbox = mock(SpApiCallOutboxService.class);
        when(outbox.list("BOGUS", 50, List.of(1001L)))
                .thenThrow(new IllegalArgumentException("unsupported Outbox status: BOGUS"));
        UserContext.setShops(List.of(1001L));

        MockMvc mockMvc = mockMvc(controller(outbox, mock(SpApiOutboxReplayExecutor.class)));
        mockMvc.perform(get("/spapi/connectors/outbox").param("status", "BOGUS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("无店铺授权的 VIEWER 查询列表必须 fail-closed，不能把 null 下推成全库查询")
    void listFailsClosedWhenViewerHasNoShopScope() throws Exception {
        SpApiCallOutboxService outbox = mock(SpApiCallOutboxService.class);
        UserContext.setRole("VIEWER");
        UserContext.setShops(List.of());

        MockMvc mockMvc = mockMvc(controller(outbox, mock(SpApiOutboxReplayExecutor.class)));
        mockMvc.perform(get("/spapi/connectors/outbox").param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        verifyNoInteractions(outbox);
    }

    @Test
    @DisplayName("ADMIN 可跨店查询；空 shops 只对 ADMIN 解释为全局管理范围")
    void adminWithoutShopScopeCanListAllShops() throws Exception {
        SpApiCallOutboxService outbox = mock(SpApiCallOutboxService.class);
        when(outbox.list("FAILED", 50, null)).thenReturn(List.of(view(7L, 2002L)));
        UserContext.setRole("ADMIN");
        UserContext.setShops(List.of());

        MockMvc mockMvc = mockMvc(controller(outbox, mock(SpApiOutboxReplayExecutor.class)));
        mockMvc.perform(get("/spapi/connectors/outbox").param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].shopId").value(2002));

        verify(outbox).list("FAILED", 50, null);
    }

    @Test
    @DisplayName("无店铺授权的 VIEWER 人工重放必须拒绝，且不得读取或执行目标记录")
    void replayFailsClosedWhenViewerHasNoShopScope() throws Exception {
        SpApiCallOutboxService outbox = mock(SpApiCallOutboxService.class);
        SpApiOutboxReplayExecutor executor = mock(SpApiOutboxReplayExecutor.class);
        UserContext.setRole("VIEWER");
        UserContext.setShops(List.of());

        MockMvc mockMvc = mockMvc(controller(outbox, executor));
        mockMvc.perform(post("/spapi/connectors/outbox/7/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        verifyNoInteractions(outbox, executor);
    }

    @Test
    @DisplayName("越权店铺的人工重放被拒绝，且不得触发执行器")
    void manualReplayRejectsForeignShop() throws Exception {
        SpApiCallOutboxService outbox = mock(SpApiCallOutboxService.class);
        SpApiOutboxReplayExecutor executor = mock(SpApiOutboxReplayExecutor.class);
        when(outbox.view(7L)).thenReturn(view(7L, 2002L));
        UserContext.setShops(List.of(1001L));

        MockMvc mockMvc = mockMvc(controller(outbox, executor));
        mockMvc.perform(post("/spapi/connectors/outbox/7/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        verify(executor, never()).replay(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("授权店铺的人工重放以 automatic=false 调用执行器")
    void manualReplayUsesOperatorMode() throws Exception {
        SpApiCallOutboxService outbox = mock(SpApiCallOutboxService.class);
        SpApiOutboxReplayExecutor executor = mock(SpApiOutboxReplayExecutor.class);
        when(outbox.view(7L)).thenReturn(view(7L, 1001L));
        when(executor.replay(7L, false))
                .thenReturn(SpApiOutboxReplayExecutor.ReplayResult.success("SUCCEEDED", null));
        UserContext.setShops(List.of(1001L));

        MockMvc mockMvc = mockMvc(controller(outbox, executor));
        mockMvc.perform(post("/spapi/connectors/outbox/7/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));

        verify(executor).replay(7L, false);
    }

    @Test
    @DisplayName("列表允许 VIEWER，重放端点禁止 VIEWER")
    void roleContract() throws Exception {
        Method list = ConnectorController.class.getMethod("outbox", String.class, int.class);
        Method replay = ConnectorController.class.getMethod("replayOutbox", Long.class);
        RequireRole listRole = list.getAnnotation(RequireRole.class);
        RequireRole replayRole = replay.getAnnotation(RequireRole.class);
        assertNotNull(listRole);
        assertNotNull(replayRole);
        assertTrue(List.of(listRole.value()).contains("VIEWER"));
        assertFalse(List.of(replayRole.value()).contains("VIEWER"));
    }

    private static MockMvc mockMvc(ConnectorController controller) {
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static ConnectorController controller(SpApiCallOutboxService outbox,
                                                  SpApiOutboxReplayExecutor executor) {
        return new ConnectorController(
                mock(ConnectorRegistry.class),
                mock(ShopCredentialStore.class),
                mock(OrdersClient.class),
                mock(SpiRateLimiter.class),
                outbox,
                executor);
    }

    private static OutboxView view(long id, long shopId) {
        return new OutboxView(id, shopId, "orders.getOrders", "GET", "/orders/v0/orders",
                "FAILED", 1, 4, null, "ATVPDKIKX0DER", "req-1",
                "HTTP_429", "throttled", null, null, null, List.of(200), null,
                SpApiTokenSource.LWA, null, null);
    }
}
