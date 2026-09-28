package com.amz.controller;

import com.amz.annotation.RequireRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("广告写接口角色授权契约")
class AdWriteEndpointAuthorizationContractTest {

    private static final List<Class<?>> AD_CONTROLLERS = List.of(
            AdController.class,
            AdCampaignExtController.class,
            AdCreativeController.class,
            AdTargetingController.class,
            SearchTermController.class
    );

    @Test
    @DisplayName("所有广告写接口必须显式声明角色守卫")
    void everyWriteEndpointDeclaresRoleGuard() {
        for (Class<?> controller : AD_CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!isWriteEndpoint(method)) {
                    continue;
                }
                RequireRole role = method.getAnnotation(RequireRole.class);
                assertNotNull(role,
                        controller.getSimpleName() + "." + method.getName() + " 是写接口，必须声明 @RequireRole");
            }
        }
    }

    @Test
    @DisplayName("普通广告写接口仅允许 OPERATOR/ADMIN，全店铺同步仅允许 ADMIN")
    void writeEndpointRolesFollowLeastPrivilege() {
        for (Class<?> controller : AD_CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!isWriteEndpoint(method)) {
                    continue;
                }
                RequireRole role = method.getAnnotation(RequireRole.class);
                assertNotNull(role, controller.getSimpleName() + "." + method.getName() + " 缺少 @RequireRole");
                Set<String> allowed = Set.of(role.value());
                if ("syncAllReports".equals(method.getName())) {
                    assertEquals(Set.of("ADMIN"), allowed,
                            "全店铺同步只能由 ADMIN 执行");
                } else {
                    assertTrue(allowed.containsAll(Set.of("OPERATOR", "ADMIN")),
                            controller.getSimpleName() + "." + method.getName()
                                    + " 应允许 OPERATOR/ADMIN，实际=" + allowed);
                }
            }
        }
    }

    private static boolean isWriteEndpoint(Method method) {
        return Arrays.stream(method.getAnnotations())
                .map(Annotation::annotationType)
                .anyMatch(type -> type == PostMapping.class
                        || type == PutMapping.class
                        || type == DeleteMapping.class
                        || type == PatchMapping.class);
    }
}