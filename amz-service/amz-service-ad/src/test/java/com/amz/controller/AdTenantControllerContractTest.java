package com.amz.controller;

import com.amz.annotation.ShopScoped;
import com.amz.model.AdCampaignExt;
import com.amz.model.AdCreative;
import com.amz.model.AdTargeting;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.amz.service.AdCampaignExtService;
import com.amz.service.AdCreativeService;
import com.amz.service.AdReportExtService;
import com.amz.service.AdTargetingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("广告 Controller 租户契约")
class AdTenantControllerContractTest {

    @Test
    @DisplayName("素材接口必须标注 ShopScoped 且把 shopId 透传到服务层")
    void creativeEndpointsPassShopScope() {
        AdCreativeService service = mock(AdCreativeService.class);
        AdCreative creative = new AdCreative();
        creative.setId(9L);
        when(service.createCreative(eq(2L), same(creative))).thenReturn(creative);
        when(service.updateCreative(eq(2L), same(creative))).thenReturn(creative);
        when(service.review(eq(2L), eq(9L), eq("APPROVED"))).thenReturn(creative);
        when(service.listByCampaign(eq(2L), eq("camp-a"), any(PageRequest.class)))
                .thenReturn(PageResult.empty(20));

        AdCreativeController controller = new AdCreativeController();
        ReflectionTestUtils.setField(controller, "adCreativeService", service);

        controller.create(2L, creative);
        controller.update(2L, creative);
        controller.list(2L, "camp-a", 20, null);
        controller.review(2L, 9L, "APPROVED");

        verify(service).createCreative(eq(2L), same(creative));
        verify(service).updateCreative(eq(2L), same(creative));
        verify(service).listByCampaign(eq(2L), eq("camp-a"), any(PageRequest.class));
        verify(service).review(eq(2L), eq(9L), eq("APPROVED"));
        assertShopScoped(AdCreativeController.class, "create", "update", "list", "review");
    }

    @Test
    @DisplayName("定向接口必须标注 ShopScoped 且把 shopId 透传到服务层")
    void targetingEndpointsPassShopScope() {
        AdTargetingService service = mock(AdTargetingService.class);
        AdTargeting targeting = new AdTargeting();
        targeting.setId(11L);
        when(service.createTargeting(eq(2L), same(targeting))).thenReturn(targeting);
        when(service.updateTargeting(eq(2L), same(targeting))).thenReturn(targeting);
        when(service.listByCampaign(eq(2L), eq("camp-a"), eq("CONTEXTUAL"), any(PageRequest.class)))
                .thenReturn(PageResult.empty(20));

        AdTargetingController controller = new AdTargetingController();
        ReflectionTestUtils.setField(controller, "adTargetingService", service);

        controller.create(2L, targeting);
        controller.update(2L, targeting);
        controller.list(2L, "camp-a", "CONTEXTUAL", 20, null);
        controller.delete(2L, 11L);

        verify(service).createTargeting(eq(2L), same(targeting));
        verify(service).updateTargeting(eq(2L), same(targeting));
        verify(service).listByCampaign(eq(2L), eq("camp-a"), eq("CONTEXTUAL"), any(PageRequest.class));
        verify(service).delete(eq(2L), eq(11L));
        assertShopScoped(AdTargetingController.class, "create", "update", "list", "delete");
    }

    @Test
    @DisplayName("活动创建更新批量接口必须标注 ShopScoped 且把 shopId 透传到服务层")
    void campaignWriteEndpointsPassShopScope() {
        AdCampaignExtService campaignService = mock(AdCampaignExtService.class);
        AdReportExtService reportService = mock(AdReportExtService.class);
        AdCampaignExt campaign = new AdCampaignExt();
        campaign.setId(12L);
        List<AdCampaignExt> campaigns = List.of(campaign);
        when(campaignService.createCampaign(eq(2L), same(campaign))).thenReturn(campaign);
        when(campaignService.updateCampaign(eq(2L), same(campaign))).thenReturn(campaign);
        when(campaignService.batchCreate(eq(2L), same(campaigns))).thenReturn(campaigns);
        when(campaignService.batchUpdateStatus(eq(2L), eq(List.of(12L)), eq("PAUSED"))).thenReturn(campaigns);
        when(campaignService.listCampaigns(eq(2L), eq("SP"), any(PageRequest.class)))
                .thenReturn(PageResult.empty(20));
        when(reportService.getSummaryByType(2L)).thenReturn(Map.of());
        when(reportService.getShopSummary(2L)).thenReturn(Map.of());

        AdCampaignExtController controller = new AdCampaignExtController();
        ReflectionTestUtils.setField(controller, "campaignExtService", campaignService);
        ReflectionTestUtils.setField(controller, "reportExtService", reportService);

        controller.create(2L, campaign);
        controller.update(2L, campaign);
        controller.list(2L, "SP", 20, null);
        controller.batchCreate(2L, campaigns);
        controller.batchUpdateStatus(2L, List.of(12L), "PAUSED");
        controller.summaryByType(2L);
        controller.summary(2L);

        verify(campaignService).createCampaign(eq(2L), same(campaign));
        verify(campaignService).updateCampaign(eq(2L), same(campaign));
        verify(campaignService).listCampaigns(eq(2L), eq("SP"), any(PageRequest.class));
        verify(campaignService).batchCreate(eq(2L), same(campaigns));
        verify(campaignService).batchUpdateStatus(eq(2L), eq(List.of(12L)), eq("PAUSED"));
        verify(reportService).getSummaryByType(2L);
        verify(reportService).getShopSummary(2L);
        assertShopScoped(AdCampaignExtController.class,
                "create", "update", "list", "batchCreate", "batchUpdateStatus", "summaryByType", "summary");
    }

    private static void assertShopScoped(Class<?> controllerType, String... methodNames) {
        for (String methodName : methodNames) {
            Method method = Arrays.stream(controllerType.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(methodName))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("缺少接口方法: " + controllerType.getSimpleName() + "." + methodName));
            assertTrue(method.isAnnotationPresent(ShopScoped.class),
                    controllerType.getSimpleName() + "." + methodName + " 缺少 @ShopScoped");
        }
    }
}
