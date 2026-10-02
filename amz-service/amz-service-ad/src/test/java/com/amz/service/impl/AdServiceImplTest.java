package com.amz.service.impl;

import com.amz.analytics.AdPerformanceAnalyzer;
import com.amz.client.AdvertisingApiClient;
import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.AdDailyReportMapper;
import com.amz.mapper.AdKeywordMapper;
import com.amz.mapper.BidScheduleMapper;
import com.amz.model.AdDailyReport;
import com.amz.model.AdKeyword;
import com.amz.model.AdReport;
import com.amz.model.BidSchedule;
import com.amz.optimizer.KeywordOptimizer;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdServiceImplTest {

    private AdServiceImpl service;
    private AdDailyReportMapper dailyReportMapper;
    private AdvertisingApiClient advertisingApiClient;
    private BidScheduleMapper bidScheduleMapper;

    @BeforeEach
    void setUp() {
        service = new AdServiceImpl();
        dailyReportMapper = mock(AdDailyReportMapper.class);
        advertisingApiClient = mock(AdvertisingApiClient.class);
        bidScheduleMapper = mock(BidScheduleMapper.class);

        AdPerformanceAnalyzer analyzer = new AdPerformanceAnalyzer();
        KeywordOptimizer keywordOptimizer = new KeywordOptimizer();
        ReflectionTestUtils.setField(keywordOptimizer, "analyzer", analyzer);

        ReflectionTestUtils.setField(service, "analyzer", analyzer);
        ReflectionTestUtils.setField(service, "adDailyReportMapper", dailyReportMapper);
        ReflectionTestUtils.setField(service, "adKeywordMapper", mock(AdKeywordMapper.class));
        ReflectionTestUtils.setField(service, "bidScheduleMapper", bidScheduleMapper);
        ReflectionTestUtils.setField(service, "advertisingApiClient", advertisingApiClient);
        ReflectionTestUtils.setField(service, "keywordOptimizer", keywordOptimizer);
    }

    @Test
    @DisplayName("活动级报表按 MIN(id) 游标分页，并映射数据库聚合结果")
    void getShopReportsUsesDatabaseAggregationAndKeysetPagination() {
        AdDailyReport campaignA = row("camp-a", LocalDate.now().minusDays(1), 100L, 10L,
                "20.00", "200.00", 2);
        campaignA.setId(101L);
        AdDailyReport campaignB = row("camp-b", LocalDate.now(), 300L, 20L,
                "40.00", "100.00", 3);
        campaignB.setId(102L);
        AdDailyReport campaignC = row("camp-c", LocalDate.now(), 50L, 5L,
                "10.00", "0.00", 0);
        campaignC.setId(103L);
        when(dailyReportMapper.aggregateCampaignReports(
                eq(1L), any(LocalDate.class), any(LocalDate.class), eq(100L), eq(3)))
                .thenReturn(Arrays.asList(campaignA, campaignB, campaignC));

        PageResult<AdReport> page = service.getShopReports(
                1L, PageRequest.of(2, PageRequest.encodeCursor(100L)));

        assertEquals(2, page.items().size());
        assertEquals(PageRequest.encodeCursor(102L), page.nextCursor());
        assertTrue(page.truncated());
        AdReport campaignAReport = page.items().get(0);
        assertEquals("camp-a", campaignAReport.getCampaignId());
        assertEquals(100L, campaignAReport.getImpressions());
        assertEquals(10L, campaignAReport.getClicks());
        assertEquals(0, new BigDecimal("20.00").compareTo(campaignAReport.getCost()));
        assertEquals(0, new BigDecimal("200.00").compareTo(campaignAReport.getSales()));
        assertEquals(2, campaignAReport.getOrders());
        assertEquals(0, new BigDecimal("10.00").compareTo(campaignAReport.getAcos()));
        verify(dailyReportMapper).aggregateCampaignReports(
                eq(1L), any(LocalDate.class), any(LocalDate.class), eq(100L), eq(3));
        verify(dailyReportMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("日报表为空时活动级报表返回空页，不返回模拟活动")
    void getShopReportsReturnsEmptyWhenNoLiveRows() {
        when(dailyReportMapper.aggregateCampaignReports(
                eq(1L), any(LocalDate.class), any(LocalDate.class), isNull(), eq(51)))
                .thenReturn(Collections.emptyList());

        PageResult<AdReport> page = service.getShopReports(1L, PageRequest.of(50, null));

        assertTrue(page.items().isEmpty());
        assertTrue(!page.truncated());
    }

    @Test
    @DisplayName("shopId 为空时不查询数据库")
    void getShopReportsRejectsNullShopIdWithoutQuery() {
        PageResult<AdReport> page = service.getShopReports(null, PageRequest.of(20, null));

        assertTrue(page.items().isEmpty());
        verifyNoInteractions(dailyReportMapper);
    }

    @Test
    @DisplayName("店铺汇总一次数据库聚合；无数据返回 null")
    void getShopSummaryUsesLiveRowsAndReturnsNullWhenEmpty() {
        when(dailyReportMapper.aggregateShopReport(
                eq(1L), any(LocalDate.class), any(LocalDate.class))).thenReturn(null);
        assertNull(service.getShopSummary(1L));

        AdDailyReport summaryRow = row(null, LocalDate.now(), 150L, 15L,
                "30.00", "100.00", 2);
        summaryRow.setId(99L);
        when(dailyReportMapper.aggregateShopReport(
                eq(1L), any(LocalDate.class), any(LocalDate.class))).thenReturn(summaryRow);

        AdReport summary = service.getShopSummary(1L);

        assertEquals(150L, summary.getImpressions());
        assertEquals(15L, summary.getClicks());
        assertEquals(0, new BigDecimal("30.00").compareTo(summary.getCost()));
        assertEquals(0, new BigDecimal("100.00").compareTo(summary.getSales()));
        assertEquals(0, new BigDecimal("30.00").compareTo(summary.getAcos()));
        verify(dailyReportMapper, never()).aggregateCampaignReports(any(), any(), any(), any(), eq(51));
    }

    @Test
    @DisplayName("没有关键词级报表时优化建议只能是 OBSERVE，不得伪造统一指标")
    void optimizeKeywordsDoesNotInventKeywordMetrics() {
        AdKeyword keyword = new AdKeyword();
        keyword.setId(11L);
        keyword.setShopId(1L);
        keyword.setCampaignId("camp-a");
        keyword.setKeyword("wireless earbuds");
        keyword.setBid(new BigDecimal("1.20"));
        when(advertisingApiClient.listKeywords(1L, "camp-a")).thenReturn(Collections.singletonList(keyword));

        List<KeywordOptimizer.Suggestion> suggestions = service.optimizeKeywords(1L, "camp-a");

        assertEquals(1, suggestions.size());
        assertEquals("OBSERVE", suggestions.get(0).getAction());
        assertNull(suggestions.get(0).getSuggestedBid());
    }

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("创建分时调价规则时拒绝他店 shopId")
    void createBidScheduleRejectsForeignShop() {
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(1L));
        BidSchedule schedule = new BidSchedule();
        schedule.setShopId(2L);

        assertThrows(CodeErrorException.class, () -> service.createBidSchedule(schedule));
        verifyNoInteractions(bidScheduleMapper);
    }

    @Test
    @DisplayName("创建分时调价规则时允许已授权店铺")
    void createBidScheduleAllowsAuthorizedShop() {
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(1L));
        BidSchedule schedule = new BidSchedule();
        schedule.setShopId(1L);
        // 分时调价会按小时真实改广告账号竞价，所以窗口与倍率是必填项：
        // 这里给的是 V1 种子里「晚间 1.5 倍」那一行的形状。
        schedule.setStartHour(20);
        schedule.setEndHour(23);
        schedule.setMultiplier(new BigDecimal("1.50"));

        service.createBidSchedule(schedule);

        assertEquals(Integer.valueOf(1), schedule.getEnabled());
        verify(bidScheduleMapper).insert(schedule);
    }
    @Test
    @DisplayName("创建分时调价规则在无认证上下文时必须 fail-closed")
    void createBidScheduleWithoutAuthenticatedContextFailsClosed() {
        BidSchedule schedule = new BidSchedule();
        schedule.setShopId(1L);

        assertThrows(CodeErrorException.class, () -> service.createBidSchedule(schedule));
        verifyNoInteractions(bidScheduleMapper);
    }
    private static AdDailyReport row(String campaignId, LocalDate date, Long impressions, Long clicks,
                                     String cost, String sales, Integer orders) {
        AdDailyReport row = new AdDailyReport();
        row.setShopId(1L);
        row.setCampaignId(campaignId);
        row.setReportDate(date);
        row.setImpressions(impressions);
        row.setClicks(clicks);
        row.setCost(new BigDecimal(cost));
        row.setSales(new BigDecimal(sales));
        row.setOrders(orders);
        return row;
    }
}
