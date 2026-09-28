package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.context.UserContext;
import com.amz.credential.ShopCredentialStore;
import com.amz.preflight.PreflightReport;
import com.amz.preflight.SpApiConnectivityPreflight;
import com.amz.result.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SP-API 分层连通性自检的 HTTP 契约。
 *
 * <p>该端点会真实出网；GET 还会把 forceTokenRefresh 的副作用藏在“安全方法”语义里，
 * 因此统一使用 POST，并要求 OPERATOR/ADMIN + 严格店铺归属校验。
 */
@DisplayName("SP-API 分层连通性自检端点契约")
class PreflightControllerContractTest {

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("单店和批量自检均使用 POST，且只允许 OPERATOR/ADMIN")
    void endpointsUsePostAndRequireElevatedRole() throws Exception {
        assertPostAndRole("one", Long.class, boolean.class);
        assertPostAndRole("all", boolean.class, int.class);
    }

    @Test
    @DisplayName("无店铺授权的 OPERATOR 不能触发单店自检")
    void oneFailsClosedWithoutShopScope() {
        SpApiConnectivityPreflight preflight = mock(SpApiConnectivityPreflight.class);
        PreflightController controller = new PreflightController(preflight, mock(ShopCredentialStore.class));
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of());

        Result<PreflightReport> result = controller.one(1001L, false);

        assertEquals(400, result.getCode());
        assertNotNull(result.getError());
        assertEquals("PREFLIGHT_FORBIDDEN", result.getError().getCode());
        verifyNoInteractions(preflight);
    }

    @Test
    @DisplayName("有店铺授权时透传 shopId 与 forceTokenRefresh")
    void onePassesScopeAndRefreshFlag() {
        SpApiConnectivityPreflight preflight = mock(SpApiConnectivityPreflight.class);
        PreflightController controller = new PreflightController(preflight, mock(ShopCredentialStore.class));
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(7L));
        PreflightReport report = report(7L);
        when(preflight.check(7L, true)).thenReturn(report);

        Result<PreflightReport> result = controller.one(7L, true);

        assertEquals(200, result.getCode());
        assertSame(report, result.getData());
        verify(preflight).check(7L, true);
    }

    @Test
    @DisplayName("批量自检 limit 越界 fail-closed，不触发出网")
    void allRejectsInvalidLimit() {
        SpApiConnectivityPreflight preflight = mock(SpApiConnectivityPreflight.class);
        PreflightController controller = new PreflightController(preflight, mock(ShopCredentialStore.class));

        Result<List<PreflightReport>> result = controller.all(false, 0);

        assertEquals(400, result.getCode());
        assertNotNull(result.getError());
        assertEquals("INVALID_REQUEST", result.getError().getCode());
        verifyNoInteractions(preflight);
    }

    @Test
    @DisplayName("批量自检只检查当前用户授权店铺，并严格停在 limit")
    void allFiltersUnauthorizedShopsAndStopsAtLimit() {
        SpApiConnectivityPreflight preflight = mock(SpApiConnectivityPreflight.class);
        ShopCredentialStore store = mock(ShopCredentialStore.class);
        when(store.getActiveShopIds()).thenReturn(new LinkedHashSet<>(List.of(1L, 2L, 3L)));
        when(preflight.check(2L, false)).thenReturn(report(2L));
        PreflightController controller = new PreflightController(preflight, store);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(2L));

        Result<List<PreflightReport>> result = controller.all(false, 1);

        assertEquals(200, result.getCode());
        assertNotNull(result.getData());
        assertEquals(1, result.getData().size());
        assertEquals(2L, result.getData().get(0).shopId());
        verify(preflight).check(2L, false);
    }

    private static void assertPostAndRole(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = PreflightController.class.getDeclaredMethod(methodName, parameterTypes);
        assertNotNull(method.getAnnotation(PostMapping.class),
                methodName + " 会触发出网/缓存副作用，必须使用 POST，而不是 GET");
        RequireRole requireRole = method.getAnnotation(RequireRole.class);
        assertNotNull(requireRole, methodName + " 必须带 @RequireRole");
        List<String> roles = List.of(requireRole.value());
        assertEquals(List.of("OPERATOR", "ADMIN"), roles);
    }

    private static PreflightReport report(Long shopId) {
        return new PreflightReport(
                shopId,
                "ATVPDKIKX0DER",
                "NA",
                "sellingpartnerapi-na.amazon.com",
                false,
                Instant.parse("2026-09-27T00:00:00Z"),
                true,
                List.of(),
                "test");
    }
}