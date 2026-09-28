package com.amz.controller;

import com.amz.annotation.RequireRole;
import com.amz.annotation.ShopScoped;
import com.amz.model.AdReport;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.amz.result.Result;
import com.amz.scheduler.AdReportSyncScheduler;
import com.amz.service.AdService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdControllerContractTest {

    @Test
    @DisplayName("广告报表同步必须区分单店操作员权限与全店铺管理员权限")
    void syncEndpointsEnforceRoleAndScopeBoundaries() {
        Method shopSync = declaredMethod(AdController.class, "syncReports");
        assertTrue(shopSync.isAnnotationPresent(ShopScoped.class),
                "单店同步必须保留 @ShopScoped 店铺授权校验");
        RequireRole shopRole = shopSync.getAnnotation(RequireRole.class);
        assertNotNull(shopRole, "单店同步必须声明 @RequireRole");
        assertArrayEquals(new String[]{"OPERATOR", "ADMIN"}, shopRole.value());
        assertArrayEquals(new String[]{"shopId"}, shopSync.getAnnotation(PostMapping.class).params(),
                "单店同步只应匹配显式携带 shopId 的请求");

        Method allSync = declaredMethod(AdController.class, "syncAllReports");
        assertFalse(allSync.isAnnotationPresent(ShopScoped.class),
                "全店铺同步没有单一 shopId，不能伪装成店铺级接口");
        RequireRole allRole = allSync.getAnnotation(RequireRole.class);
        assertNotNull(allRole, "全店铺同步必须声明 @RequireRole");
        assertArrayEquals(new String[]{"ADMIN"}, allRole.value());
        assertArrayEquals(new String[]{"!shopId"}, allSync.getAnnotation(PostMapping.class).params(),
                "全店铺同步只能匹配未携带 shopId 的请求");
    }

    @Test
    @DisplayName("单店同步缺少 shopId 时必须失败，不能退化为全店铺同步")
    void singleShopSyncRejectsMissingShopId() {
        AdReportSyncScheduler scheduler = mock(AdReportSyncScheduler.class);
        AdController controller = new AdController();
        ReflectionTestUtils.setField(controller, "adReportSyncScheduler", scheduler);

        Result<Map<String, Object>> result = controller.syncReports(null, 7);

        assertEquals(400, result.getCode());
        verify(scheduler, never()).syncShopReportsWithSummary(any(), anyInt());
        verify(scheduler, never()).syncAllShopsWithSummary(anyInt());
    }
    @Test
    @DisplayName("手动同步响应应完整暴露成功失败跳过及元数据告警口径")
    void syncResponseIncludesAllSummaryFields() {
        AdReportSyncScheduler scheduler = mock(AdReportSyncScheduler.class);
        when(scheduler.syncShopReportsWithSummary(101L, 7))
                .thenReturn(new AdReportSyncScheduler.SyncSummary(1, 1, 0, 0, 12, 1));

        AdController controller = new AdController();
        ReflectionTestUtils.setField(controller, "adReportSyncScheduler", scheduler);

        Result<Map<String, Object>> result = controller.syncReports(101L, 7);
        Map<String, Object> data = result.getData();

        assertEquals(200, result.getCode());
        assertEquals(Set.of("shopId", "days", "attempted", "succeeded", "failed", "skipped",
                "upserted", "metadataWarnings"), data.keySet());
        assertEquals(101L, data.get("shopId"));
        assertEquals(7, data.get("days"));
        assertEquals(1, data.get("attempted"));
        assertEquals(1, data.get("succeeded"));
        assertEquals(0, data.get("failed"));
        assertEquals(0, data.get("skipped"));
        assertEquals(12, data.get("upserted"));
        assertEquals(1, data.get("metadataWarnings"));
    }

    @Test
    @DisplayName("活动报表接口透传 size/cursor，并保留 data 数组与 _page 游标元数据")
    void reportEndpointPassesPagingAndReturnsPageMetadata() {
        AdService adService = mock(AdService.class);
        String cursor = PageRequest.encodeCursor(30L);
        AdReport first = new AdReport();
        first.setCampaignId("camp-a");
        AdReport second = new AdReport();
        second.setCampaignId("camp-b");
        AdReport probe = new AdReport();
        probe.setCampaignId("camp-c");
        when(adService.getShopReports(eq(101L), any(PageRequest.class)))
                .thenReturn(PageResult.of(List.of(first, second, probe), 2,
                        row -> PageRequest.encodeCursor(30L)));

        AdController controller = new AdController();
        ReflectionTestUtils.setField(controller, "adService", adService);

        Result<List<AdReport>> result = controller.getReportsByShop(101L, 2, cursor);

        assertEquals(200, result.getCode());
        assertEquals(List.of(first, second), result.getData());
        assertEquals(Result.MSG_TRUNCATED, result.getMessage());
        assertEquals(2, result.getPage().getSize());
        assertEquals(2, result.getPage().getReturned());
        assertEquals(true, result.getPage().isHasMore());
        assertEquals(true, result.getPage().isTruncated());
        assertEquals(PageRequest.encodeCursor(30L), result.getPage().getNextCursor());
        verify(adService).getShopReports(eq(101L), any(PageRequest.class));
    }

    @Test
    @DisplayName("活动报表接口缺省页大小为 50，非法游标 fail-closed 且不查服务")
    void reportEndpointDefaultsSizeAndRejectsInvalidCursor() {
        AdService adService = mock(AdService.class);
        when(adService.getShopReports(eq(101L), any(PageRequest.class)))
                .thenReturn(PageResult.empty(PageRequest.DEFAULT_SIZE));
        AdController controller = new AdController();
        ReflectionTestUtils.setField(controller, "adService", adService);

        Result<List<AdReport>> result = controller.getReportsByShop(101L, null, null);

        assertEquals(200, result.getCode());
        assertEquals(50, result.getPage().getSize());
        verify(adService).getShopReports(eq(101L), any(PageRequest.class));
    }

    private static Method declaredMethod(Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "缺少接口方法: " + type.getSimpleName() + "." + name));
    }
}