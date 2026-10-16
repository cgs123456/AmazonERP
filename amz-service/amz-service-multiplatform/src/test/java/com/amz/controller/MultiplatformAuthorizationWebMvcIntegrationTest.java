package com.amz.controller;

import com.amz.aspect.RequireRoleAspect;
import com.amz.interceptor.BaseAuthInterceptor;
import com.amz.model.UnifiedOrder;
import com.amz.service.impl.MultiplatformServiceImpl;
import com.amz.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 多平台写端点：真实 MVC + 真实切面链（P0 第 4 批 MultiplatformController）。
 * <p>
 * 与 {@code MultiplatformWriteEndpointGuardContractTest} 的分工同其它模块：
 * 契约测试钉注解在不在，这里钉**接上之后判得对不对**，并且——本次最重要的——
 * 钉住**豁免没被误伤**：
 * <ul>
 *   <li>VIEWER 打 {@code /order/{id}/ship}（此前本模块最危险的端点：可标任意平台
 *       订单已发货）→ 被角色切面拒绝，Service 零交互；</li>
 *   <li>OPERATOR 可打 → 放行并触达 Service；</li>
 *   <li>**webhook 豁免仍成立**：这是本轮加锁的验收点——平台回调不带用户 JWT，
 *       一旦豁免失效，多平台消息接收会整体失效（且没有任何角色测试会红，因为
 *       真实调用方是 Temu/TikTok/Shein 而非测试里的用户）。这里用「带合法 token 的
 *       webhook 走真实链 + 不被角色门禁拦」间接钉住；免 JWT 那条由
 *       {@code MultiplatformWebhookProcessingTest} 覆盖。</li>
 * </ul>
 * <p>
 * 证据等级：E2（进程内真实链，不发外部平台请求——Service 全 mock）。
 */
@DisplayName("多平台写端点：真实 JWT + 拦截器 + 切面链")
class MultiplatformAuthorizationWebMvcIntegrationTest {

    private static final long SHOP = 1001L;

    private JwtUtil jwtUtil;
    private MultiplatformServiceImpl service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secretKey", "test-secret-key-for-mp-auth-chain");
        ReflectionTestUtils.setField(jwtUtil, "issuer", "amz-erp");
        ReflectionTestUtils.setField(jwtUtil, "audience", "amz-erp-client");
        ReflectionTestUtils.setField(jwtUtil, "expireTime", 86_400_000L);
        jwtUtil.init();

        service = Mockito.mock(MultiplatformServiceImpl.class);
        MultiplatformController controller = new MultiplatformController();
        ReflectionTestUtils.setField(controller, "multiplatformService", service);

        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(controller);
        proxyFactory.addAspect(new RequireRoleAspect());
        MultiplatformController proxied = proxyFactory.getProxy();

        mockMvc = MockMvcBuilders.standaloneSetup(proxied)
                .addInterceptors(new BaseAuthInterceptor(jwtUtil))
                .build();
    }

    private String token(String role) {
        return jwtUtil.createToken(7, List.of(SHOP), role);
    }

    @Test
    @DisplayName("VIEWER 不能把平台订单标记为已发货，且不触达 Service")
    void viewerCannotMarkShipped() throws Exception {
        mockMvc.perform(post("/multiplatform/order/55/ship")
                        .param("trackingNo", "SF1234567890")
                        .header("token", token("VIEWER"))
                        .header("shopId", String.valueOf(SHOP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("无权限")));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("OPERATOR 可以标记发货并触达 Service")
    void operatorCanMarkShipped() throws Exception {
        when(service.markShipped(anyLong(), anyString())).thenReturn(true);

        mockMvc.perform(post("/multiplatform/order/55/ship")
                        .param("trackingNo", "SF1234567890")
                        .header("token", token("OPERATOR"))
                        .header("shopId", String.valueOf(SHOP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(service).markShipped(55L, "SF1234567890");
    }

    @Test
    @DisplayName("VIEWER 不能解绑平台账号")
    void viewerCannotDeleteAccount() throws Exception {
        mockMvc.perform(delete("/multiplatform/account/9")
                        .header("token", token("VIEWER"))
                        .header("shopId", String.valueOf(SHOP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("无权限")));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("VIEWER 不能轮换第三方应用密钥（此前可把对方系统踢下线）")
    void viewerCannotRotateAppSecret() throws Exception {
        mockMvc.perform(post("/multiplatform/oauth/app/9/rotate")
                        .header("token", token("VIEWER"))
                        .header("shopId", String.valueOf(SHOP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("无权限")));

        verifyNoInteractions(service);
    }

    /**
     * 本轮加锁的**豁免**验收：webhook 是平台回调入口（{@code BaseAuthInterceptor}
     * 白名单），它<b>不能</b>被角色门禁拦住——否则多平台消息接收整体失效，
     * 而真实调用方（Temu/TikTok/Shein）不是本测试里的用户，任何角色测试都不会红。
     * <p>
     * 用带合法 token 的请求走真实链来证明「豁免在链上确实生效」：
     * 请求能到达 Service（mock 返回 null = 重复/未落库的合法分支），说明
     * {@code @RequireRole} 没有挂在这个方法上。
     */
    @Test
    @DisplayName("webhook 豁免仍成立：带合法 token 也不被角色门禁拦（能到达 Service）")
    void webhookStaysExemptFromRoleGuard() throws Exception {
        when(service.receiveWebhook(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(null);

        mockMvc.perform(post("/multiplatform/webhook/TEMU/orderCreated")
                        .param("eventId", "evt-1")
                        .content("{}")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .header("token", token("VIEWER"))
                        .header("X-Signature", "sig"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(service).receiveWebhook("TEMU", "orderCreated", "evt-1", "{}", null, "sig");
    }

    @Test
    @DisplayName("OPERATOR 可以更新平台账号")
    void operatorCanUpdateAccount() throws Exception {
        mockMvc.perform(put("/multiplatform/account/9")
                        .content("{}")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .header("token", token("OPERATOR"))
                        .header("shopId", String.valueOf(SHOP)))
                .andExpect(status().isOk());

        verify(service).updateAccount(Mockito.eq(9L), Mockito.any());
    }
}
