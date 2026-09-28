package com.amz.controller;

import com.amz.annotation.RequireRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("财务 Controller 写接口角色守卫契约测试")
class FinanceControllerGuardTest {

    private static final List<Class<? extends Annotation>> WRITE_MAPPINGS = List.of(
            PostMapping.class, PutMapping.class, DeleteMapping.class, PatchMapping.class);

    @Test
    @DisplayName("所有财务写接口必须显式声明 OPERATOR/ADMIN，新增接口不得绕过")
    void everyFinanceWriteEndpointRequiresOperatorOrAdmin() throws Exception {
        List<Method> writeEndpoints = new ArrayList<>();
        List<Class<?>> controllers = scanControllers();

        assertFalse(controllers.isEmpty(), "必须扫描到财务 Controller，扫到 0 个会使测试假通过");
        for (Class<?> controller : controllers) {
            for (Method method : controller.getDeclaredMethods()) {
                for (Annotation annotation : method.getAnnotations()) {
                    if (WRITE_MAPPINGS.contains(annotation.annotationType())) {
                        writeEndpoints.add(method);
                        break;
                    }
                }
            }
        }

        assertFalse(writeEndpoints.isEmpty(), "必须扫描到财务写接口，扫到 0 个会使测试假通过");
        assertEquals(13, writeEndpoints.size(),
                "当前有 13 个财务写接口；新增接口必须同步声明 @RequireRole 并更新本断言");

        for (Method method : writeEndpoints) {
            RequireRole requireRole = method.getAnnotation(RequireRole.class);
            assertNotNull(requireRole,
                    "财务写接口 " + method.getDeclaringClass().getSimpleName() + "#" + method.getName()
                            + " 缺少 @RequireRole");
            assertArrayEquals(new String[]{"OPERATOR", "ADMIN"}, requireRole.value(),
                    "财务写接口 " + method.getName() + " 只应放行 OPERATOR/ADMIN");
        }
    }

    @Test
    @DisplayName("结算明细列表必须暴露 size/cursor 游标分页参数")
    void settlementListExposesCursorPagination() {
        Method method = Arrays.stream(SettlementController.class.getDeclaredMethods())
                .filter(candidate -> "list".equals(candidate.getName()))
                .findFirst()
                .orElseThrow();
        Set<String> requestParams = Arrays.stream(method.getParameterAnnotations())
                .flatMap(Arrays::stream)
                .filter(RequestParam.class::isInstance)
                .map(RequestParam.class::cast)
                .map(RequestParam::value)
                .collect(Collectors.toSet());

        assertTrue(requestParams.contains("size"), "结算明细列表缺少 size 分页参数");
        assertTrue(requestParams.contains("cursor"), "结算明细列表缺少 cursor 分页参数");
    }
    private static List<Class<?>> scanControllers() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> controllers = new ArrayList<>();
        for (BeanDefinition bean : scanner.findCandidateComponents("com.amz.controller")) {
            controllers.add(Class.forName(bean.getBeanClassName()));
        }
        controllers.sort((a, b) -> a.getName().compareTo(b.getName()));
        return controllers;
    }
}
