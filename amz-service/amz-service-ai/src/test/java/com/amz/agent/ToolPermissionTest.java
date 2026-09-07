package com.amz.agent;

import com.amz.context.UserContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 工具权限测试（A-3）。
 * <p>
 * UserContext 为静态 ThreadLocal，每个用例结束必须 clear，
 * 防止角色残留污染同线程后续用例。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Agent 工具权限测试")
class ToolPermissionTest {

    private static final Set<String> EXPECTED_OPERATE_TOOLS = Set.of(
            "create_purchase_plan",
            "auto_reply_message",
            "generate_promotion_plan",
            "cross_marketplace_listing",
            "optimize_ad_campaign",
            "optimize_listing_seo",
            "optimize_shipping_route",
            "optimize_inventory_distribution");

    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("([a-z0-9])([A-Z])");

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    private static JsonObject call(ErpToolExecutor executor, String name) {
        FunctionCall call = new FunctionCall();
        call.setName(name);
        call.setArguments(new HashMap<>(Map.of("shopId", 1)));
        return JsonParser.parseString(executor.execute(call)).getAsJsonObject();
    }

    @Test
    @DisplayName("VIEWER 调用全部 operate 工具应被拒绝")
    void testViewerDeniedForAllOperateTools() {
        UserContext.setRole("VIEWER");
        ErpToolExecutor executor = new ErpToolExecutor();

        for (String tool : EXPECTED_OPERATE_TOOLS) {
            JsonObject json = call(executor, tool);
            assertFalse(json.get("ok").getAsBoolean(), tool + " 应被 VIEWER 拒绝");
            assertTrue(json.get("message").getAsString().contains("OPERATOR"),
                    tool + " 拒绝信息应提示所需权限");
        }
    }

    @Test
    @DisplayName("ADMIN/OPERATOR 应放行（以无需后端的未知参数路径验证不经过权限拒绝）")
    void testAdminAllowed() {
        // 用未知参数触发工具内部分支：能走到业务逻辑即证明通过权限门禁
        // （create_purchase_plan 无有效参数时返回失败而非权限拒绝）
        UserContext.setRole("ADMIN");
        ErpToolExecutor executor = new ErpToolExecutor();

        FunctionCall call = new FunctionCall();
        call.setName("create_purchase_plan");
        call.setArguments(new HashMap<>());
        JsonObject json = JsonParser.parseString(executor.execute(call)).getAsJsonObject();
        assertFalse(json.get("message").getAsString().contains("OPERATOR"),
                "ADMIN 不应被权限门禁拦截");
    }

    @Test
    @DisplayName("无鉴权上下文（role=null，如内部调用/单测）应放行")
    void testNullRoleAllowed() {
        // UserContext 未设置 role，保持 null
        assertTrue(ErpToolExecutor.hasOperatePermission());
    }

    @Test
    @DisplayName("hasOperatePermission 角色判定")
    void testHasOperatePermissionRoles() {
        UserContext.setRole("VIEWER");
        assertFalse(ErpToolExecutor.hasOperatePermission());

        UserContext.setRole("OPERATOR");
        assertTrue(ErpToolExecutor.hasOperatePermission());

        UserContext.setRole("ADMIN");
        assertTrue(ErpToolExecutor.hasOperatePermission());
    }

    @Test
    @DisplayName("@ToolPermission 标注与 OPERATE_TOOLS 集合双向一致（防漂移）")
    void testAnnotationAndSetConsistent() {
        // 1) 集合内容必须与预期 8 个一致
        assertEquals(EXPECTED_OPERATE_TOOLS, ErpToolExecutor.OPERATE_TOOLS);

        // 2) 被标注为 operate 的方法集合（方法名转 snake 即工具名）必须与集合一致
        Set<String> annotated = new HashSet<>();
        for (Method m : ErpToolExecutor.class.getDeclaredMethods()) {
            ToolPermission ann = m.getAnnotation(ToolPermission.class);
            if (ann != null && "agent:operate".equals(ann.value())) {
                annotated.add(camelToSnake(m.getName()));
            }
        }
        assertEquals(EXPECTED_OPERATE_TOOLS, annotated, "标注与集合不一致：新增 operate 工具必须两边同步");
    }

    private static String camelToSnake(String name) {
        Matcher m = CAMEL_BOUNDARY.matcher(name);
        return m.replaceAll("$1_$2").toLowerCase();
    }
}
