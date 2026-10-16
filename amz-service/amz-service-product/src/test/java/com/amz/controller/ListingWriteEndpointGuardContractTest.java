package com.amz.controller;

import com.amz.annotation.OperLog;
import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Listing / 商品主数据写端点的角色门禁契约（P0 加锁）。
 * <p>
 * <b>存在动因</b>：这些端点的 shopId 大多只在<b>请求体</b>里
 * （{@code KeywordRanking}/{@code CompetitorMonitor}/{@code BuyBox}/{@code AmzProduct}），
 * 而 {@code ShopIdGuardAspect} 原实现只认 {@code @PathVariable}/{@code @RequestParam}——
 * 解析不到参数就按「未找到 shopId 参数 → 兼容放行」跳过，而 amz-service-product 的
 * service 层又完全没有行级归属校验（该模块 {@code UserContext} 零引用）。
 * <b>两层防线同时让开</b>，于是任何已登录用户（含 VIEWER）都能往任意店铺写入。
 * <p>
 * 现在两条都补上了：切面能读请求体（{@code ShopIdBodyResolutionContractTest} 钉住其行为），
 * 本测试钉住这里的注解确实声明了。
 * <p>
 * <b>为什么用反射而不是 MockMvc</b>：{@code standaloneSetup} 不装载 AOP，
 * 切面注解在同一进程里不会被触发，用 MockMvc 断言「VIEWER 被拒」会得到一个永远通过的空测试。
 */
@DisplayName("Listing/商品主数据写端点角色门禁契约")
class ListingWriteEndpointGuardContractTest {

    private static final List<Class<? extends Annotation>> WRITE_MAPPINGS = List.of(
            PostMapping.class, PutMapping.class, DeleteMapping.class, PatchMapping.class);

    @Test
    @DisplayName("ListingMonitorController：所有写端点都声明了 @RequireRole")
    void listingMonitorWriteEndpointsRequireRole() {
        assertAllWriteEndpointsGuarded(ListingMonitorController.class);
    }

    @Test
    @DisplayName("ProductMasterController：所有写端点都声明了 @RequireRole")
    void productMasterWriteEndpointsRequireRole() {
        assertAllWriteEndpointsGuarded(ProductMasterController.class);
    }

    @Test
    @DisplayName("请求体携带 shopId 的写端点必须同时有 @ShopScoped（届时租户校验才真正生效）")
    void bodyShopIdWriteEndpointsAreShopScoped() {
        assertShopScoped(ListingMonitorController.class, "saveRanking", new Class<?>[]{com.amz.model.KeywordRanking.class});
        assertShopScoped(ListingMonitorController.class, "saveCompetitor", new Class<?>[]{com.amz.model.CompetitorMonitor.class});
        assertShopScoped(ListingMonitorController.class, "saveBuyBox", new Class<?>[]{com.amz.model.BuyBox.class});
        assertShopScoped(ProductMasterController.class, "create", new Class<?>[]{com.amz.model.AmzProduct.class});
    }

    @Test
    @DisplayName("这些写端点的 shopId 确实来自请求体——这是它们此前能被绕过的原因")
    void writeEndpointsTakeShopIdFromBody() {
        List<String> notFromBody = new ArrayList<>();
        for (Class<?> c : List.of(ListingMonitorController.class, ProductMasterController.class)) {
            for (Method m : c.getDeclaredMethods()) {
                if (!isWriteEndpoint(m)) {
                    continue;
                }
                // @RequestBody 挂在**参数**上，不是方法上
                boolean bodyHasShopId = false;
                for (Parameter p : m.getParameters()) {
                    if (p.isAnnotationPresent(RequestBody.class)) {
                        bodyHasShopId = hasShopIdField(p.getType());
                    }
                }
                if (bodyHasShopId) {
                    // 只要从请求体取 shopId，就必须有 @ShopScoped 让切面能校验到它
                    notFromBody.add(m.getName());
                    assertTrue(m.isAnnotationPresent(ShopScoped.class),
                            c.getSimpleName() + "." + m.getName() + " 的 shopId 在请求体里（切面只能靠 @ShopScoped 触发），"
                                    + "缺 @ShopScoped 等于没有租户校验");
                }
            }
        }
        assertTrue(!notFromBody.isEmpty(),
                "本测试的目的是守卫「shopId 在请求体」这类端点；若一个都找不到，说明端点形状变了，"
                        + "应重新核对本测试是否还在测真正的东西");
    }

    private static boolean hasShopIdField(Class<?> type) {
        try {
            type.getDeclaredField("shopId");
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    private static void assertShopScoped(Class<?> c, String name, Class<?>[] params) {
        Method m = Arrays.stream(c.getDeclaredMethods())
                .filter(x -> x.getName().equals(name) && Arrays.equals(x.getParameterTypes(), params))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("方法不存在：" + name));
        assertTrue(m.isAnnotationPresent(ShopScoped.class),
                c.getSimpleName() + "." + name + " 缺 @ShopScoped");
        assertTrue(m.isAnnotationPresent(RequireRole.class),
                c.getSimpleName() + "." + name + " 缺 @RequireRole");
    }

    private static void assertAllWriteEndpointsGuarded(Class<?> controller) {
        List<String> unguarded = new ArrayList<>();
        for (Method m : controller.getDeclaredMethods()) {
            if (!isWriteEndpoint(m) || m.isAnnotationPresent(RequireRole.class)) {
                continue;
            }
            unguarded.add(m.getName() + " " + Arrays.toString(writeMappings(m)));
        }
        assertTrue(unguarded.isEmpty(),
                controller.getSimpleName() + " 存在未声明 @RequireRole 的写端点（VIEWER 可写）：" + unguarded
                        + "。补 @RequireRole({\"OPERATOR\",\"ADMIN\"})，或在本测试里写明它为何是公开写端点。");
    }

    private static boolean isWriteEndpoint(Method m) {
        return writeMappings(m).length > 0;
    }

    private static Class<?>[] writeMappings(Method m) {
        return WRITE_MAPPINGS.stream().filter(m::isAnnotationPresent).toArray(Class[]::new);
    }
}
