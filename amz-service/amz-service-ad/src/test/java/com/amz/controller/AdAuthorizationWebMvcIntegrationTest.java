package com.amz.controller;

import com.amz.aspect.RequireRoleAspect;
import com.amz.aspect.ShopIdGuardAspect;
import com.amz.interceptor.BaseAuthInterceptor;
import com.amz.model.BidSchedule;
import com.amz.scheduler.AdReportSyncScheduler;
import com.amz.security.InternalServiceTokenService;
import com.amz.service.AdService;
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

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 广告写接口的真实 MVC + AOP 集成测试。
 *
 * <p>该测试使用真实 Spring MVC standalone 链路、真实 JWT 拦截器、真实 Spring AOP
 * 代理和两个真实切面；只替换数据库 Service / 调度器，验证被拒绝的请求不会继续
 * 调用业务服务。它比仅检查注解或直接调用 Controller 更能发现生产接线缺失。</p>
 *
 * <p>证据等级：E2（进程内桩，不发真实 Amazon 请求）。</p>
 */
@DisplayName("广告写接口：真实 MVC + AOP 必须在 Service 前阻断越权")
class AdAuthorizationWebMvcIntegrationTest {

    private JwtUtil jwtUtil;
    private AdService adService;
    private AdReportSyncScheduler adReportSyncScheduler;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secretKey", "test-secret-key-for-ad-auth-integration");
        ReflectionTestUtils.setField(jwtUtil, "issuer", "amz-erp");
        ReflectionTestUtils.setField(jwtUtil, "audience", "amz-erp-client");
        ReflectionTestUtils.setField(jwtUtil, "expireTime", 86_400_000L);
        jwtUtil.init();

        adService = Mockito.mock(AdService.class);
        adReportSyncScheduler = Mockito.mock(AdReportSyncScheduler.class);

        AdController controller = new AdController();
        ReflectionTestUtils.setField(controller, "adService", adService);
        ReflectionTestUtils.setField(controller, "adReportSyncScheduler", adReportSyncScheduler);

        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(controller);
        proxyFactory.addAspect(new RequireRoleAspect());
        proxyFactory.addAspect(new ShopIdGuardAspect());
        AdController proxiedController = proxyFactory.getProxy();

        InternalServiceTokenService serviceTokenService = new InternalServiceTokenService(
                "test-secret-key-for-ad-auth-integration",
                "amz-erp-internal",
                "amz-erp-service",
                "amz-service-ad",
                60);

        mockMvc = MockMvcBuilders.standaloneSetup(proxiedController)
                .addInterceptors(new BaseAuthInterceptor(jwtUtil, serviceTokenService))
                .build();
    }

    @Test
    @DisplayName("VIEWER 创建分时调价规则时在 Controller 前被角色切面拒绝")
    void viewerCannotCreateBidSchedule() throws Exception {
        mockMvc.perform(post("/ad/bidSchedule")
                        .header("token", token("VIEWER", 1001L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bidScheduleJson(1001L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("无权限")));

        verifyNoInteractions(adService);
    }

    @Test
    @DisplayName("OPERATOR 不能为未授权店铺创建分时调价规则")
    void operatorCannotCreateBidScheduleForUnauthorizedShop() throws Exception {
        mockMvc.perform(post("/ad/bidSchedule")
                        .header("token", token("OPERATOR", 1001L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bidScheduleJson(2002L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("无权")));

        verifyNoInteractions(adService);
    }

    @Test
    @DisplayName("OPERATOR 可以为已授权店铺创建分时调价规则")
    void operatorCanCreateBidScheduleForAuthorizedShop() throws Exception {
        BidSchedule saved = new BidSchedule();
        saved.setShopId(1001L);
        when(adService.createBidSchedule(any(BidSchedule.class))).thenReturn(saved);

        mockMvc.perform(post("/ad/bidSchedule")
                        .header("token", token("OPERATOR", 1001L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bidScheduleJson(1001L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(adService).createBidSchedule(any(BidSchedule.class));
    }

    @Test
    @DisplayName("OPERATOR 不能触发全店铺广告报表同步")
    void operatorCannotSyncAllReports() throws Exception {
        mockMvc.perform(post("/ad/reports/sync")
                        .param("days", "7")
                        .header("token", token("OPERATOR", 1001L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("无权限")));

        verifyNoInteractions(adReportSyncScheduler);
    }

    @Test
    @DisplayName("OPERATOR 不能同步未授权店铺的广告报表")
    void operatorCannotSyncUnauthorizedShopReports() throws Exception {
        mockMvc.perform(post("/ad/reports/sync")
                        .param("shopId", "2002")
                        .param("days", "7")
                        .header("token", token("OPERATOR", 1001L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("不在授权范围")));

        verifyNoInteractions(adReportSyncScheduler);
    }

    @Test
    @DisplayName("OPERATOR 可以同步已授权店铺的广告报表")
    void operatorCanSyncAuthorizedShopReports() throws Exception {
        when(adReportSyncScheduler.syncShopReportsWithSummary(1001L, 7))
                .thenReturn(new AdReportSyncScheduler.SyncSummary(1, 1, 0, 0, 3, 0));

        mockMvc.perform(post("/ad/reports/sync")
                        .param("shopId", "1001")
                        .param("days", "7")
                        .header("token", token("OPERATOR", 1001L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.upserted").value(3));

        verify(adReportSyncScheduler).syncShopReportsWithSummary(1001L, 7);
    }

    @Test
    @DisplayName("ADMIN 可以触发全店铺广告报表同步")
    void adminCanSyncAllReports() throws Exception {
        when(adReportSyncScheduler.syncAllShopsWithSummary(7))
                .thenReturn(new AdReportSyncScheduler.SyncSummary(2, 2, 0, 0, 8, 0));

        mockMvc.perform(post("/ad/reports/sync")
                        .param("days", "7")
                        .header("token", token("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.attempted").value(2));

        verify(adReportSyncScheduler).syncAllShopsWithSummary(7);
    }

    private String token(String role, Long... shops) {
        return jwtUtil.createToken(1, List.of(shops), role);
    }

    private static String bidScheduleJson(Long shopId) {
        return "{\"shopId\":" + shopId
                + ",\"campaignId\":\"camp-1\""
                + ",\"startHour\":0"
                + ",\"endHour\":6"
                + ",\"multiplier\":0.7"
                + ",\"enabled\":1}";
    }
}