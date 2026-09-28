package com.amz.scheduler;

import com.amz.client.AdvertisingApiClient;
import com.amz.mapper.AdKeywordMapper;
import com.amz.mapper.BidScheduleMapper;
import com.amz.model.AdKeyword;
import com.amz.model.BidSchedule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BidScheduleExecutorTest {

    private BidScheduleMapper bidScheduleMapper;
    private AdKeywordMapper adKeywordMapper;
    private AdvertisingApiClient advertisingApiClient;
    private BidScheduleExecutor executor;

    @BeforeEach
    void setUp() {
        bidScheduleMapper = mock(BidScheduleMapper.class);
        adKeywordMapper = mock(AdKeywordMapper.class);
        advertisingApiClient = mock(AdvertisingApiClient.class);

        executor = new BidScheduleExecutor();
        ReflectionTestUtils.setField(executor, "bidScheduleMapper", bidScheduleMapper);
        ReflectionTestUtils.setField(executor, "adKeywordMapper", adKeywordMapper);
        ReflectionTestUtils.setField(executor, "advertisingApiClient", advertisingApiClient);
    }

    @Test
    @DisplayName("跨活动同名关键词必须使用各自活动的基准价")
    void keepsBaseBidsScopedByCampaign() {
        BidSchedule rule = rule(null, "1.50");
        when(bidScheduleMapper.selectEnabledByHour(anyInt())).thenReturn(List.of(rule));
        when(advertisingApiClient.listKeywords(101L, null)).thenReturn(List.of(
                keyword(1001L, "C1", "Wireless Earbuds", "EXACT", "10.00"),
                keyword(1002L, "C2", "Wireless Earbuds", "EXACT", "10.00")));
        when(adKeywordMapper.selectList(any())).thenReturn(List.of(
                localKeyword(1L, "C1", "Wireless Earbuds", "EXACT", "1.00"),
                localKeyword(2L, "C2", "Wireless Earbuds", "EXACT", "2.00")));
        when(advertisingApiClient.updateKeywordBid(eq(101L), any(), any())).thenReturn(true);

        runCurrentHour();

        verify(advertisingApiClient).updateKeywordBid(101L, 1001L, new BigDecimal("1.50"));
        verify(advertisingApiClient).updateKeywordBid(101L, 1002L, new BigDecimal("3.00"));
    }

    @Test
    @DisplayName("基准价存储不可用时不得继续调价")
    void doesNotMutateBidsWhenBaseBidStorageFails() {
        BidSchedule rule = rule("C1", "1.50");
        when(bidScheduleMapper.selectEnabledByHour(anyInt())).thenReturn(List.of(rule));
        when(advertisingApiClient.listKeywords(101L, "C1")).thenReturn(List.of(
                keyword(1001L, "C1", "Wireless Earbuds", "EXACT", "10.00")));
        when(adKeywordMapper.selectList(any())).thenThrow(new IllegalStateException("base_bid column missing"));

        runCurrentHour();

        verify(advertisingApiClient, never()).updateKeywordBid(any(), any(), any());
    }

    @Test
    @DisplayName("缺失基准价应批量持久化，不能逐关键词先查后插")
    void batchPersistsMissingBaseBids() {
        BidSchedule rule = rule("C1", "1.00");
        when(bidScheduleMapper.selectEnabledByHour(anyInt())).thenReturn(List.of(rule));
        when(advertisingApiClient.listKeywords(101L, "C1")).thenReturn(List.of(
                keyword(1001L, "C1", "alpha", "EXACT", "1.00"),
                keyword(1002L, "C1", "beta", "PHRASE", "2.00")));
        when(adKeywordMapper.selectList(any())).thenReturn(List.of());
        when(advertisingApiClient.updateKeywordBid(eq(101L), any(), any())).thenReturn(true);

        runCurrentHour();

        verify(adKeywordMapper, never()).selectOne(any());
        verify(adKeywordMapper, never()).insert(any(AdKeyword.class));
        verify(advertisingApiClient).updateKeywordBid(101L, 1001L, new BigDecimal("1.00"));
        verify(advertisingApiClient).updateKeywordBid(101L, 1002L, new BigDecimal("2.00"));
    }

    @Test
    @DisplayName("单个关键词调价失败不能阻断同批其他关键词")
    void continuesAfterSingleKeywordMutationFailure() {
        BidSchedule rule = rule("C1", "1.50");
        when(bidScheduleMapper.selectEnabledByHour(anyInt())).thenReturn(List.of(rule));
        when(advertisingApiClient.listKeywords(101L, "C1")).thenReturn(List.of(
                keyword(1001L, "C1", "alpha", "EXACT", "1.00"),
                keyword(1002L, "C1", "beta", "PHRASE", "1.00")));
        when(adKeywordMapper.selectList(any())).thenReturn(List.of(
                localKeyword(11L, "C1", "alpha", "EXACT", "1.00"),
                localKeyword(12L, "C1", "beta", "PHRASE", "1.00")));
        when(advertisingApiClient.updateKeywordBid(101L, 1001L, new BigDecimal("1.50")))
                .thenThrow(new RuntimeException("rate limited"));
        when(advertisingApiClient.updateKeywordBid(101L, 1002L, new BigDecimal("1.50")))
                .thenReturn(true);

        runCurrentHour();

        verify(advertisingApiClient).updateKeywordBid(101L, 1002L, new BigDecimal("1.50"));
    }

    private void runCurrentHour() {
        ReflectionTestUtils.invokeMethod(executor, "doExecuteHourly");
    }

    private static BidSchedule rule(String campaignId, String multiplier) {
        BidSchedule rule = new BidSchedule();
        rule.setId(1L);
        rule.setShopId(101L);
        rule.setCampaignId(campaignId);
        rule.setMultiplier(new BigDecimal(multiplier));
        rule.setEnabled(1);
        return rule;
    }

    private static AdKeyword keyword(Long id, String campaignId, String text, String matchType, String bid) {
        AdKeyword keyword = new AdKeyword();
        keyword.setId(id);
        keyword.setShopId(101L);
        keyword.setCampaignId(campaignId);
        keyword.setKeyword(text);
        keyword.setMatchType(matchType);
        keyword.setBid(new BigDecimal(bid));
        keyword.setState("ENABLED");
        return keyword;
    }

    private static AdKeyword localKeyword(Long id, String campaignId, String text, String matchType, String baseBid) {
        AdKeyword keyword = keyword(null, campaignId, text, matchType, "0.00");
        keyword.setId(id);
        keyword.setBaseBid(new BigDecimal(baseBid));
        return keyword;
    }
}