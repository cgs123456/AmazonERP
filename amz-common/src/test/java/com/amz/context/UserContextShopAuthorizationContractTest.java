package com.amz.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("UserContext 店铺授权：已认证 fail-closed，内部服务身份放行，无身份拒绝")
class UserContextShopAuthorizationContractTest {

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("无任何身份（无 userId、无内部服务身份）：不再放行（§S33.121 收紧）")
    void noIdentityAtAllIsRejected() {
        // 这条断言在 §S33.121 之前是 assertTrue —— 原口径把「谁都不是」解释为
        // 「可访问所有店铺」。实测确认全仓定时任务都显式 setUserId + setShops，
        // 没有一方依赖这个形状，故收紧为拒绝。
        // 若将来有人真的需要无身份放行，必须显式 setInternalService，
        // 让「这次调用代表哪个服务」可追溯，而不是隐式地放开。
        assertFalse(UserContext.isShopAllowed(1001L));
    }

    @Test
    @DisplayName("带内部服务身份、无 userId：仍放行（service 间调用的唯一通路）")
    void internalServiceIdentityWithoutUserIdStillGranted() {
        // @InternalServiceAccess 端点只设 internalService、不设 userId
        // （BaseAuthInterceptor:166），这一支是它们唯一的通路。
        UserContext.setInternalService("amz-service-finance");

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
