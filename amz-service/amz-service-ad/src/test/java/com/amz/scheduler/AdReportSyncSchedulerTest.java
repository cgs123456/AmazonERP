package com.amz.scheduler;

import com.amz.client.AdvertisingApiClient;
import com.amz.credential.AdvertisingCredentialProvider;
import com.amz.mapper.AdCampaignExtMapper;
import com.amz.mapper.AdDailyReportMapper;
import com.amz.model.AdCampaign;
import com.amz.model.AdCampaignExt;
import com.amz.model.AdDailyReport;
import com.amz.model.AdReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdReportSyncSchedulerTest {

    private AdCampaignExtMapper campaignMapper;
    private AdDailyReportMapper dailyReportMapper;
    private AdvertisingApiClient advertisingApiClient;
    private AdvertisingCredentialProvider credentialProvider;
    private AdReportSyncScheduler scheduler;

    @BeforeEach
    void setUp() {
        campaignMapper = mock(AdCampaignExtMapper.class);
        dailyReportMapper = mock(AdDailyReportMapper.class);
        advertisingApiClient = mock(AdvertisingApiClient.class);
        credentialProvider = mock(AdvertisingCredentialProvider.class);

        scheduler = new AdReportSyncScheduler();
        ReflectionTestUtils.setField(scheduler, "adCampaignExtMapper", campaignMapper);
        ReflectionTestUtils.setField(scheduler, "adDailyReportMapper", dailyReportMapper);
        ReflectionTestUtils.setField(scheduler, "advertisingApiClient", advertisingApiClient);
        ReflectionTestUtils.setField(scheduler, "advertisingCredentialProvider", credentialProvider);

        when(advertisingApiClient.getReports(anyLong(), anyString(), anyString())).thenReturn(List.of());
    }

    @Test
    @DisplayName("活动表为空时仍同步已配置凭证的店铺")
    void discoversConfiguredShopWhenCampaignTableIsEmpty() {
        when(campaignMapper.selectShopIdsAfter(anyLong(), anyInt())).thenReturn(List.of());
        when(credentialProvider.configuredShopIds()).thenReturn(Set.of(1001L));

        scheduler.syncAllShops(1);

        verify(advertisingApiClient).getReports(eq(1001L), anyString(), anyString());
    }

    @Test
    @DisplayName("店铺发现应合并本地活动与配置凭证并去重")
    void unionsLocalAndConfiguredShopIds() {
        when(campaignMapper.selectShopIdsAfter(anyLong(), anyInt())).thenReturn(List.of(1001L, 2001L));
        when(credentialProvider.configuredShopIds()).thenReturn(Set.of(1001L, 3001L));

        scheduler.syncAllShops(1);

        ArgumentCaptor<Long> shopIds = ArgumentCaptor.forClass(Long.class);
        verify(advertisingApiClient, times(3)).getReports(shopIds.capture(), anyString(), anyString());
        assertEquals(Set.of(1001L, 2001L, 3001L), new HashSet<>(shopIds.getAllValues()));
    }
    @Test
    @DisplayName("本地店铺发现按 shop_id keyset 分页，不一次拉取全表")
    void discoversLocalShopsWithKeysetPages() {
        ReflectionTestUtils.setField(scheduler, "shopPageSize", 2);
        when(campaignMapper.selectShopIdsAfter(Long.MIN_VALUE, 2))
                .thenReturn(List.of(101L, 202L));
        when(campaignMapper.selectShopIdsAfter(202L, 2)).thenReturn(List.of(303L));
        when(credentialProvider.configuredShopIds()).thenReturn(Set.of());

        scheduler.syncAllShops(1);

        verify(campaignMapper).selectShopIdsAfter(Long.MIN_VALUE, 2);
        verify(campaignMapper).selectShopIdsAfter(202L, 2);
        verify(advertisingApiClient).getReports(eq(101L), anyString(), anyString());
        verify(advertisingApiClient).getReports(eq(202L), anyString(), anyString());
        verify(advertisingApiClient).getReports(eq(303L), anyString(), anyString());
    }
    @Test
    @DisplayName("批量同步应区分成功与失败店铺，不能把部分失败伪装成成功")
    void reportsPartialFailuresInSummary() {
        when(campaignMapper.selectShopIdsAfter(anyLong(), anyInt())).thenReturn(List.of(101L, 202L));
        when(credentialProvider.configuredShopIds()).thenReturn(Set.of());
        when(advertisingApiClient.getReports(eq(101L), anyString(), anyString())).thenReturn(List.of(
                report("c1", "ag1", LocalDate.now(), 10L, 1L, "1.00", 0, "10.00", 0)));
        when(advertisingApiClient.getReports(eq(202L), anyString(), anyString()))
                .thenThrow(new IllegalStateException("credentials expired"));
        when(dailyReportMapper.upsert(any(AdDailyReport.class))).thenReturn(1);

        AdReportSyncScheduler.SyncSummary summary = scheduler.syncAllShopsWithSummary(1);

        assertEquals(2, summary.attempted());
        assertEquals(1, summary.succeeded());
        assertEquals(1, summary.failed());
        assertEquals(0, summary.skipped());
        assertEquals(1, summary.upserted());
    }

    @Test
    @DisplayName("单店铺同步失败时应返回失败状态而不是零成功")
    void singleShopFailureReturnsFailedSummary() {
        when(advertisingApiClient.getReports(eq(202L), anyString(), anyString()))
                .thenThrow(new IllegalStateException("credentials expired"));

        AdReportSyncScheduler.SyncSummary summary = scheduler.syncShopReportsWithSummary(202L, 1);

        assertEquals(1, summary.attempted());
        assertEquals(0, summary.succeeded());
        assertEquals(1, summary.failed());
        assertEquals(0, summary.skipped());
        assertEquals(0, summary.upserted());
    }
    @Test
    @DisplayName("日报同步前刷新活动元数据并按店铺活动原子 upsert")
    void refreshesCampaignMetadataBeforeDailyReportPull() {
        AdCampaign campaign = campaign("c1", "Campaign A", "ENABLED", "50.00", "FOR_SALES");
        when(advertisingApiClient.listCampaigns(101L)).thenReturn(List.of(campaign));
        when(campaignMapper.upsertMetadata(any(AdCampaignExt.class))).thenReturn(1);

        scheduler.syncShopReports(101L, 1);

        ArgumentCaptor<AdCampaignExt> metadata = ArgumentCaptor.forClass(AdCampaignExt.class);
        verify(campaignMapper).upsertMetadata(metadata.capture());
        AdCampaignExt row = metadata.getValue();
        assertEquals(101L, row.getShopId());
        assertEquals("c1", row.getCampaignId());
        assertEquals("Campaign A", row.getCampaignName());
        assertEquals("SP", row.getAdType());
        assertEquals("SP", row.getCampaignType());
        assertEquals(new BigDecimal("50.00"), row.getBudget());
        assertEquals("DAILY", row.getBudgetType());
        assertEquals("FOR_SALES", row.getBiddingStrategy());
        assertEquals("ENABLED", row.getStatus());
        verify(campaignMapper, never()).selectOne(any());

        var ordered = inOrder(advertisingApiClient, campaignMapper);
        ordered.verify(advertisingApiClient).listCampaigns(101L);
        ordered.verify(campaignMapper).upsertMetadata(any(AdCampaignExt.class));
        ordered.verify(advertisingApiClient).getReports(eq(101L), anyString(), anyString());
    }

    @Test
    @DisplayName("元数据失败不阻断报表，但必须计入元数据告警")
    void metadataFailureIsReportedAsWarning() {
        when(advertisingApiClient.listCampaigns(101L))
                .thenThrow(new IllegalStateException("campaign endpoint unavailable"));
        when(advertisingApiClient.getReports(eq(101L), anyString(), anyString())).thenReturn(List.of(
                report("c1", "ag1", LocalDate.now(), 10L, 1L, "1.00", 0, "10.00", 0)));
        when(dailyReportMapper.upsert(any(AdDailyReport.class))).thenReturn(1);

        AdReportSyncScheduler.SyncSummary summary = scheduler.syncShopReportsWithSummary(101L, 1);

        assertEquals(1, summary.succeeded());
        assertEquals(0, summary.failed());
        assertEquals(1, summary.metadataWarnings());
        assertEquals(1, summary.upserted());
    }
    @Test
    @DisplayName("活动类型映射按 id keyset 分页加载并回填日报")
    void loadsAdTypeMapWithKeysetPages() {
        ReflectionTestUtils.setField(scheduler, "campaignPageSize", 2);
        when(campaignMapper.selectAdTypePage(101L, Long.MIN_VALUE, 2)).thenReturn(List.of(
                campaignExt(1L, "c1", "SP"),
                campaignExt(2L, "c2", "SB")));
        when(campaignMapper.selectAdTypePage(101L, 2L, 2)).thenReturn(List.of(
                campaignExt(3L, "c3", "SD")));
        when(advertisingApiClient.getReports(eq(101L), anyString(), anyString())).thenReturn(List.of(
                report("c3", "ag1", LocalDate.now(), 10L, 1L, "1.00", 0, "10.00", 0)));
        when(dailyReportMapper.upsert(any(AdDailyReport.class))).thenReturn(1);

        scheduler.syncShopReports(101L, 1);

        ArgumentCaptor<AdDailyReport> rows = ArgumentCaptor.forClass(AdDailyReport.class);
        verify(dailyReportMapper).upsert(rows.capture());
        assertEquals("SD", rows.getValue().getAdType());
        verify(campaignMapper).selectAdTypePage(101L, Long.MIN_VALUE, 2);
        verify(campaignMapper).selectAdTypePage(101L, 2L, 2);
    }

    private static AdCampaignExt campaignExt(Long id, String campaignId, String adType) {
        AdCampaignExt row = new AdCampaignExt();
        row.setId(id);
        row.setCampaignId(campaignId);
        row.setAdType(adType);
        return row;
    }
    private static AdCampaign campaign(String id, String name, String state, String budget, String biddingStrategy) {
        AdCampaign campaign = new AdCampaign();
        campaign.setCampaignId(id);
        campaign.setName(name);
        campaign.setState(state);
        campaign.setCampaignType("SP");
        campaign.setDailyBudget(new BigDecimal(budget));
        campaign.setBiddingStrategy(biddingStrategy);
        return campaign;
    }

    @Test
    @DisplayName("使用单个日期范围请求，并按返回日期汇总广告组行")
    void usesSingleRangeRequestAndAggregatesAdGroupRowsByReportDate() {
        LocalDate firstDate = LocalDate.now().minusDays(2);
        LocalDate secondDate = firstDate.plusDays(1);
        when(campaignMapper.selectList(any())).thenReturn(List.of());
        when(advertisingApiClient.getReports(eq(101L), anyString(), anyString())).thenReturn(List.of(
                report("c1", "ag1", firstDate, 100L, 10L, "1.00", 2, "10.00", 3),
                report("c1", "ag2", firstDate, 50L, 5L, "0.50", 1, "5.00", 2),
                report("c1", "ag1", secondDate, 20L, 2L, "0.20", 1, "2.00", 1)));
        when(dailyReportMapper.upsert(any(AdDailyReport.class))).thenReturn(1);

        int saved = scheduler.syncShopReports(101L, 7);

        LocalDate end = LocalDate.now();
        verify(advertisingApiClient, times(1)).getReports(eq(101L), eq(end.minusDays(6).toString()), eq(end.toString()));
        ArgumentCaptor<AdDailyReport> rows = ArgumentCaptor.forClass(AdDailyReport.class);
        verify(dailyReportMapper, times(2)).upsert(rows.capture());
        assertEquals(2, saved);

        Map<LocalDate, AdDailyReport> byDate = rows.getAllValues().stream()
                .collect(Collectors.toMap(AdDailyReport::getReportDate, Function.identity()));
        AdDailyReport aggregate = byDate.get(firstDate);
        assertEquals(150L, aggregate.getImpressions());
        assertEquals(15L, aggregate.getClicks());
        assertEquals(new BigDecimal("1.50"), aggregate.getCost());
        assertEquals(new BigDecimal("15.00"), aggregate.getSales());
        assertEquals(3, aggregate.getOrders());
        assertEquals(5, aggregate.getUnits());
        assertEquals(secondDate, byDate.get(secondDate).getReportDate());
    }

    private static AdReport report(String campaignId, String adGroupId, LocalDate date, Long impressions,
                                   Long clicks, String cost, Integer orders, String sales, Integer units) {
        AdReport report = new AdReport();
        report.setCampaignId(campaignId);
        report.setAdGroupId(adGroupId);
        report.setReportDate(date);
        report.setImpressions(impressions);
        report.setClicks(clicks);
        report.setCost(new BigDecimal(cost));
        report.setOrders(orders);
        report.setSales(new BigDecimal(sales));
        report.setUnits(units);
        return report;
    }

    @Test
    @DisplayName("日报落库使用单条原子 upsert，不再先查后写")
    void upsertsDailyRowsWithoutSelectThenInsert() {
        LocalDate date = LocalDate.now();
        when(campaignMapper.selectList(any())).thenReturn(List.of());
        when(advertisingApiClient.getReports(eq(101L), anyString(), anyString())).thenReturn(List.of(
                report("c1", "ag1", date, 100L, 10L, "1.00", 2, "10.00", 3)));

        scheduler.syncShopReports(101L, 1);

        ArgumentCaptor<AdDailyReport> rows = ArgumentCaptor.forClass(AdDailyReport.class);
        verify(dailyReportMapper).upsert(rows.capture());
        verify(dailyReportMapper, never()).selectOne(any());
        verify(dailyReportMapper, never()).insert(any(AdDailyReport.class));
        assertEquals(date, rows.getValue().getReportDate());
        assertEquals(3, rows.getValue().getUnits());
    }

}
