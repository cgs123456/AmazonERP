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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客服写端点的角色门禁契约（P0 加锁）。
 * <p>
 * <b>存在动因</b>：这两个 Controller 的写端点此前<b>几乎没有</b> {@code @RequireRole}——
 * {@code CustomerController} 4 个写端点只有 closeTicket 有，而当时唯一那行 {@code @ShopScoped}
 * 还误挂到了 closeTicket 上（它没有 shopId 参数，切面解析不到直接放行），
 * 真正带 {@code @PathVariable Long shopId} 的 {@code solicitReviews} 反而裸奔。
 * 结果是 VIEWER 与 OPERATOR 在这些端点上等权：能建单、能回复、能索评、能改商品主数据。
 * <p>
 * <b>为什么用反射而不是 MockMvc</b>：{@code standaloneSetup} 不装载 AOP，
 * {@code @RequireRole} 这类切面注解在同一进程里根本不会被触发——用 MockMvc 断言「VIEWER 被拒」
 * 会得到一个永远通过的空测试。反射直接检查注解存在性，朴实但没有假阳性。
 * <p>
 * <b>本测试只钉「注解在不在」，不钉「切面判得对不对」</b>：后者的守卫在
 * {@code ShopIdBodyResolutionContractTest}（@ShopScoped/@RequireRole 的解析与拒绝行为）
 * 与各 service 的 TenantGuardTest（行级归属）。三层各管一段。
 */
@DisplayName("客服写端点角色门禁契约：每个写端点都必须显式声明 @RequireRole 与 @RequireLog")
class CustomerWriteEndpointGuardContractTest {

    /** 写端点判定：HTTP 语义为 POST/PUT/DELETE/PATCH 的方法。 */
    private static final List<Class<? extends java.lang.annotation.Annotation>> WRITE_MAPPINGS = List.of(
            PostMapping.class, PutMapping.class, DeleteMapping.class, PatchMapping.class);

    @Test
    @DisplayName("CustomerController：所有写端点都声明了 @RequireRole")
    void customerControllerWriteEndpointsRequireRole() {
        assertAllWriteEndpointsGuarded(CustomerController.class);
    }

    @Test
    @DisplayName("CustomerEmailController：所有写端点都声明了 @RequireRole")
    void customerEmailControllerWriteEndpointsRequireRole() {
        assertAllWriteEndpointsGuarded(CustomerEmailController.class);
    }

    @Test
    @DisplayName("工单建单/回复/索评已加锁（曾经裸奔的三个端点）")
    void previouslyUnguardedTicketEndpointsAreNowGuarded() throws Exception {
        assertGuarded(CustomerController.class.getMethod("receiveMessage", com.amz.model.CustomerTicket.class),
                "POST /customer/ticket 建单");
        assertGuarded(CustomerController.class.getMethod("replyTicket", Long.class, String.class),
                "POST /customer/ticket/{id}/reply 回复");
        assertGuarded(CustomerController.class.getMethod("solicitReviews", Long.class),
                "POST /customer/review/solicit/{shopId} 索评");
    }

    @Test
    @DisplayName("索评端点必须同时有 @ShopScoped：它带 shopId 路径参数，租户校验能真的生效")
    void solicitReviewsIsShopScoped() throws Exception {
        Method m = CustomerController.class.getMethod("solicitReviews", Long.class);
        assertTrue(m.isAnnotationPresent(ShopScoped.class),
                "solicitReviews 有 @PathVariable Long shopId，必须声明 @ShopScoped——"
                        + "此前那行注解误挂在没有 shopId 参数的 closeTicket 上，等于两个端点都没被校验");
    }

    @Test
    @DisplayName("closeTicket 不应有 @ShopScoped：它没有 shopId 参数，注解只会造成「已校验」的错觉")
    void closeTicketIsNotShopScoped() throws Exception {
        Method m = CustomerController.class.getMethod("closeTicket", Long.class);
        assertFalse(m.isAnnotationPresent(ShopScoped.class),
                "closeTicket 的 shopId 只在工单行上（不在任何参数里），@ShopScoped 解析不到参数会直接放行；"
                        + "留着它会让下一个读代码的人以为这里已经做了店铺校验。"
                        + "归属由 CustomerServiceImpl.requireShopOnRow 按行判定。");
    }

    private static void assertAllWriteEndpointsGuarded(Class<?> controller) {
        List<String> unguarded = new ArrayList<>();
        for (Method m : controller.getDeclaredMethods()) {
            if (!isWriteEndpoint(m)) {
                continue;
            }
            if (!m.isAnnotationPresent(RequireRole.class)) {
                unguarded.add(m.getName() + " " + Arrays.toString(writeMappings(m)));
            }
        }
        assertTrue(unguarded.isEmpty(),
                controller.getSimpleName() + " 存在未声明 @RequireRole 的写端点（VIEWER 可写）：" + unguarded
                        + "。补 @RequireRole({\"OPERATOR\",\"ADMIN\"})，或在本测试里写明它为何是公开写端点。");
    }

    private static void assertGuarded(Method m, String label) {
        assertTrue(m.isAnnotationPresent(RequireRole.class), label + " 缺少 @RequireRole");
        assertTrue(m.isAnnotationPresent(OperLog.class),
                label + " 缺少 @OperLog：写操作不进审计日志，事后无法回溯谁改了什么");
    }

    private static boolean isWriteEndpoint(Method m) {
        return writeMappings(m).length > 0;
    }

    private static Class<?>[] writeMappings(Method m) {
        return WRITE_MAPPINGS.stream().filter(m::isAnnotationPresent).toArray(Class[]::new);
    }
}
