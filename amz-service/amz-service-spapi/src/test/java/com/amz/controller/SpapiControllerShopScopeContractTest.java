package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.context.UserContext;
import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialAdminService;
import com.amz.credential.ShopCredentialConcurrentUpdateException;
import com.amz.credential.ShopCredentialStore;
import com.amz.credential.ShopCredentialUpdateRequest;
import com.amz.result.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("SpapiController 旧凭证入口：ADMIN-only 且委托 CAS 管理服务")
class SpapiControllerShopScopeContractTest {

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("无店铺授权的 VIEWER 不能写入凭证，且不得触碰凭证存储或管理服务")
    void credentialWriteFailsClosedWithoutShopScope() {
        ShopCredentialStore store = mock(ShopCredentialStore.class);
        ShopCredentialAdminService adminService = mock(ShopCredentialAdminService.class);
        SpapiController controller = controller(store, adminService);
        UserContext.setRole("VIEWER");
        UserContext.setShops(List.of());

        ShopCredential credential = new ShopCredential();
        credential.setShopId(1001L);

        Result<String> result = controller.saveCredential(credential);

        assertEquals(400, result.getCode());
        assertNotNull(result.getError());
        assertEquals("FORBIDDEN", result.getError().getCode());
        verifyNoInteractions(store, adminService);
    }

    @Test
    @DisplayName("旧凭证入口只允许 ADMIN，不得继续暴露给 OPERATOR")
    void legacyCredentialWriteRequiresAdminRole() throws Exception {
        Method method = SpapiController.class.getMethod("saveCredential", ShopCredential.class);
        RequireRole requireRole = method.getAnnotation(RequireRole.class);

        assertNotNull(requireRole);
        List<String> roles = List.of(requireRole.value());
        assertTrue(roles.contains("ADMIN"));
        assertFalse(roles.contains("OPERATOR"));
        assertFalse(roles.contains("VIEWER"));
    }

    @Test
    @DisplayName("缺少 shops claim 的 VIEWER 不能写入凭证，且不得触碰凭证存储或管理服务")
    void credentialWriteFailsClosedWhenShopClaimMissing() {
        ShopCredentialStore store = mock(ShopCredentialStore.class);
        ShopCredentialAdminService adminService = mock(ShopCredentialAdminService.class);
        SpapiController controller = controller(store, adminService);
        UserContext.setRole("VIEWER");
        UserContext.setShops(null);

        ShopCredential credential = new ShopCredential();
        credential.setShopId(1001L);

        Result<String> result = controller.saveCredential(credential);

        assertEquals(400, result.getCode());
        assertNotNull(result.getError());
        assertEquals("FORBIDDEN", result.getError().getCode());
        verifyNoInteractions(store, adminService);
    }

    @Test
    @DisplayName("ADMIN 提交完整凭证时必须委托 CAS 管理服务，不能再调用无条件覆盖 put")
    void structurallyValidCredentialDelegatesToCasAdminService() {
        ShopCredentialStore store = mock(ShopCredentialStore.class);
        ShopCredentialAdminService adminService = mock(ShopCredentialAdminService.class);
        SpapiController controller = controller(store, adminService);
        UserContext.setRole("ADMIN");

        ShopCredential credential = validCredential();
        Result<String> result = controller.saveCredential(credential);

        ArgumentCaptor<ShopCredentialUpdateRequest> request = ArgumentCaptor.forClass(
                ShopCredentialUpdateRequest.class);
        verify(adminService).upsert(eq(1001L), request.capture());
        assertEquals(credential.getClientId(), request.getValue().getClientId());
        assertEquals(credential.getClientSecret(), request.getValue().getClientSecret());
        assertEquals(credential.getRefreshToken(), request.getValue().getRefreshToken());
        assertEquals(credential.getRegion(), request.getValue().getRegion());
        assertEquals(credential.getMarketplaceId(), request.getValue().getMarketplaceId());
        assertEquals(credential.getSellerId(), request.getValue().getSellerId());
        assertEquals(200, result.getCode());
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("结构残缺凭证必须拒绝，且不得触碰凭证存储或管理服务")
    void structurallyInvalidCredentialIsRejectedBeforeStore() {
        ShopCredentialStore store = mock(ShopCredentialStore.class);
        ShopCredentialAdminService adminService = mock(ShopCredentialAdminService.class);
        SpapiController controller = controller(store, adminService);
        UserContext.setRole("ADMIN");

        ShopCredential credential = validCredential();
        credential.setRefreshToken(null);
        Result<String> result = controller.saveCredential(credential);

        assertEquals(400, result.getCode());
        assertNotNull(result.getError());
        assertEquals("INVALID_REQUEST", result.getError().getCode());
        assertTrue(result.getMessage().contains("refreshToken"));
        assertFalse(result.getMessage().contains("test-refresh-token"));
        verifyNoInteractions(store, adminService);
    }

    @Test
    @DisplayName("CAS 冲突必须返回机器可读 CONFLICT，不能降级为成功")
    void casConflictIsReturnedToLegacyCaller() {
        ShopCredentialStore store = mock(ShopCredentialStore.class);
        ShopCredentialAdminService adminService = mock(ShopCredentialAdminService.class);
        SpapiController controller = controller(store, adminService);
        UserContext.setRole("ADMIN");

        ShopCredential credential = validCredential();
        doThrow(new ShopCredentialConcurrentUpdateException(1001L))
                .when(adminService).upsert(eq(1001L), org.mockito.ArgumentMatchers.any());

        Result<String> result = controller.saveCredential(credential);

        assertEquals(400, result.getCode());
        assertNotNull(result.getError());
        assertEquals("CONFLICT", result.getError().getCode());
        verifyNoInteractions(store);
    }

    private static ShopCredential validCredential() {
        ShopCredential credential = new ShopCredential();
        credential.setShopId(1001L);
        credential.setClientId("amzn1.application-oa2-client.test");
        credential.setClientSecret("test-client-secret");
        credential.setRefreshToken("test-refresh-token");
        credential.setRegion("NA");
        credential.setMarketplaceId("ATVPDKIKX0DER");
        credential.setSellerId("A1TESTSHOP");
        return credential;
    }

    private static SpapiController controller(ShopCredentialStore store,
                                              ShopCredentialAdminService adminService) {
        SpapiController controller = new SpapiController();
        ReflectionTestUtils.setField(controller, "shopCredentialStore", store);
        ReflectionTestUtils.setField(controller, "shopCredentialAdminService", adminService);
        return controller;
    }
}
