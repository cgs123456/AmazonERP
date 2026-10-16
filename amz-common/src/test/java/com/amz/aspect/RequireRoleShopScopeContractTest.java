package com.amz.aspect;

import com.amz.annotation.RequireRole;
import com.amz.context.UserContext;
import com.amz.result.Result;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RequireRoleAspect} 按**店铺**判角色的契约。
 * <p>
 * 存在动因见 HANDOFF §S22：接通店铺级角色之前，{@code @RequireRole} 只看全局角色，
 * 于是「店 A 是 OPERATOR、店 B 是 VIEWER」的用户在店 B 照样能写。
 * <p>
 * 更要紧的是**店铺 id 必须取自方法参数而不是请求头**：客户端把 header 设成自己有
 * OPERATOR 的店 A、请求却作用于店 B，只看 header 就会放行——那个洞本测试专门钉住。
 */
@DisplayName("RequireRole 按店铺判角色（店铺 id 取方法参数，不取请求头）")
class RequireRoleShopScopeContractTest {

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("全局 ADMIN 但在目标店只有 VIEWER：写操作被拒（收窄生效）")
    void shopLevelViewerIsRejectedEvenWhenGloballyAdmin() throws Throwable {
        UserContext.setRole("ADMIN");
        UserContext.setShopRoles(Map.of(2002L, "VIEWER"));
        ProceedingJoinPoint pjp = writeCall(2002L);

        Object result = new RequireRoleAspect().check(pjp, requireRole());

        assertInstanceOf(Result.class, result);
        assertEquals(400, ((Result<?>) result).getCode());
        verify(pjp, never()).proceed();
    }

    @Test
    @DisplayName("全局 VIEWER 但在目标店是 OPERATOR：放行（提权生效）")
    void shopLevelOperatorIsAllowedEvenWhenGloballyViewer() throws Throwable {
        UserContext.setRole("VIEWER");
        UserContext.setShopRoles(Map.of(1001L, "OPERATOR"));
        ProceedingJoinPoint pjp = writeCall(1001L);
        when(pjp.proceed()).thenReturn(Result.success("ok"));

        Object result = new RequireRoleAspect().check(pjp, requireRole());

        assertInstanceOf(Result.class, result);
        assertEquals(200, ((Result<?>) result).getCode());
        verify(pjp).proceed();
    }

    @Test
    @DisplayName("店铺 id 取方法参数：参数是店 B 就必须用店 B 的角色，哪怕上下文是店 A")
    void shopIdComesFromMethodParameterNotAmbientContext() throws Throwable {
        // 用户在两个店角色不同；请求头/上下文指向店 A（OPERATOR），参数指向店 B（VIEWER）
        UserContext.setRole("OPERATOR");
        UserContext.setShopId(1001L);
        Map<Long, String> byShop = new LinkedHashMap<>();
        byShop.put(1001L, "OPERATOR");
        byShop.put(2002L, "VIEWER");
        UserContext.setShopRoles(byShop);

        ProceedingJoinPoint pjp = writeCall(2002L);
        Object result = new RequireRoleAspect().check(pjp, requireRole());

        // 若实现改成读 UserContext.getShopId()（店 A），这里会放行 —— 那正是要防的错配洞
        assertInstanceOf(Result.class, result);
        assertEquals(400, ((Result<?>) result).getCode());
        verify(pjp, never()).proceed();
    }

    @Test
    @DisplayName("方法签名没有 shopId 参数时回落全局角色，与改动前行为一致")
    void endpointWithoutShopIdParamFallsBackToGlobalRole() throws Throwable {
        UserContext.setRole("OPERATOR");
        UserContext.setShopRoles(Map.of(1001L, "VIEWER"));
        ProceedingJoinPoint pjp = call("noShopParam");
        when(pjp.proceed()).thenReturn(Result.success("ok"));

        Object result = new RequireRoleAspect().check(pjp, requireRole());

        assertEquals(200, ((Result<?>) result).getCode());
        verify(pjp).proceed();
    }

    @Test
    @DisplayName("参数名与语义名不一致时仍按显式注解名认出来（@PathVariable(\"shopId\") Long tenantId）")
    void explicitAnnotationNameIsRecognised() throws Throwable {
        UserContext.setRole("ADMIN");
        UserContext.setShopRoles(Map.of(2002L, "VIEWER"));

        Method method = DummyController.class.getDeclaredMethod("pathScoped", Long.class);
        ProceedingJoinPoint pjp = pjpFor(method, new Object[]{2002L});
        Object result = new RequireRoleAspect().check(pjp, requireRole());

        assertEquals(400, ((Result<?>) result).getCode());
        verify(pjp, never()).proceed();
    }

    @Test
    @DisplayName("没有任何角色上下文时 fail-closed 拒绝")
    void missingRoleFailsClosed() throws Throwable {
        ProceedingJoinPoint pjp = writeCall(1001L);

        Object result = new RequireRoleAspect().check(pjp, requireRole());

        assertEquals(400, ((Result<?>) result).getCode());
        verify(pjp, never()).proceed();
    }

    @Test
    @DisplayName("受信服务身份放行，不受店铺角色影响")
    void trustedServiceStillProceeds() throws Throwable {
        UserContext.setInternalService("amz-service-finance");
        ProceedingJoinPoint pjp = writeCall(1001L);
        when(pjp.proceed()).thenReturn(Result.success("ok"));

        Object result = new RequireRoleAspect().check(pjp, requireRole());

        assertEquals(200, ((Result<?>) result).getCode());
        verify(pjp).proceed();
    }

    private static RequireRole requireRole() {
        return WRITE.getAnnotation(RequireRole.class);
    }

    private static final Method WRITE = writeMethod();

    private static Method writeMethod() {
        try {
            return DummyController.class.getDeclaredMethod("write", Long.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ProceedingJoinPoint writeCall(Long shopId) throws Exception {
        Method method = DummyController.class.getDeclaredMethod("write", Long.class);
        return pjpFor(method, new Object[]{shopId});
    }

    private static ProceedingJoinPoint call(String methodName) throws Exception {
        Method method = DummyController.class.getDeclaredMethod(methodName);
        return pjpFor(method, new Object[]{});
    }

    private static ProceedingJoinPoint pjpFor(Method method, Object[] args) {
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getArgs()).thenReturn(args);
        return pjp;
    }

    static class DummyController {
        @RequireRole({"OPERATOR", "ADMIN"})
        public Result<String> write(@RequestParam("shopId") Long shopId) {
            return Result.success("ok");
        }

        @RequireRole({"OPERATOR", "ADMIN"})
        public Result<String> pathScoped(@PathVariable("shopId") Long tenantId) {
            return Result.success("ok");
        }

        @RequireRole({"OPERATOR", "ADMIN"})
        public Result<String> noShopParam() {
            return Result.success("ok");
        }
    }
}
