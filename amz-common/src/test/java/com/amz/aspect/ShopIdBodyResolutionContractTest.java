package com.amz.aspect;

import com.amz.annotation.ShopScoped;
import com.amz.context.UserContext;
import com.amz.result.Result;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 请求体携带 shopId 时的租户校验契约。
 * <p>
 * <b>存在动因（这是一条真实越权路径，不是假想）</b>：网关只校验请求头 {@code shopId}
 * 是否属于当前用户，而请求头是「客户端当前选中的店」；请求体里的 shopId 是完全独立的一个值。
 * 攻击者把请求头设成自己的店（过网关），请求体里填受害者的店（过切面）——
 * 只要 shopId 只在请求体里，{@link ShopIdGuardAspect} 就解析不到参数，
 * 按「未找到 shopId 参数 → 兼容放行」跳过校验。<b>两层防线同时让开。</b>
 * <p>
 * 本仓现状：32 个 {@code @ShopScoped} 写端点的 shopId 只在请求体（或请求体的实体里）。
 * 其中 amz-service-product 的 5 个端点连 service 层行级校验都没有——
 * 即任何已登录用户（含 VIEWER）都能对任意店铺写入。
 * <p>
 * 所以这里钉住的是：**shopId 无论出现在路径、查询串还是请求体，都必须被解析到并校验**。
 */
@DisplayName("请求体 shopId 必须参与租户校验（切面不得静默跳过）")
class ShopIdBodyResolutionContractTest {

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("shopId 在请求体实体里：他店写入必须被拒")
    void bodyShopIdForeignIsRejected() throws Throwable {
        UserContext.setUserId(1);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(1001L));

        ProceedingJoinPoint pjp = call("bodyScoped", new BodyWithShop(1002L));

        Object result = new ShopIdGuardAspect().guard(pjp);

        assertInstanceOf(Result.class, result);
        assertEquals(400, ((Result<?>) result).getCode());
        verify(pjp, never()).proceed();
    }

    @Test
    @DisplayName("shopId 在请求体实体里：本店写入正常放行")
    void bodyShopIdOwnedProceeds() throws Throwable {
        UserContext.setUserId(1);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(1001L));

        ProceedingJoinPoint pjp = call("bodyScoped", new BodyWithShop(1001L));
        when(pjp.proceed()).thenReturn(Result.success("ok"));

        Object result = new ShopIdGuardAspect().guard(pjp);

        assertInstanceOf(Result.class, result);
        assertEquals(200, ((Result<?>) result).getCode());
        verify(pjp).proceed();
    }

    @Test
    @DisplayName("解析器：请求体实体里的 shopId 能被解析出来")
    void resolverReadsBodyShopId() throws Exception {
        Method method = DummyController.class.getDeclaredMethod("bodyScoped", BodyWithShop.class);

        Long resolved = ShopIdParameterResolver.resolve(method, new Object[]{new BodyWithShop(1002L)});

        assertEquals(1002L, resolved);
    }

    @Test
    @DisplayName("解析器：请求体为 null 时返回 null（不抛异常，交由调用方降级）")
    void resolverToleratesNullBody() throws Exception {
        Method method = DummyController.class.getDeclaredMethod("bodyScoped", BodyWithShop.class);

        assertNull(ShopIdParameterResolver.resolve(method, new Object[]{null}));
    }

    @Test
    @DisplayName("解析器：请求体实体没有 shopId 字段时返回 null（不能凭类型名猜）")
    void resolverIgnoresBodyWithoutShopIdField() throws Exception {
        Method method = DummyController.class.getDeclaredMethod("noShopBody", BodyNoShop.class);

        assertNull(ShopIdParameterResolver.resolve(method, new Object[]{new BodyNoShop("x")}));
    }

    @Test
    @DisplayName("路径参数优先于请求体（同一方法两种来源时以路径为准）")
    void pathVariableWinsOverBody() throws Exception {
        Method method = DummyController.class.getDeclaredMethod("bothScoped", Long.class, BodyWithShop.class);

        Long resolved = ShopIdParameterResolver.resolve(
                method, new Object[]{1001L, new BodyWithShop(1002L)});

        assertEquals(1001L, resolved);
    }

    private static ProceedingJoinPoint call(String methodName, Object arg) throws Exception {
        Method method = DummyController.class.getDeclaredMethod(methodName, arg.getClass());
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getArgs()).thenReturn(new Object[]{arg});
        return pjp;
    }

    /** 模拟请求体实体：shopId 是普通字段（Lombok @Data 生成的形态）。 */
    static class BodyWithShop {
        private Long shopId;

        BodyWithShop(Long shopId) {
            this.shopId = shopId;
        }

        public Long getShopId() {
            return shopId;
        }
    }

    /** 模拟请求体实体：没有 shopId 字段（如 OauthApp）。 */
    static class BodyNoShop {
        private String name;

        BodyNoShop(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    static class DummyController {
        @ShopScoped
        public Result<String> bodyScoped(@RequestBody BodyWithShop body) {
            return Result.success("ok");
        }

        @ShopScoped
        public Result<String> noShopBody(@RequestBody BodyNoShop body) {
            return Result.success("ok");
        }

        @ShopScoped
        public Result<String> bothScoped(@PathVariable("shopId") Long shopId,
                                         @RequestBody BodyWithShop body) {
            return Result.success("ok");
        }

        @ShopScoped
        public Result<String> pathOnly(@PathVariable("shopId") Long shopId) {
            return Result.success("ok");
        }

        @ShopScoped
        public Result<String> queryOnly(@RequestParam("shopId") Long shopId) {
            return Result.success("ok");
        }
    }
}
