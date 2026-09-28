package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.client.OrdersClient;
import com.amz.connector.ConnectorRegistry;
import com.amz.credential.ShopCredentialStore;
import com.amz.ratelimit.SpiRateLimiter;
import com.amz.result.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("连接器限流观测端点：只读、受角色守卫、返回结构化观测")
class ConnectorControllerRateLimitContractTest {

    @Test
    @DisplayName("MockMvc：/rate-limits 精确路由优先于 /{code} 且可完成 JSON 序列化")
    void rateLimitsRouteResolvesBeforePathVariable() throws Exception {
        SpiRateLimiter limiter = new SpiRateLimiter();
        limiter.updateLimit(1001L, "reports.createReport", "0.005");

        ConnectorController controller = new ConnectorController(
                mock(ConnectorRegistry.class),
                mock(ShopCredentialStore.class),
                mock(OrdersClient.class),
                limiter);

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        mockMvc.perform(get("/spapi/connectors/rate-limits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].operationId").value("reports.createReport"))
                .andExpect(jsonPath("$.data[0].headerValue").value("0.005"));
    }

    @Test
    @DisplayName("GET /spapi/connectors/rate-limits 返回 SpiRateLimiter 的结构化观测")
    void rateLimitsEndpointReturnsStructuredObservations() throws Exception {
        SpiRateLimiter limiter = new SpiRateLimiter();
        limiter.updateLimit(1001L, "reports.createReport", "0.005");

        ConnectorController controller = new ConnectorController(
                mock(ConnectorRegistry.class),
                mock(ShopCredentialStore.class),
                mock(OrdersClient.class),
                limiter);

        Method method = ConnectorController.class.getMethod("rateLimits");
        GetMapping mapping = method.getAnnotation(GetMapping.class);
        assertNotNull(mapping, "必须注册 GET 路由");
        assertArrayEquals(new String[]{"/rate-limits"}, mapping.value());

        RequireRole role = method.getAnnotation(RequireRole.class);
        assertNotNull(role, "观测端点会暴露店铺限流状态，必须受角色守卫");
        List<String> roles = List.of(role.value());
        assertTrue(roles.contains("VIEWER") && roles.contains("OPERATOR") && roles.contains("ADMIN"),
                "只读观测端点应允许 VIEWER/OPERATOR/ADMIN，实际=" + roles);

        Result<List<SpiRateLimiter.RateLimitObservation>> result = controller.rateLimits();
        assertEquals(200, result.getCode());
        assertEquals(1, result.getData().size());
        assertEquals("0.005", result.getData().get(0).headerValue());
    }
}