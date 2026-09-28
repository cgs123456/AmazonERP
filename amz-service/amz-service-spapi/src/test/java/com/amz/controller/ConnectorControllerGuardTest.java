package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 6：新增 Controller 不得成为新的无守卫端点（附录 F 的 C 类风险）。
 * <p>
 * <b>为什么用反射而不是 Spring 上下文：</b>本类要防的是「以后有人往
 * {@link ConnectorController} 加一个 {@code @PostMapping} 却忘记加守卫」。
 * 反射扫描在本模块无 Spring 上下文时也能跑，且断言的是<b>源码事实</b>，
 * 不受 profile、Bean 装配或网关路由影响。
 * <p>
 * <b>另含第 22 轮 code review 遗留的回归断言：</b>
 * {@code FeedsController#submit} 属附录 F 的 C 类——没有方法级 {@code @ShopScoped}，
 * 归属校验只靠方法体内
 * {@code UserContext.isShopAllowedByUserOrTrustedService(request.getShopId())}。
 * 一旦后续重构删掉这行，该端点就变成完全无归属校验的写端点，因此必须锁住。
 * <p>
 * 证据类型 E1（自证：断言对象为本仓库源码本身）。
 */
@DisplayName("Task 6 连接器端点守卫（反射扫描 + FeedsController.submit 回归）")
class ConnectorControllerGuardTest {

    /** 认定为「端点」的注解类型。 */
    private static final List<Class<? extends Annotation>> MAPPING_ANNOTATIONS = List.of(
            GetMapping.class, PostMapping.class, PutMapping.class, DeleteMapping.class,
            PatchMapping.class, RequestMapping.class);

    private static boolean isEndpoint(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            if (MAPPING_ANNOTATIONS.contains(annotation.annotationType())) {
                return true;
            }
        }
        return false;
    }

    private static List<Method> endpoints(Class<?> type) {
        List<Method> found = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            if (isEndpoint(method)) {
                found.add(method);
            }
        }
        return found;
    }

    @Test
    @DisplayName("ConnectorController 的每个端点都带 @RequireRole 或 @ShopScoped")
    void everyEndpointIsGuarded() {
        List<Method> methods = endpoints(ConnectorController.class);
        assertFalse(methods.isEmpty(),
                "反射必须至少扫到 1 个端点；扫到 0 个说明注解判定失效，本断言会假通过");
        assertEquals(6, methods.size(), "当前为 list / rate-limits / outbox / replayOutbox / one / self-test 六个端点；"
                + "新增端点必须同步加守卫，否则本断言失败");
        for (Method method : methods) {
            boolean guarded = method.isAnnotationPresent(RequireRole.class)
                    || method.isAnnotationPresent(ShopScoped.class);
            assertTrue(guarded, "端点 " + method.getName() + " 缺少 @RequireRole / @ShopScoped；"
                    + "未守卫端点会成为附录 F 无守卫清单的新增项");
        }
    }

    @Test
    @DisplayName("自检端点只放行 OPERATOR/ADMIN（会触发真实出网调用，不得对 VIEWER 开放）")
    void selfTestRequiresElevatedRole() {
        Method selfTest = endpoints(ConnectorController.class).stream()
                .filter(method -> "selfTest".equals(method.getName()))
                .findFirst().orElseThrow(() -> new AssertionError("未找到 selfTest 端点"));
        RequireRole requireRole = selfTest.getAnnotation(RequireRole.class);
        assertTrue(requireRole != null, "selfTest 必须带 @RequireRole");
        List<String> roles = List.of(requireRole.value());
        assertTrue(roles.contains("ADMIN") && roles.contains("OPERATOR"), roles.toString());
        assertFalse(roles.contains("VIEWER"), "自检会触发真实出网调用，不得对 VIEWER 开放");
    }

    @Test
    @DisplayName("网关对外别名保持 /api/connectors，服务直连路径仍为 /spapi/connectors")
    void plannedPublicPathIsRegistered() {
        assertEquals("/api/connectors", ConnectorController.PLANNED_PUBLIC_PATH);
    }

    @Test
    @DisplayName("FeedsController.submit 保留双信任店铺校验（C 类端点唯一归属校验）")
    void feedsSubmitKeepsShopOwnershipCheck() throws IOException {
        String source = Files.readString(
                Paths.get("src/main/java/com/amz/controller/FeedsController.java"),
                StandardCharsets.UTF_8);
        String submit = methodSource(source, "public Result<String> submit(");
        assertTrue(submit.contains("UserContext.isShopAllowedByUserOrTrustedService"),
                "FeedsController.submit 的店铺归属校验只存在于方法体内；"
                        + "删除这行会使该写端点变成无归属校验端点（附录 F C 类）");
    }

    @Test
    @DisplayName("outcomeCode：能识别 status= 与 UnknownMarketplace，无法识别时回 UNKNOWN（不伪造 200）")
    void outcomeCodeExtraction() {
        assertEquals("429", ConnectorController.outcomeCode("RuntimeException: SP-API call failed status=429"));
        assertEquals("401", ConnectorController.outcomeCode("SP-API call failed status=401"));
        assertEquals(ConnectorController.OUTCOME_MARKETPLACE_MISSING,
                ConnectorController.outcomeCode("UnknownMarketplaceException: ATVPDKIKX0DER"));
        assertEquals(ConnectorController.OUTCOME_UNKNOWN, ConnectorController.outcomeCode("boom"));
        assertEquals(ConnectorController.OUTCOME_UNKNOWN, ConnectorController.outcomeCode(null));
    }

    /**
     * 抽取单个方法的源码（从签名行到方法体结束的 {@code     }}）。
     * <p>
     * 只依赖本仓库统一的 4 空格缩进风格；抽取不到时会返回空串，由调用方的断言失败暴露。
     */
    private static String methodSource(String fileSource, String signature) {
        String[] lines = fileSource.split("\n", -1);
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(signature)) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
            if (i > start && lines[i].equals("    }")) {
                break;
            }
        }
        return sb.toString();
    }
}
