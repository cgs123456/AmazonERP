package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.client.MockSpApiOperationClient;
import com.amz.client.SpApiOperationCatalog;
import com.amz.client.SpApiOperationClient;
import com.amz.client.SpApiOperationSpec;
import com.amz.connector.SpApiCallException;
import com.amz.context.UserContext;
import com.amz.result.Result;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("SP-API 统一 operation 控制器契约")
class SpApiOperationControllerTest {

    private static final long SHOP_ID = 1001L;
    private static final String MARKETPLACE_ID = "ATVPDKIKX0DER";

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("目录端点返回官方 operation 元数据并允许三类已认证角色")
    void listOperationsReturnsCatalogMetadata() throws Exception {
        SpApiOperationController controller = new SpApiOperationController(mock(SpApiOperationClient.class));

        Result<List<Map<String, Object>>> result = controller.listOperations();

        assertEquals(200, result.getCode());
        assertEquals(SpApiOperationCatalog.operations().size(), result.getData().size());
        Map<String, Object> first = result.getData().get(0);
        assertEquals("notifications.getSubscriptions", first.get("operationId"));
        assertEquals("notifications", first.get("family"));
        assertEquals("GET", first.get("method"));
        assertTrue(first.containsKey("requiredQueryParameters"));

        Method method = SpApiOperationController.class.getMethod("listOperations");
        GetMapping mapping = method.getAnnotation(GetMapping.class);
        assertTrue(mapping != null, "目录必须暴露为 GET");
        assertEquals(List.of(), List.of(mapping.value()));
        RequireRole role = method.getAnnotation(RequireRole.class);
        assertTrue(role != null, "目录端点也必须限定已认证角色");
        assertEquals(List.of("VIEWER", "OPERATOR", "ADMIN"), List.of(role.value()));
    }

    @Test
    @DisplayName("执行端点按目录解析 operation 并原样传递路径、查询和 JSON body")
    void executeForwardsCatalogOperation() {
        SpApiOperationClient client = mock(SpApiOperationClient.class);
        SpApiOperationSpec operation = SpApiOperationCatalog
                .find("listingsItems.getListingsItem").orElseThrow();
        JsonObject response = JsonParser.parseString("{\"sku\":\"S-1\"}").getAsJsonObject();
        when(client.execute(eq(SHOP_ID), eq(MARKETPLACE_ID), same(operation),
                eq(Map.of("sellerId", "SELLER", "sku", "S-1")),
                eq(Map.of("marketplaceIds", MARKETPLACE_ID)), any(JsonObject.class)))
                .thenReturn(response);
        UserContext.setRole("ADMIN");

        SpApiOperationController.OperationExecutionRequest request = request(
                Map.of("sellerId", "SELLER", "sku", "S-1"),
                Map.of("marketplaceIds", MARKETPLACE_ID),
                Map.of("productType", "PRODUCT"));
        Result<JsonObject> result = new SpApiOperationController(client)
                .execute("listingsItems.getListingsItem", request);

        assertEquals(200, result.getCode());
        assertEquals(response, result.getData());
        ArgumentCaptor<JsonObject> bodyCaptor = ArgumentCaptor.forClass(JsonObject.class);
        verify(client).execute(eq(SHOP_ID), eq(MARKETPLACE_ID), same(operation),
                eq(Map.of("sellerId", "SELLER", "sku", "S-1")),
                eq(Map.of("marketplaceIds", MARKETPLACE_ID)), bodyCaptor.capture());
        assertEquals("PRODUCT", bodyCaptor.getValue().get("productType").getAsString());
    }

    @Test
    @DisplayName("未知 operation 在出站前返回结构化失败")
    void unknownOperationFailsBeforeClient() {
        SpApiOperationClient client = mock(SpApiOperationClient.class);
        UserContext.setRole("ADMIN");

        Result<JsonObject> result = new SpApiOperationController(client)
                .execute("unknown.operation", request(Map.of(), Map.of(), null));

        assertEquals(400, result.getCode());
        assertTrue(result.getError() != null);
        assertEquals("INVALID_REQUEST", result.getError().getCode());
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("目录内 operation 的必填参数缺失时由统一客户端拒绝，不伪装成功")
    void malformedOperationFailsBeforeGateway() {
        UserContext.setRole("ADMIN");

        Result<JsonObject> result = new SpApiOperationController(new MockSpApiOperationClient())
                .execute("notifications.getSubscription", request(Map.of(), Map.of(), null));

        assertEquals(400, result.getCode());
        assertTrue(result.getError() != null);
        assertEquals("INVALID_REQUEST", result.getError().getCode());
    }

    @Test
    @DisplayName("上游异常结构化返回且凭证/令牌被脱敏")
    void upstreamFailureIsStructuredAndRedacted() {
        SpApiOperationClient client = mock(SpApiOperationClient.class);
        SpApiOperationSpec operation = SpApiOperationCatalog
                .find("notifications.getDestinations").orElseThrow();
        when(client.execute(eq(SHOP_ID), eq(MARKETPLACE_ID), same(operation), any(), any(), any()))
                .thenThrow(SpApiCallException.transportFailure(
                        operation.operationId(), operation.path(),
                        "Authorization: Bearer top-secret access_token=also-secret"));
        UserContext.setRole("ADMIN");

        Result<JsonObject> result = new SpApiOperationController(client)
                .execute(operation.operationId(), request(Map.of(), Map.of(), null));

        assertEquals(400, result.getCode());
        assertTrue(result.getError() != null);
        assertEquals("SPAPI_CALL_FAILED", result.getError().getCode());
        assertFalse(result.getMessage().contains("top-secret"), result.getMessage());
        assertFalse(result.getMessage().contains("also-secret"), result.getMessage());
    }

    @Test
    @DisplayName("成功响应中的 token、凭证和签名字段不向调用方回显")
    void successfulResponseIsRedacted() {
        SpApiOperationClient client = mock(SpApiOperationClient.class);
        SpApiOperationSpec operation = SpApiOperationCatalog
                .find("notifications.getDestinations").orElseThrow();
        JsonObject response = JsonParser.parseString("""
                {
                  "access_token": "top-secret",
                  "nested": {
                    "Authorization": "Bearer nested-secret",
                    "client_secret": "client-secret",
                    "nextToken": "page-2"
                  },
                  "message": "access_token=query-secret"
                }
                """).getAsJsonObject();
        when(client.execute(eq(SHOP_ID), eq(MARKETPLACE_ID), same(operation), any(), any(), any()))
                .thenReturn(response);
        UserContext.setRole("ADMIN");

        Result<JsonObject> result = new SpApiOperationController(client)
                .execute(operation.operationId(), request(Map.of(), Map.of(), null));

        assertEquals(200, result.getCode());
        String json = result.getData().toString();
        assertFalse(json.contains("top-secret"), json);
        assertFalse(json.contains("nested-secret"), json);
        assertFalse(json.contains("client-secret"), json);
        assertFalse(json.contains("query-secret"), json);
        assertEquals("page-2",
                result.getData().getAsJsonObject("nested").get("nextToken").getAsString());
    }

    @Test
    @DisplayName("请求体缺少店铺授权时 fail-closed，且不调用真实客户端")
    void missingShopScopeFailsClosed() {
        SpApiOperationClient client = mock(SpApiOperationClient.class);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of());

        Result<JsonObject> result = new SpApiOperationController(client)
                .execute("notifications.getDestinations", request(Map.of(), Map.of(), null));

        assertEquals(400, result.getCode());
        assertTrue(result.getError() != null);
        assertEquals("FORBIDDEN", result.getError().getCode());
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("执行端点要求 OPERATOR/ADMIN、店铺隔离，且 mock profile 不禁用")
    void executeEndpointGuardsAndProfile() throws Exception {
        RequestMapping classMapping = SpApiOperationController.class.getAnnotation(RequestMapping.class);
        assertTrue(classMapping != null);
        assertEquals(List.of("/spapi/operations"), List.of(classMapping.value()));
        assertNull(SpApiOperationController.class.getAnnotation(Profile.class),
                "统一控制器必须同时可用于 mock 与 real profile");

        Method execute = SpApiOperationController.class.getMethod(
                "execute", String.class, SpApiOperationController.OperationExecutionRequest.class);
        PostMapping mapping = execute.getAnnotation(PostMapping.class);
        assertTrue(mapping != null);
        assertEquals(List.of("/{operationId}"), List.of(mapping.value()));
        assertTrue(execute.isAnnotationPresent(ShopScoped.class));
        RequireRole role = execute.getAnnotation(RequireRole.class);
        assertTrue(role != null);
        assertEquals(List.of("OPERATOR", "ADMIN"), List.of(role.value()));
    }

    private static SpApiOperationController.OperationExecutionRequest request(
            Map<String, String> pathParameters,
            Map<String, String> queryParameters,
            Map<String, Object> body) {
        SpApiOperationController.OperationExecutionRequest request =
                new SpApiOperationController.OperationExecutionRequest();
        request.setShopId(SHOP_ID);
        request.setMarketplaceId(MARKETPLACE_ID);
        request.setPathParameters(pathParameters);
        request.setQueryParameters(queryParameters);
        request.setBody(body);
        return request;
    }
}