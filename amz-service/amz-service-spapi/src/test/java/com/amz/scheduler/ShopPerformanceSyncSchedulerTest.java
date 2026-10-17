package com.amz.scheduler;

import com.amz.client.ReportsClient;
import com.amz.credential.ShopCredentialStore;
import com.amz.lock.DistributedJobLock;
import com.amz.mapper.ShopPerformanceMapper;
import com.amz.model.ShopPerformance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 店铺绩效定时留痕。
 *
 * <p><b>为什么这条测试必须存在</b>：§S33.33 之前，这个能力有 controller、有
 * sync handler、有落库表，**却没有调度**——表永远是空的，而空表无法区分
 * 「还没同步过」与「绩效一直正常」。没有测试时，这类「接线缺失」完全不可见。
 *
 * <p>判据刻意盯**调用次数与店铺集合**：调度器最容易错的地方是「默认关闭却仍发请求」
 * 或「只同步了第一个店铺」。
 */
@DisplayName("店铺绩效调度：默认关闭、按店铺逐个留痕")
class ShopPerformanceSyncSchedulerTest {

    private final ReportsClient reportsClient = mock(ReportsClient.class);
    private final ShopPerformanceMapper mapper = mock(ShopPerformanceMapper.class);
    private final ShopCredentialStore credentialStore = mock(ShopCredentialStore.class);
    private final DistributedJobLock lock = mock(DistributedJobLock.class);

    private ShopPerformanceSyncScheduler scheduler(boolean enabled) {
        ShopPerformanceSyncScheduler s = new ShopPerformanceSyncScheduler();
        ReflectionTestUtils.setField(s, "reportsClient", reportsClient);
        ReflectionTestUtils.setField(s, "performanceMapper", mapper);
        ReflectionTestUtils.setField(s, "credentialStore", credentialStore);
        ReflectionTestUtils.setField(s, "distributedJobLock", lock);
        ReflectionTestUtils.setField(s, "syncEnabled", enabled);
        ReflectionTestUtils.setField(s, "windowDays", 30);
        ReflectionTestUtils.setField(s, "maxPollAttempts", 1);
        ReflectionTestUtils.setField(s, "pollIntervalMs", 1L);
        ReflectionTestUtils.setField(s, "pollDeadlineMs", 1000L);
        // 让锁真的执行任务体，否则「跑过了」和「没跑」断言起来一样
        doAnswer(inv -> {
            java.util.function.Supplier<?> action = inv.getArgument(2, java.util.function.Supplier.class);
            return action.get();
        }).when(lock).runWithLock(anyString(), anyLong(), any(java.util.function.Supplier.class), any());
        return s;
    }

    private void stubReport(String reportId) {
        when(reportsClient.createReport(anyLong(), isNull(), anyString(), anyString(), anyString()))
                .thenReturn(reportId);
        com.amz.client.dto.ReportInfo info = new com.amz.client.dto.ReportInfo();
        info.setReportId(reportId);
        info.setProcessingStatus(com.amz.client.dto.ReportInfo.STATUS_DONE);
        info.setDocumentId("doc-1");
        when(reportsClient.getReport(anyLong(), eq(reportId))).thenReturn(info);
        when(reportsClient.downloadDocument(anyLong(), anyString())).thenReturn("csv-content");
    }

    @Test
    @DisplayName("默认关闭：一个店铺都不查、一次报表都不拉")
    void disabledByDefault() {
        scheduler(false).syncDaily();

        verify(credentialStore, never()).getActiveShopIds();
        verify(reportsClient, never()).createReport(anyLong(), any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("开启后逐个店铺同步：每个店铺都要拉一次报表")
    void syncsEveryActiveShop() {
        Set<Long> shops = new LinkedHashSet<>(java.util.List.of(1L, 2L, 3L));
        when(credentialStore.getActiveShopIds()).thenReturn(shops);
        stubReport("R-1");
        when(mapper.insert(any(ShopPerformance.class))).thenReturn(1);

        scheduler(true).syncDaily();

        verify(reportsClient).createReport(eq(1L), isNull(), anyString(), anyString(), anyString());
        verify(reportsClient).createReport(eq(2L), isNull(), anyString(), anyString(), anyString());
        verify(reportsClient).createReport(eq(3L), isNull(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("单店失败不阻断其余店铺")
    void oneShopFailureDoesNotStopOthers() {
        when(credentialStore.getActiveShopIds())
                .thenReturn(new LinkedHashSet<>(java.util.List.of(1L, 2L)));
        when(reportsClient.createReport(eq(1L), isNull(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("店铺 1 凭证失效"));
        stubReport("R-2");
        when(mapper.insert(any(ShopPerformance.class))).thenReturn(1);

        scheduler(true).syncDaily();

        verify(reportsClient).createReport(eq(2L), isNull(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("没有已授权店铺时不拉报表")
    void noActiveShopsDoesNothing() {
        when(credentialStore.getActiveShopIds()).thenReturn(Set.of());

        scheduler(true).syncDaily();

        verify(reportsClient, never()).createReport(anyLong(), any(), anyString(), anyString(), anyString());
    }
}
