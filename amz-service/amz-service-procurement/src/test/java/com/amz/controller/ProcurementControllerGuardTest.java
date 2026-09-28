package com.amz.controller;

import com.amz.annotation.RequireRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

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

@DisplayName("采购 Controller 写接口角色守卫契约测试")
class ProcurementControllerGuardTest {

    private static final List<Class<? extends Annotation>> WRITE_MAPPINGS = List.of(
            PostMapping.class, PutMapping.class, DeleteMapping.class, PatchMapping.class);

    @Test
    @DisplayName("所有写接口必须显式声明 OPERATOR/ADMIN，新增接口不得绕过")
    void everyWriteEndpointRequiresOperatorOrAdmin() {
        List<Method> writeEndpoints = new ArrayList<>();
        for (Method method : ProcurementController.class.getDeclaredMethods()) {
            for (Annotation annotation : method.getAnnotations()) {
                if (WRITE_MAPPINGS.contains(annotation.annotationType())) {
                    writeEndpoints.add(method);
                    break;
                }
            }
        }

        assertFalse(writeEndpoints.isEmpty(), "反射必须扫到写接口，扫到 0 个会使测试假通过");
        assertEquals(22, writeEndpoints.size(),
                "当前有 22 个写接口；新增接口必须同步声明 @RequireRole 并更新本断言");

        for (Method method : writeEndpoints) {
            RequireRole requireRole = method.getAnnotation(RequireRole.class);
            assertNotNull(requireRole, "写接口 " + method.getName() + " 缺少 @RequireRole");
            assertArrayEquals(new String[]{"OPERATOR", "ADMIN"}, requireRole.value(),
                    "写接口 " + method.getName() + " 只应放行 OPERATOR/ADMIN");
        }
    }

    @Test
    @DisplayName("采购计划列表必须暴露 size/cursor 游标分页参数")
    void purchasePlanListExposesCursorPagination() {
        Method method = Arrays.stream(ProcurementController.class.getDeclaredMethods())
                .filter(candidate -> "listPlans".equals(candidate.getName()))
                .findFirst()
                .orElseThrow();
        Set<String> requestParams = Arrays.stream(method.getParameterAnnotations())
                .flatMap(Arrays::stream)
                .filter(RequestParam.class::isInstance)
                .map(RequestParam.class::cast)
                .map(RequestParam::value)
                .collect(Collectors.toSet());

        assertTrue(requestParams.contains("size"), "采购计划列表缺少 size 分页参数");
        assertTrue(requestParams.contains("cursor"), "采购计划列表缺少 cursor 分页参数");
    }

    @Test
    @DisplayName("库存批次列表必须暴露 size/cursor 游标分页参数")
    void batchListExposesCursorPagination() {
        Method method = Arrays.stream(ProcurementController.class.getDeclaredMethods())
                .filter(candidate -> "listBatches".equals(candidate.getName()))
                .findFirst()
                .orElseThrow();
        Set<String> requestParams = Arrays.stream(method.getParameterAnnotations())
                .flatMap(Arrays::stream)
                .filter(RequestParam.class::isInstance)
                .map(RequestParam.class::cast)
                .map(RequestParam::value)
                .collect(Collectors.toSet());

        assertTrue(requestParams.contains("size"), "库存批次列表缺少 size 分页参数");
        assertTrue(requestParams.contains("cursor"), "库存批次列表缺少 cursor 分页参数");
    }

    @Test
    @DisplayName("供应商列表必须暴露 status/keyword/size/cursor 查询参数")
    void supplierListExposesFiltersAndCursorPagination() {
        Method method = Arrays.stream(ProcurementController.class.getDeclaredMethods())
                .filter(candidate -> "listSuppliers".equals(candidate.getName()))
                .findFirst()
                .orElseThrow();
        Set<String> requestParams = Arrays.stream(method.getParameterAnnotations())
                .flatMap(Arrays::stream)
                .filter(RequestParam.class::isInstance)
                .map(RequestParam.class::cast)
                .map(RequestParam::value)
                .collect(Collectors.toSet());

        assertTrue(requestParams.contains("status"), "供应商列表缺少 status 过滤参数");
        assertTrue(requestParams.contains("keyword"), "供应商列表缺少 keyword 模糊查询参数");
        assertTrue(requestParams.contains("size"), "供应商列表缺少 size 分页参数");
        assertTrue(requestParams.contains("cursor"), "供应商列表缺少 cursor 分页参数");
    }
    @Test
    @DisplayName("采购成本聚合接口必须提供独立 GET 端点，供财务按 SKU 聚合读取")
    void batchCostSummaryEndpointExists() {
        Method method = Arrays.stream(ProcurementController.class.getDeclaredMethods())
                .filter(candidate -> "batchCostSummary".equals(candidate.getName()))
                .findFirst()
                .orElseThrow();
        GetMapping mapping = method.getAnnotation(GetMapping.class);

        assertNotNull(mapping, "批次成本聚合接口缺少 @GetMapping");
        assertTrue(Arrays.asList(mapping.value()).contains("/batch/cost-summary/{shopId}"),
                "批次成本聚合接口路径不符合跨服务契约：" + Arrays.toString(mapping.value()));
    }
}