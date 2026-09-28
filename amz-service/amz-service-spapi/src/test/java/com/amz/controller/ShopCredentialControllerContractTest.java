package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.context.UserContext;
import com.amz.credential.ShopCredentialAdminService;
import com.amz.credential.ShopCredentialConcurrentUpdateException;
import com.amz.credential.ShopCredentialStatus;
import com.amz.credential.ShopCredentialUpdateRequest;
import com.amz.result.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 凭证管理 HTTP 边界的权限、租户隔离与脱敏契约。
 */
@DisplayName("SP-API 凭证管理端点契约")
class ShopCredentialControllerContractTest {

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("状态/写入/删除端点分别使用 GET/PUT/DELETE，且只允许 ADMIN")
    void endpointsUseExpectedMethodsAndRequireAdmin() throws Exception {
        assertMappingAndRole("status", GetMapping.class, Long.class);
        assertMappingAndRole("upsert", PutMapping.class, Long.class, ShopCredentialUpdateRequest.class);
        assertMappingAndRole("delete", DeleteMapping.class, Long.class);
    }

    @Test
    @DisplayName("无店铺授权的 ADMIN 身份缺失时，状态查询 fail-closed")
    void statusFailsClosedWithoutAdminRole() {
        ShopCredentialAdminService service = mock(ShopCredentialAdminService.class);
        ShopCredentialController controller = new ShopCredentialController(service);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(7L));

        Result<ShopCredentialStatus> result = controller.status(7L);

        assertEquals(400, result.getCode());
        assertEquals("FORBIDDEN", result.getError().getCode());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("非授权店铺必须在写凭证前 fail-closed")
    void upsertRejectsUnauthorizedShopBeforeWrite() {
        ShopCredentialAdminService service = mock(ShopCredentialAdminService.class);
        ShopCredentialController controller = new ShopCredentialController(service);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of());

        Result<ShopCredentialStatus> result = controller.upsert(7L, new ShopCredentialUpdateRequest());

        assertEquals(400, result.getCode());
        assertEquals("FORBIDDEN", result.getError().getCode());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("非授权店铺必须在删除凭证前 fail-closed")
    void deleteRejectsUnauthorizedShopBeforeDelete() {
        ShopCredentialAdminService service = mock(ShopCredentialAdminService.class);
        ShopCredentialController controller = new ShopCredentialController(service);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of());

        Result<ShopCredentialStatus> result = controller.delete(7L);

        assertEquals(400, result.getCode());
        assertEquals("FORBIDDEN", result.getError().getCode());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("ADMIN 的授权店铺只从路径变量传入，服务层收到相同 shopId")
    void adminUsesPathShopId() {
        ShopCredentialAdminService service = mock(ShopCredentialAdminService.class);
        ShopCredentialStatus expected = new ShopCredentialStatus(
                false, false, false, false, false, null, null, null, null);
        when(service.status(7L)).thenReturn(expected);
        ShopCredentialController controller = new ShopCredentialController(service);
        UserContext.setRole("ADMIN");

        Result<ShopCredentialStatus> result = controller.status(7L);

        assertEquals(200, result.getCode());
        verify(service).status(7L);
    }

    @Test
    @DisplayName("CAS conflict is returned with a machine-readable CONFLICT code")
    void casConflictIsReturnedAsConflictCode() {
        ShopCredentialAdminService service = mock(ShopCredentialAdminService.class);
        when(service.upsert(eq(7L), any(ShopCredentialUpdateRequest.class)))
                .thenThrow(new ShopCredentialConcurrentUpdateException(7L));
        ShopCredentialController controller = new ShopCredentialController(service);
        UserContext.setRole("ADMIN");

        Result<ShopCredentialStatus> result = controller.upsert(7L, new ShopCredentialUpdateRequest());

        assertEquals(400, result.getCode());
        assertNotNull(result.getError());
        assertEquals("CONFLICT", result.getError().getCode());
    }

    @Test
    @DisplayName("请求 DTO 不得包含 shopId，避免 body 覆盖路径造成 IDOR")
    void updateRequestDoesNotCarryShopId() {
        Set<String> fields = Arrays.stream(ShopCredentialUpdateRequest.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .collect(Collectors.toSet());

        assertFalse(fields.contains("shopId"), "shopId 只能来自路径变量");
    }

    @Test
    @DisplayName("状态响应 record 必须只有安全字段，不能出现任何秘密字段")
    void statusResponseContainsOnlySafeFields() {
        Set<String> fields = Arrays.stream(ShopCredentialStatus.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "configured",
                "clientIdConfigured",
                "clientSecretConfigured",
                "refreshTokenConfigured",
                "awsKeysConfigured",
                "region",
                "marketplaceId",
                "sellerId",
                "updatedAt"), fields);
        for (String secretField : List.of("clientSecret", "refreshToken", "accessKey", "secretKey")) {
            assertFalse(fields.contains(secretField), secretField + " 绝不能出现在状态响应中");
        }
    }

    private static void assertMappingAndRole(String methodName,
                                             Class<? extends Annotation> mappingType,
                                             Class<?>... parameterTypes) throws Exception {
        Method method = ShopCredentialController.class.getDeclaredMethod(methodName, parameterTypes);
        assertNotNull(method.getAnnotation(mappingType), methodName + " 的 HTTP 方法注解缺失");
        RequireRole requireRole = method.getAnnotation(RequireRole.class);
        assertNotNull(requireRole, methodName + " 必须带 @RequireRole");
        assertEquals(List.of("ADMIN"), List.of(requireRole.value()));
    }
}
