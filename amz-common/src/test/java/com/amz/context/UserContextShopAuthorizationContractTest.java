package com.amz.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("UserContext 店铺授权：已认证请求 fail-closed，内部调用保持兼容")
class UserContextShopAuthorizationContractTest {

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("无 userId 的内部调用在 shops 缺失时继续兼容放行")
    void internalCallWithoutUserContextRemainsCompatible() {
        assertTrue(UserContext.isShopAllowed(1001L));
    }

    @Test
    @DisplayName("已认证非 ADMIN 缺少 shops 时不能访问任意店铺")
    void authenticatedNonAdminWithoutShopsFailsClosed() {
        UserContext.setUserId(1);
        UserContext.setRole("VIEWER");

        assertFalse(UserContext.isShopAllowed(1001L));
        assertFalse(UserContext.isShopAllowedStrict(1001L));
    }

    @Test
    @DisplayName("已认证用户命中授权店铺时允许访问")
    void authenticatedUserWithMatchingShopIsAllowed() {
        UserContext.setUserId(1);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(1001L));

        assertTrue(UserContext.isShopAllowed(1001L));
    }

    @Test
    @DisplayName("已认证用户访问未授权店铺时拒绝")
    void authenticatedUserCannotAccessOtherShop() {
        UserContext.setUserId(1);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(2002L));

        assertFalse(UserContext.isShopAllowed(1001L));
    }

    @Test
    @DisplayName("ADMIN 缺少 shops claim 仍保留全局管理语义")
    void adminWithoutShopsKeepsGlobalAccess() {
        UserContext.setUserId(1);
        UserContext.setRole("ADMIN");

        assertTrue(UserContext.isShopAllowed(1001L));
    }

    @Test
    @DisplayName("显式设置系统用户和单店 shops 的定时任务仍可访问目标店铺")
    void systemUserWithExplicitShopCanAccessTargetShop() {
        UserContext.setUserId(0);
        UserContext.setShops(List.of(1001L));

        assertTrue(UserContext.isShopAllowed(1001L));
        assertFalse(UserContext.isShopAllowed(2002L));
    }

    @Test
    @DisplayName("可信服务身份仍不能绕过空 shopId 参数校验")
    void trustedServiceCannotBypassNullShopId() {
        UserContext.setInternalService("amz-service-finance");

        assertFalse(UserContext.isShopAllowedByUserOrTrustedService(null));
    }

    @Test
    @DisplayName("可信服务身份可访问显式指定店铺")
    void trustedServiceCanAccessExplicitShop() {
        UserContext.setInternalService("amz-service-finance");

        assertTrue(UserContext.isShopAllowedByUserOrTrustedService(1001L));
    }
}
