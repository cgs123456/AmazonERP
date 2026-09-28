package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.client.AmazonMessagingRealClient;
import com.amz.client.MessagingAction;
import com.amz.result.Result;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Messaging 边界：官方动作转发、参数校验、失败不伪装成功")
class MessagingControllerContractTest {

    private static final long SHOP_ID = 1001L;
    private static final String ORDER_ID = "123-1234567-1234567";
    private static final String MARKETPLACE_ID = "ATVPDKIKX0DER";

    @Test
    @DisplayName("查询动作：返回官方 JSON 转换后的 Map")
    void actionsAreReturned() {
        AmazonMessagingRealClient client = mock(AmazonMessagingRealClient.class);
        JsonObject response = new JsonObject();
        response.addProperty("marker", "official");
        when(client.getMessagingActionsForOrder(SHOP_ID, ORDER_ID, MARKETPLACE_ID))
                .thenReturn(response);

        Result<Map<String, Object>> result = controller(client)
                .getActions(SHOP_ID, ORDER_ID, MARKETPLACE_ID);

        assertEquals(200, result.getCode());
        assertEquals("official", result.getData().get("marker"));
        verify(client).getMessagingActionsForOrder(SHOP_ID, ORDER_ID, MARKETPLACE_ID);
    }

    @Test
    @DisplayName("发送动作：只接受官方枚举并原样传递请求体")
    void sendUsesOfficialActionAndBody() {
        AmazonMessagingRealClient client = mock(AmazonMessagingRealClient.class);
        when(client.sendMessage(eq(SHOP_ID), eq(ORDER_ID), eq(MARKETPLACE_ID),
                eq(MessagingAction.INVOICE), any())).thenReturn(new JsonObject());
        Map<String, Object> body = Map.of("attachments", List.of(Map.of(
                "fileName", "invoice.pdf", "uploadDestinationId", "upload-1")));

        Result<Map<String, Object>> result = controller(client)
                .send(SHOP_ID, ORDER_ID, MARKETPLACE_ID, "INVOICE", body);

        assertEquals(200, result.getCode());
        verify(client).sendMessage(eq(SHOP_ID), eq(ORDER_ID), eq(MARKETPLACE_ID),
                eq(MessagingAction.INVOICE), any(JsonObject.class));
    }

    @Test
    @DisplayName("发送动作缺 body 时在客户端调用前失败")
    void nullBodyFailsBeforeClientCall() {
        AmazonMessagingRealClient client = mock(AmazonMessagingRealClient.class);

        Result<Map<String, Object>> result = controller(client)
                .send(SHOP_ID, ORDER_ID, MARKETPLACE_ID, "INVOICE", null);

        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().contains("body"), result.getMessage());
        verify(client, never()).sendMessage(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("非法动作在调用客户端前失败")
    void unknownActionFailsBeforeClientCall() {
        AmazonMessagingRealClient client = mock(AmazonMessagingRealClient.class);

        Result<Map<String, Object>> result = controller(client)
                .send(SHOP_ID, ORDER_ID, MARKETPLACE_ID, "reply", Map.of("text", "hello"));

        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().contains("action"), result.getMessage());
        verify(client, never()).sendMessage(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("mock profile 不得注册真实 Messaging 控制器，避免缺 Bean 启动失败")
    void controllerIsExcludedFromMockProfile() {
        Profile profile = MessagingController.class.getAnnotation(Profile.class);
        assertTrue(profile != null, "MessagingController 必须声明 profile");
        assertEquals(List.of("!mock"), List.of(profile.value()));
    }

    @Test
    @DisplayName("发送消息是写操作，必须同时具备店铺隔离与写角色守卫")
    void sendEndpointIsGuarded() throws Exception {
        Method method = MessagingController.class.getMethod("send", Long.class, String.class,
                String.class, String.class, Map.class);

        assertTrue(method.isAnnotationPresent(ShopScoped.class));
        RequireRole role = method.getAnnotation(RequireRole.class);
        assertTrue(role != null, "发送消息必须声明角色");
        List<String> roles = List.of(role.value());
        assertTrue(roles.contains("OPERATOR") && roles.contains("ADMIN"), roles.toString());
        assertFalse(roles.contains("VIEWER"), roles.toString());
    }
    @Test
    @DisplayName("上游失败保留平台状态码，不返回空成功")
    void upstreamFailureIsExplicit() {
        AmazonMessagingRealClient client = mock(AmazonMessagingRealClient.class);
        when(client.getMessagingActionsForOrder(SHOP_ID, ORDER_ID, MARKETPLACE_ID))
                .thenThrow(new RuntimeException("SP-API call failed method=GET status=429"
                        + " body={\"errors\":[{\"code\":\"QuotaExceeded\"}]}"));

        Result<Map<String, Object>> result = controller(client)
                .getActions(SHOP_ID, ORDER_ID, MARKETPLACE_ID);

        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().contains("status=429"), result.getMessage());
        assertTrue(result.getMessage().contains("QuotaExceeded"), result.getMessage());
        assertFalse(result.getMessage().contains("success"));
    }

    private static MessagingController controller(AmazonMessagingRealClient client) {
        MessagingController controller = new MessagingController();
        ReflectionTestUtils.setField(controller, "client", client);
        return controller;
    }
}