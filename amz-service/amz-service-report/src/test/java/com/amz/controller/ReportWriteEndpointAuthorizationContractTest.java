package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.context.UserContext;
import com.amz.aspect.RequireRoleAspect;
import com.amz.model.ProfitDetail;
import com.amz.result.Result;
import com.amz.service.ReportUpgradeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 报表写接口的角色授权契约。
 * <p>
 * 动因（功能覆盖清点 N1 的角色层残留）：report 模块此前一处 {@code @RequireRole} 都没有，
 * 任何登录成功的 VIEWER 都能调这 7 个写入口改别人的报表数据。上一轮补的是「店铺归属」
 * （{@code ReportTenantGuard}，因为 {@code @ShopScoped} 对 shopId 只在请求体里的端点空转），
 * 这一轮补的是「什么角色可以写」。
 * <p>
 * 光有反射检查不够：注解写错位置、切面没生效，反射测试照样绿。所以同时用
 * {@link AspectJProxyFactory} 把真切面套到真 controller 上，验 VIEWER 被拒、OPERATOR 放行、
 * 以及「受信服务身份先放行」这条豁免真的存在（它是内部调用不被打断的原因）。
 */
@DisplayName("报表写接口角色授权契约")
@ExtendWith(MockitoExtension.class)
class ReportWriteEndpointAuthorizationContractTest {

    private static final List<Class<?>> REPORT_CONTROLLERS = List.of(
            ReportController.class, ReportUpgradeController.class, RealtimeProfitController.class);

    private static final List<Class<? extends Annotation>> WRITE_ANNOTATIONS = List.of(
            PostMapping.class, PutMapping.class, DeleteMapping.class, PatchMapping.class);

    @Mock
    private ReportUpgradeService reportUpgradeService;

    @InjectMocks
    private ReportUpgradeController controller;

    @BeforeEach
    void setUp() {
        UserContext.clear();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static boolean isWriteEndpoint(Method method) {
        return WRITE_ANNOTATIONS.stream().anyMatch(method::isAnnotationPresent);
    }

    private static int writeEndpointCount() {
        int count = 0;
        for (Class<?> controllerClass : REPORT_CONTROLLERS) {
            for (Method method : controllerClass.getDeclaredMethods()) {
                if (isWriteEndpoint(method)) {
                    count++;
                }
            }
        }
        return count;
    }

    @Test
    @DisplayName("每个写接口都显式声明 @RequireRole 与 @ShopScoped，一个都不放过")
    void everyWriteEndpointDeclaresBothGuards() {
        int seen = 0;
        for (Class<?> controllerClass : REPORT_CONTROLLERS) {
            for (Method method : controllerClass.getDeclaredMethods()) {
                if (!isWriteEndpoint(method)) {
                    continue;
                }
                seen++;
                assertNotNull(method.getAnnotation(RequireRole.class),
                        controllerClass.getSimpleName() + "." + method.getName() + " 是写接口，必须声明 @RequireRole");
                assertNotNull(method.getAnnotation(ShopScoped.class),
                        controllerClass.getSimpleName() + "." + method.getName() + " 是写接口，必须声明 @ShopScoped");
            }
        }
        // 数量门禁：新增写接口时要么带上两道守卫，要么显式改这个数并说明理由
        assertEquals(7, seen, "报表写接口数量变了：" + seen);
    }

    @Test
    @DisplayName("允许角色只有 OPERATOR/ADMIN，不放 VIEWER 进来")
    void writeEndpointRolesFollowLeastPrivilege() {
        for (Class<?> controllerClass : REPORT_CONTROLLERS) {
            for (Method method : controllerClass.getDeclaredMethods()) {
                if (!isWriteEndpoint(method)) {
                    continue;
                }
                RequireRole role = method.getAnnotation(RequireRole.class);
                assertNotNull(role, controllerClass.getSimpleName() + "." + method.getName() + " 缺少 @RequireRole");
                Set<String> allowed = Set.of(role.value());
                assertTrue(allowed.size() > 0, "角色列表不能为空（空列表等于谁都不放行）");
                assertTrue(Set.of("OPERATOR", "ADMIN").containsAll(allowed),
                        controllerClass.getSimpleName() + "." + method.getName() + " 出现了超出 OPERATOR/ADMIN 的角色："
                                + allowed);
            }
        }
    }

    @Test
    @DisplayName("VIEWER 调写接口：切面直接拒，服务层一次都没被调用")
    void viewerIsRejectedByTheAspectItself() {
        UserContext.setUserId(7);
        UserContext.setRole("VIEWER");
        UserContext.setShops(List.of(1L));
        ReportUpgradeController proxy = proxyWithRoleAspect();
        ProfitDetail detail = new ProfitDetail();
        detail.setShopId(1L);

        Result<ProfitDetail> result = proxy.saveProfit(detail);

        assertEquals(400, result.getCode(), "切面拒绝要返回 code=400，不能是 200");
        assertTrue(String.valueOf(result.getMessage()).contains("需要角色"), String.valueOf(result.getMessage()));
        verifyNoInteractions(reportUpgradeService);
    }

    @Test
    @DisplayName("OPERATOR 调同一个方法：放行到服务层")
    void operatorPassesThroughTheAspect() {
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(1L));
        ProfitDetail detail = new ProfitDetail();
        detail.setShopId(1L);

        proxyWithRoleAspect().saveProfit(detail);

        verify(reportUpgradeService).saveProfitDetail(detail);
    }

    @Test
    @DisplayName("受信服务身份先放行：内部调用不会被打断（这是豁免，必须留在测试里）")
    void trustedServiceIdentityBypassesRoleCheck() {
        UserContext.setRole("VIEWER");
        UserContext.setInternalService("amz-service-spapi");
        ProfitDetail detail = new ProfitDetail();
        detail.setShopId(1L);

        proxyWithRoleAspect().saveProfit(detail);

        verify(reportUpgradeService).saveProfitDetail(detail);
        assertTrue(Arrays.asList("OPERATOR", "ADMIN").contains(UserContext.getRole()) == false,
                "这条用例的前提是角色本身不够格");
    }

    private ReportUpgradeController proxyWithRoleAspect() {
        AspectJProxyFactory factory = new AspectJProxyFactory(controller);
        factory.addAspect(new RequireRoleAspect());
        factory.setProxyTargetClass(true);
        return factory.getProxy();
    }
}
