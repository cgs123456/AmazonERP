package com.amz.controller;

import com.amz.aspect.RequireRoleAspect;
import com.amz.aspect.ShopIdGuardAspect;
import com.amz.interceptor.BaseAuthInterceptor;
import com.amz.model.KeywordRanking;
import com.amz.service.ListingMonitorService;
import com.amz.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Listing 写端点：店铺级角色在**真实 Web + AOP 链**上的端到端行为（P0-1 第二层）。
 * <p>
 * <b>与已有的 {@code ListingWriteEndpointGuardContractTest} 的分工</b>：那个反射契约测试
 * 只钉「注解声明在不在」；本测试跑真实链路——真实 JWT 签发、真实
 * {@link BaseAuthInterceptor}、真实 {@link RequireRoleAspect}/{@link ShopIdGuardAspect}
 * 代理，只 mock 业务 Service。它钉的是注解**接上之后判得对不对**，
 * 尤其是 §S22 的核心口径：**角色按本次操作真正面向的店铺判定，不按请求头**。
 * <p>
 * 钉住的场景（「店 A OPERATOR / 店 B VIEWER」的多店用户，两个切面共用
 * {@code ShopIdParameterResolver}，shopId 来自请求体——§S23 补的解析）：
 * <ol>
 *   <li>header 店 A + 请求体店 A → 放行（OPERATOR 写自己有权限的店）；</li>
 *   <li>header 店 A + 请求体店 B → **拒绝**：错配洞的正面证据。拿店 A 的
 *       OPERATOR 去写店 B，在「只看 header」的实现里会放行——这正是当年
 *       ShopIdGuardAspect 用参数而角色判定点曾用 header 时存在的不对称；</li>
 *   <li>店 B 是 VIEWER：直接以店 B 为目标 → 拒绝（店铺级角色生效，
 *       不是回落全局 OPERATOR 就放行）；</li>
 *   <li>被拒的请求不得触达 Service（verifyNoInteractions）。</li>
 * </ol>
 * <p>
 * 证据等级：E2（进程内真实链，不发外部请求）。
 */
@DisplayName("Listing 写端点：店铺级角色端到端（真实 JWT + 拦截器 + 双切面）")
class ListingShopScopedRoleWebMvcIntegrationTest {

    private static final long SHOP_A = 1001L;
    private static final long SHOP_B = 1002L;

    private JwtUtil jwtUtil;
    private ListingMonitorService listingMonitorService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secretKey", "test-secret-key-for-listing-role-chain");
        ReflectionTestUtils.setField(jwtUtil, "issuer", "amz-erp");
        ReflectionTestUtils.setField(jwtUtil, "audience", "amz-erp-client");
        ReflectionTestUtils.setField(jwtUtil, "expireTime", 86_400_000L);
        jwtUtil.init();

        listingMonitorService = Mockito.mock(ListingMonitorService.class);
        ListingMonitorController controller = new ListingMonitorController();
        ReflectionTestUtils.setField(controller, "listingMonitorService", listingMonitorService);

        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(controller);
        proxyFactory.addAspect(new RequireRoleAspect());
        proxyFactory.addAspect(new ShopIdGuardAspect());
        ListingMonitorController proxied = proxyFactory.getProxy();

        mockMvc = MockMvcBuilders.standaloneSetup(proxied)
                .addInterceptors(new BaseAuthInterceptor(jwtUtil))
                .build();
    }

    /** 全局 OPERATOR；店 A=OPERATOR、店 B=VIEWER（接通后的目标形态）。 */
    private String multiShopToken() {
        Map<Long, String> shopRoles = new LinkedHashMap<>();
        shopRoles.put(SHOP_A, "OPERATOR");
        shopRoles.put(SHOP_B, "VIEWER");
        return jwtUtil.createToken(7, List.of(SHOP_A, SHOP_B), "OPERATOR", shopRoles);
    }

    private static String rankingJson(long shopId) {
        return "{\"shopId\":" + shopId
                + ",\"asin\":\"B0TEST0001\",\"keyword\":\"garlic press\""
                + ",\"organicRank\":12,\"adRank\":3}";
    }

    @Test
    @DisplayName("header 店 A + 请求体店 A：店 A 是 OPERATOR → 放行并触达 Service")
    void operatorWritesOwnShopAccepted() throws Exception {
        when(listingMonitorService.saveRanking(any(KeywordRanking.class)))
                .thenReturn(new KeywordRanking());

        mockMvc.perform(post("/product/listing-monitor/ranking")
                        .header("token", multiShopToken())
                        .header("shopId", String.valueOf(SHOP_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rankingJson(SHOP_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(listingMonitorService).saveRanking(any(KeywordRanking.class));
    }

    /**
     * 错配洞的正面证据：header 店 A（过拦截器），请求体店 B。
     * 角色必须按**店 B**（VIEWER）判 → 拒绝。
     * 若实现按 header 店 A 判（OPERATOR），这条会放行——§S22 修的就是这个不对称。
     */
    @Test
    @DisplayName("header 店 A + 请求体店 B：按店 B 的 VIEWER 拒绝，不触达 Service")
    void headerShopRoleCannotAuthorizeDifferentTargetShop() throws Exception {
        mockMvc.perform(post("/product/listing-monitor/ranking")
                        .header("token", multiShopToken())
                        .header("shopId", String.valueOf(SHOP_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rankingJson(SHOP_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("无权限")));

        verifyNoInteractions(listingMonitorService);
    }

    @Test
    @DisplayName("直接以店 B 为目标：店铺级 VIEWER 拒绝（不回落全局 OPERATOR）")
    void shopViewerDeniedEvenWithGlobalOperator() throws Exception {
        mockMvc.perform(post("/product/listing-monitor/ranking")
                        .header("token", multiShopToken())
                        .header("shopId", String.valueOf(SHOP_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rankingJson(SHOP_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("无权限")));

        verifyNoInteractions(listingMonitorService);
    }

    /**
     * 反向对照：**没接店铺级角色之前**（老 token 无 shop_roles claim），
     * 全局 OPERATOR 写店 B 是放行的——这正是渐进发布「老 token 行为不变」的语义。
     * 钉住它，防止把「回落全局」误改成「拒绝」导致存量会话瞬间大面积 403。
     */
    @Test
    @DisplayName("老 token（无 shop_roles）写店 B：回落全局 OPERATOR → 放行")
    void legacyTokenStillUsesGlobalRole() throws Exception {
        String legacy = jwtUtil.createToken(7, List.of(SHOP_A, SHOP_B), "OPERATOR");
        when(listingMonitorService.saveRanking(any(KeywordRanking.class)))
                .thenReturn(new KeywordRanking());

        mockMvc.perform(post("/product/listing-monitor/ranking")
                        .header("token", legacy)
                        .header("shopId", String.valueOf(SHOP_B))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rankingJson(SHOP_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(listingMonitorService).saveRanking(any(KeywordRanking.class));
    }

    @Test
    @DisplayName("全局 VIEWER 写店 A：角色拒绝（店铺没配角色时按全局判）")
    void globalViewerDenied() throws Exception {
        String viewer = jwtUtil.createToken(7, List.of(SHOP_A, SHOP_B), "VIEWER");

        mockMvc.perform(post("/product/listing-monitor/ranking")
                        .header("token", viewer)
                        .header("shopId", String.valueOf(SHOP_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rankingJson(SHOP_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("无权限")));

        verifyNoInteractions(listingMonitorService);
    }

    /**
     * 请求体店铺不在授权列表时仍然被拒，且拒绝方是**租户切面**而非角色切面：
     * 该店没有配店铺级角色 → resolveRole 回落全局 OPERATOR → 角色判定**通过**，
     * 拦住它的是 ShopIdGuard 的授权列表校验（话术「不在授权范围」）。
     * 两个切面没有 @Order，执行先后不确定——本测试故意只断言「拒绝 + 不触达 Service」，
     * 不断言具体是哪个切面先说话，避免钉一个实现细节。
     */
    @Test
    @DisplayName("请求体店铺不在授权列表：拒绝且不触达 Service")
    void bodyShopOutsideGrantsRejected() throws Exception {
        String onlyA = jwtUtil.createToken(7, List.of(SHOP_A), "OPERATOR",
                Map.of(SHOP_A, "OPERATOR"));

        mockMvc.perform(post("/product/listing-monitor/ranking")
                        .header("token", onlyA)
                        .header("shopId", String.valueOf(SHOP_A))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rankingJson(2002L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(listingMonitorService);
    }
}
