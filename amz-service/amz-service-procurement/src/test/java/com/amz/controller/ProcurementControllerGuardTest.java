package com.amz.controller;

import com.amz.annotation.RequireRole;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RestController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("采购 Controller 写接口角色守卫契约测试")
class ProcurementControllerGuardTest {

    private static final List<Class<? extends Annotation>> WRITE_MAPPINGS = List.of(
            PostMapping.class, PutMapping.class, DeleteMapping.class, PatchMapping.class);

    /**
     * 采购域的全部 Controller。§S32 把 FBA 货件 / 库存批次端点拆到了
     * {@link FbaShipmentController}，但本测试仍只扫 {@code ProcurementController}，
     * 于是「24 个写接口」的断言在拆分当天就红了——**并且一直红着没人修**。
     *
     * <p>判据改为「整个采购域」：新增 Controller 必须同步加进本列表，
     * 否则它里面的写接口会完全绕过角色检查（那正是本测试存在的意义）。
     * 数量断言保留，但它现在是**下限**而不是精确值——精确值会让每次新增端点
     * 都要改测试，而改测试恰恰是最容易被顺手放宽的一步。
     */
    /**
     * 采购域全部 Controller，**从类路径扫描**而不是手工列。
     *
     * <p>手工列表的失败模式本轮实测过：§S32 拆出 {@code FbaShipmentController} 后，
     * 本测试仍只扫 {@code ProcurementController}，9 个写接口无人检查，
     * 且断言写死 24 导致测试从拆分当天一直红着。
     * 扫描则让「新增 Controller」自动进入检查范围，不需要有人记得改测试。
     * 与 {@code LogisticsControllerGuardTest#scanControllers} 同一做法。
     */
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

    /** 每个 Controller 的写接口数量下限。新增端点只会让它变大。 */
    private static final int MIN_WRITE_ENDPOINTS = 24;

    @Test
    @DisplayName("采购域所有写接口必须显式声明 OPERATOR/ADMIN，新增 Controller 不得绕过")
    void everyWriteEndpointRequiresOperatorOrAdmin() throws Exception {
        List<Method> writeEndpoints = new ArrayList<>();
        List<Class<?>> controllers = scanControllers();
        assertFalse(controllers.isEmpty(), "必须扫描到采购 Controller，扫到 0 个会使测试假通过");
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

        assertFalse(writeEndpoints.isEmpty(), "反射必须扫到写接口，扫到 0 个会使测试假通过");
        assertTrue(writeEndpoints.size() >= MIN_WRITE_ENDPOINTS,
                "采购域只扫到 " + writeEndpoints.size() + " 个写接口，少于下限 "
                        + MIN_WRITE_ENDPOINTS + "——要么有 Controller 没登记进 "
                        + "PROCUREMENT_CONTROLLERS，要么扫描逻辑坏了");

        for (Method method : writeEndpoints) {
            RequireRole requireRole = method.getAnnotation(RequireRole.class);
            assertNotNull(requireRole, "写接口 " + method.getName() + " 缺少 @RequireRole");
            // 允许两种：OPERATOR/ADMIN（常规写），或 ADMIN-only（资金类，如取消付款）。
            // 判据是「不得放行 VIEWER」而不是「必须恰好两个角色」——
            // 后者会把更严格的 ADMIN-only 误判成违规，逼着人放宽权限去迎合测试。
            List<String> roles = Arrays.asList(requireRole.value());
            assertFalse(roles.contains("VIEWER"),
                    "写接口 " + method.getName() + " 不得放行 VIEWER：" + roles);
            assertTrue(roles.equals(List.of("OPERATOR", "ADMIN")) || roles.equals(List.of("ADMIN")),
                    "写接口 " + method.getName() + " 的角色必须是 [OPERATOR, ADMIN] 或 [ADMIN]，实际：" + roles);
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
        // §S32 把批次端点搬到 FbaShipmentController；判据跟着方法走，不能跟着旧文件走
        Method method = Arrays.stream(FbaShipmentController.class.getDeclaredMethods())
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
        // §S32 同上：批次成本聚合已搬到 FbaShipmentController
        Method method = Arrays.stream(FbaShipmentController.class.getDeclaredMethods())
                .filter(candidate -> "batchCostSummary".equals(candidate.getName()))
                .findFirst()
                .orElseThrow();
        GetMapping mapping = method.getAnnotation(GetMapping.class);

        assertNotNull(mapping, "批次成本聚合接口缺少 @GetMapping");
        assertTrue(Arrays.asList(mapping.value()).contains("/batch/cost-summary/{shopId}"),
                "批次成本聚合接口路径不符合跨服务契约：" + Arrays.toString(mapping.value()));
    }
}