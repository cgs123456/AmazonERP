package com.amz.aspect;

import com.amz.annotation.ShopScoped;
import com.amz.context.UserContext;
import com.amz.interceptor.BaseAuthInterceptor;
import com.amz.result.Result;
import com.amz.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ShopIdGuardAspect：已认证空店铺授权必须 fail-closed，内部调用保持兼容")
class ShopIdGuardAspectAuthenticationContractTest {

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("合法 JWT 但无 shops claim 的 VIEWER 不能到达 @ShopScoped 方法")
    void authenticatedTokenWithoutShopsCannotReachScopedEndpoint() throws Throwable {
        JwtUtil jwtUtil = mock(JwtUtil.class);
        when(jwtUtil.parseToken("token")).thenReturn("1");
        when(jwtUtil.parseTokenRole("token")).thenReturn("VIEWER");
        when(jwtUtil.parseTokenShops("token")).thenReturn(List.of());

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/spapi/sync/orders");
        when(request.getHeader("token")).thenReturn("token");

        BaseAuthInterceptor interceptor = new BaseAuthInterceptor(jwtUtil);
        assertTrue(interceptor.preHandle(request, mock(HttpServletResponse.class), new Object()));

        ProceedingJoinPoint pjp = scopedCall();
        Object result = new ShopIdGuardAspect().guard(pjp);

        assertInstanceOf(Result.class, result);
        assertEquals(400, ((Result<?>) result).getCode());
        verify(pjp, never()).proceed();
    }

    @Test
    @DisplayName("内部调用没有用户上下文时保持兼容放行")
    void internalCallWithoutUserContextStillProceeds() throws Throwable {
        ProceedingJoinPoint pjp = scopedCall();
        when(pjp.proceed()).thenReturn(Result.success("ok"));

        Object result = new ShopIdGuardAspect().guard(pjp);

        assertInstanceOf(Result.class, result);
        assertEquals(200, ((Result<?>) result).getCode());
        verify(pjp).proceed();
    }

    @Test
    @DisplayName("路径变量参数名不同时仍按 @PathVariable 显式名称校验并拒绝越权")
    void explicitPathVariableNameIsEnforced() throws Throwable {
        UserContext.setUserId(1);
        UserContext.setRole("VIEWER");
        UserContext.setShops(List.of(1001L));
        ProceedingJoinPoint pjp = scopedCall("pathScoped", 1002L);

        Object result = new ShopIdGuardAspect().guard(pjp);

        assertInstanceOf(Result.class, result);
        assertEquals(400, ((Result<?>) result).getCode());
        verify(pjp, never()).proceed();
    }

    @Test
    @DisplayName("查询参数名不同时仍按 @RequestParam 显式名称校验并拒绝越权")
    void explicitRequestParamNameIsEnforced() throws Throwable {
        UserContext.setUserId(1);
        UserContext.setRole("VIEWER");
        UserContext.setShops(List.of(1001L));
        ProceedingJoinPoint pjp = scopedCall("queryScoped", 1002L);

        Object result = new ShopIdGuardAspect().guard(pjp);

        assertInstanceOf(Result.class, result);
        assertEquals(400, ((Result<?>) result).getCode());
        verify(pjp, never()).proceed();
    }

    private static ProceedingJoinPoint scopedCall() throws Exception {
        return scopedCall("scoped", 1001L);
    }

    private static ProceedingJoinPoint scopedCall(String methodName, Long shopId) throws Exception {
        Method method = DummyController.class.getDeclaredMethod(methodName, Long.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getArgs()).thenReturn(new Object[]{shopId});
        return pjp;
    }

    static class DummyController {
        @ShopScoped
        public Result<String> scoped(@RequestParam("shopId") Long shopId) {
            return Result.success("ok");
        }

        @ShopScoped
        public Result<String> pathScoped(@PathVariable("shopId") Long tenantId) {
            return Result.success("ok");
        }

        @ShopScoped
        public Result<String> queryScoped(@RequestParam(name = "shopId") Long tenantId) {
            return Result.success("ok");
        }
    }
}
