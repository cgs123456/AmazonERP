package com.amz.controller;

import com.amz.annotation.RequireRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多平台写端点的角色门禁契约（P0 第 4 批：MultiplatformController）。
 * <p>
 * <b>动因</b>：这个 Controller 的 17 个写端点此前**一个 {@code @RequireRole} 都没有**，
 * VIEWER（语义=只读）可以代表店铺对第三方平台做写操作——标记发货
 * （{@code POST /order/{id}/ship}）、以店铺身份回复买家消息
 * （{@code POST /message/{id}/reply}）、轮换第三方应用密钥
 * （{@code POST /oauth/app/{id}/rotate} 会把对方系统踢下线）、解绑平台账号。
 * 店员隔离是好的（{@code @ShopScoped} 覆盖绝大多数端点），缺的只是角色一层。
 * <p>
 * <b>豁免点在这里是一次性钉死的，不是每个端点现场判断</b>：机器端点没有"当前用户"可判，
 * 加{@code @RequireRole} 会直接把平台回调/外部集成打死——
 * <ul>
 *   <li>{@code receiveWebhook}：Temu/TikTok/Shein 回调入口，本就在
 *       {@code BaseAuthInterceptor} 白名单里（不带用户 JWT），鉴权 = 自身 HMAC 验签。
 *       加了角色注解等于要求平台带我们的用户 token，消息接收全断。
 *       见 {@code MultiplatformWebhookProcessingTest}。</li>
 *   <li>{@code generateToken}：开放 API，调用方是外部系统，凭 appKey+appSecret 换 token。</li>
 * </ul>
 * 把豁免写进测试（而不是散在注释里）是为了让"以后有人给这两个加门禁"必须先改测试——
 * 改测试的动作会促使他读这段说明。
 * <p>
 * <b>为什么用反射而不是 MockMvc</b>：{@code standaloneSetup} 不装载 AOP，
 * 切面注解在同一进程里不会被触发（§S23.4 记的教训）；反射直接问注解在不在。
 * "接上之后判得对不对"由 {@code ListingShopScopedRoleWebMvcIntegrationTest} 那类
 * 真实链测试负责，两者测的不是同一件事。
 */
@DisplayName("多平台写端点角色门禁：人操作端点必须锁，机器端点豁免")
class MultiplatformWriteEndpointGuardContractTest {

    /** HTTP 写方法注解（与其它模块的守卫测试同一口径）。 */
    private static final List<Class<? extends Annotation>> WRITE_MAPPINGS = List.of(
            org.springframework.web.bind.annotation.PostMapping.class,
            org.springframework.web.bind.annotation.PutMapping.class,
            org.springframework.web.bind.annotation.DeleteMapping.class,
            org.springframework.web.bind.annotation.PatchMapping.class);

    /**
     * 机器端点：没有"当前用户"可判角色，唯一鉴权是自带的（webhook=HMAC 验签、
     * oauth token=appKey+appSecret）。加 {@code @RequireRole} 会误伤真实调用方。
     */
    private static final Map<String, String> MACHINE_ENDPOINTS = Map.of(
            "receiveWebhook", "平台回调入口：在 BaseAuthInterceptor 白名单内，不带用户 JWT，鉴权靠 HMAC 验签",
            "generateToken", "开放 API：外部系统凭 appKey+appSecret 换 token，调用方不是本系统前端用户");

    @Test
    @DisplayName("除豁免的机器端点外，每个写端点都有 @RequireRole")
    void everyHumanWriteEndpointRequiresRole() {
        List<String> unguarded = new ArrayList<>();
        for (Method m : MultiplatformController.class.getDeclaredMethods()) {
            if (!isWriteEndpoint(m)) {
                continue;
            }
            if (MACHINE_ENDPOINTS.containsKey(m.getName())) {
                assertMachineEndpointReallyIsMachineOnly(m);
                continue;
            }
            if (!m.isAnnotationPresent(RequireRole.class)) {
                unguarded.add(m.getName() + " " + Arrays.toString(writeMappings(m)));
            }
        }
        assertTrue(unguarded.isEmpty(),
                "MultiplatformController 存在未声明 @RequireRole 的人操作写端点（VIEWER 可写）："
                        + unguarded + "。补 @RequireRole({\"OPERATOR\",\"ADMIN\"})；"
                        + "若坚持不加，请把方法与理由加进 MACHINE_ENDPOINTS 或本测试的豁免清单。");
    }

    @Test
    @DisplayName("两个机器端点确实没有 @RequireRole（豁免没被误删）")
    void machineEndpointsStayExempt() {
        for (Map.Entry<String, String> e : MACHINE_ENDPOINTS.entrySet()) {
            Method m = findMethod(e.getKey());
            assertTrue(!m.isAnnotationPresent(RequireRole.class),
                    e.getValue() + "；现在它带了 @RequireRole，说明豁免状态变了——"
                            + "若这是有意的，请连 MACHINE_ENDPOINTS 的注释一起更新。");
        }
    }

    private static Method findMethod(String name) {
        for (Method candidate : MultiplatformController.class.getDeclaredMethods()) {
            if (candidate.getName().equals(name)) {
                return candidate;
            }
        }
        throw new IllegalStateException("豁免清单里的方法不存在了：" + name);
    }

    /**
     * 豁免必须经得起追问：机器端点**不应**带 {@code @RequireRole}，但应当保持
     * {@code @ShopScoped}（除 webhook 外它也没有 shopId 参数）。这里只钉
     * "豁免端点本身没被角色注解污染"这一件事，其余形态由别的测试守。
     */
    private static void assertMachineEndpointReallyIsMachineOnly(Method m) {
        assertTrue(!m.isAnnotationPresent(RequireRole.class),
                m.getName() + " 在豁免清单里却带了 @RequireRole——豁免与实现不一致，"
                        + "要么把注解去掉，要么从豁免清单移到人操作端点一起加锁");
    }

    private static boolean isWriteEndpoint(Method m) {
        return writeMappings(m).length > 0;
    }

    private static Class<?>[] writeMappings(Method m) {
        return WRITE_MAPPINGS.stream().filter(m::isAnnotationPresent).toArray(Class[]::new);
    }
}
