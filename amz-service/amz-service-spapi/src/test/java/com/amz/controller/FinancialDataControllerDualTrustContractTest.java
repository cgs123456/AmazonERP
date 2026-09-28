package com.amz.controller;

import com.amz.annotation.InternalServiceAccess;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("FinancialDataController 双信任契约：finance 服务可直连，用户写操作仍受角色约束")
class FinancialDataControllerDualTrustContractTest {

    @Test
    @DisplayName("所有财务取数端点只允许 amz-service-finance 使用服务令牌")
    void allFinanceEndpointsAllowOnlyFinanceService() throws Exception {
        assertInternalAccess("requestReport", new Class<?>[]{
                Long.class, String.class, String.class, String.class, String.class});
        assertInternalAccess("getReport", new Class<?>[]{String.class, Long.class});
        assertInternalAccess("downloadDocument", new Class<?>[]{String.class, Long.class});
        assertInternalAccess("listEvents", new Class<?>[]{Long.class, String.class, String.class});
        assertInternalAccess("estimateFees", new Class<?>[]{
                Long.class, String.class, String.class, String.class, String.class,
                java.math.BigDecimal.class, String.class});
    }

    @Test
    @DisplayName("两个写端点保留 OPERATOR/ADMIN 角色约束")
    void writeEndpointsRequireElevatedRoles() throws Exception {
        assertElevatedRole("requestReport", new Class<?>[]{
                Long.class, String.class, String.class, String.class, String.class});
        assertElevatedRole("estimateFees", new Class<?>[]{
                Long.class, String.class, String.class, String.class, String.class,
                java.math.BigDecimal.class, String.class});
    }

    private static void assertInternalAccess(String methodName, Class<?>[] parameterTypes) throws Exception {
        Method method = FinancialDataController.class.getMethod(methodName, parameterTypes);
        InternalServiceAccess access = method.getAnnotation(InternalServiceAccess.class);
        assertNotNull(access, methodName + " 缺少 @InternalServiceAccess");
        assertTrue(List.of(access.value()).equals(List.of("amz-service-finance")),
                methodName + " 只应允许 amz-service-finance");
        assertNotNull(method.getAnnotation(ShopScoped.class), methodName + " 缺少 @ShopScoped");
    }

    private static void assertElevatedRole(String methodName, Class<?>[] parameterTypes) throws Exception {
        Method method = FinancialDataController.class.getMethod(methodName, parameterTypes);
        RequireRole role = method.getAnnotation(RequireRole.class);
        assertNotNull(role, methodName + " 缺少 @RequireRole");
        List<String> roles = List.of(role.value());
        assertTrue(roles.contains("OPERATOR"), methodName + " 应允许 OPERATOR");
        assertTrue(roles.contains("ADMIN"), methodName + " 应允许 ADMIN");
    }
}
