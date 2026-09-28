package com.amz.service.impl;

import com.amz.mapper.AdAutoRuleMapper;
import com.amz.mapper.AdCampaignExtMapper;
import com.amz.mapper.AdCreativeMapper;
import com.amz.mapper.AdSearchTermMapper;
import com.amz.mapper.AdTargetingMapper;
import com.amz.mapper.BidScheduleMapper;
import com.amz.mapper.ConvertingTermMapper;
import com.amz.mapper.AdAsinKeywordMapper;
import com.amz.model.AdAsinKeyword;
import com.amz.model.AdAutoRule;
import com.amz.model.AdCampaignExt;
import com.amz.model.AdCreative;
import com.amz.model.AdSearchTerm;
import com.amz.model.AdTargeting;
import com.amz.model.BidSchedule;
import com.amz.model.ConvertingTerm;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 广告列表 keyset 分页契约。
 *
 * <p>这些接口此前直接 selectList，没有 LIMIT；当店铺数据量上来后会静默加载全表。
 * 本测试要求每条公开列表都显式探测 size+1，并在有下一页时返回可回传的游标。
 */
@DisplayName("广告列表 keyset 分页契约")
class AdPagingContractTest {

    @BeforeAll
    static void initMybatisTableInfo() {
        register(BidSchedule.class);
        register(AdCampaignExt.class);
        register(AdCreative.class);
        register(AdTargeting.class);
        register(AdSearchTerm.class);
        register(ConvertingTerm.class);
        register(AdAsinKeyword.class);
        register(AdAutoRule.class);
    }

    @Test
    @DisplayName("分时调价规则：按 id DESC 游标分页且探测行不泄漏")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void bidSchedulesUseKeysetPagination() {
        BidScheduleMapper mapper = mock(BidScheduleMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(schedule(30L), schedule(29L), schedule(28L)));
        AdServiceImpl service = new AdServiceImpl();
        ReflectionTestUtils.setField(service, "bidScheduleMapper", mapper);

        PageResult<BidSchedule> page = service.listBidSchedules(
                1L, PageRequest.of(2, PageRequest.encodeCursor(31L)));

        assertEquals(List.of(30L, 29L), page.items().stream().map(BidSchedule::getId).toList());
        assertTrue(page.truncated());
        assertEquals(PageRequest.encodeCursor(29L), page.nextCursor());

        ArgumentCaptor<LambdaQueryWrapper<BidSchedule>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        String sql = captor.getValue().getCustomSqlSegment();
        assertTrue(sql.contains("id <"), "缺少 keyset 游标条件：" + sql);
        assertTrue(sql.contains("LIMIT 3"), "必须按 size+1 探测下一页：" + sql);
    }

    @Test
    @DisplayName("广告活动：按 id DESC 游标分页且保留 adType 过滤")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void campaignsUseKeysetPagination() {
        AdCampaignExtMapper mapper = mock(AdCampaignExtMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(campaign(30L), campaign(29L), campaign(28L)));
        AdCampaignExtServiceImpl service = new AdCampaignExtServiceImpl();
        ReflectionTestUtils.setField(service, "campaignExtMapper", mapper);
        ReflectionTestUtils.setField(service, "tenantGuard", mock(AdTenantGuard.class));

        PageResult<AdCampaignExt> page = service.listCampaigns(
                1L, "SP", PageRequest.of(2, PageRequest.encodeCursor(31L)));

        assertEquals(List.of(30L, 29L), page.items().stream().map(AdCampaignExt::getId).toList());
        assertTrue(page.truncated());
        assertEquals(PageRequest.encodeCursor(29L), page.nextCursor());

        ArgumentCaptor<LambdaQueryWrapper<AdCampaignExt>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        String sql = captor.getValue().getCustomSqlSegment();
        assertTrue(sql.contains("ad_type"), "缺少 adType 过滤：" + sql);
        assertTrue(sql.contains("id <"), "缺少 keyset 游标条件：" + sql);
        assertTrue(sql.contains("LIMIT 3"), "必须按 size+1 探测下一页：" + sql);
    }

    @Test
    @DisplayName("广告素材：按 id DESC 游标分页且探测行不泄漏")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void creativesUseKeysetPagination() {
        AdCreativeMapper mapper = mock(AdCreativeMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(creative(30L), creative(29L), creative(28L)));
        AdCreativeServiceImpl service = new AdCreativeServiceImpl();
        ReflectionTestUtils.setField(service, "adCreativeMapper", mapper);
        ReflectionTestUtils.setField(service, "tenantGuard", mock(AdTenantGuard.class));

        PageResult<AdCreative> page = service.listByCampaign(
                1L, "camp-1", PageRequest.of(2, PageRequest.encodeCursor(31L)));

        assertEquals(List.of(30L, 29L), page.items().stream().map(AdCreative::getId).toList());
        assertTrue(page.truncated());
        assertEquals(PageRequest.encodeCursor(29L), page.nextCursor());

        ArgumentCaptor<LambdaQueryWrapper<AdCreative>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        String sql = captor.getValue().getCustomSqlSegment();
        assertTrue(sql.contains("campaign_id"), "缺少 campaignId 过滤：" + sql);
        assertTrue(sql.contains("id <"), "缺少 keyset 游标条件：" + sql);
        assertTrue(sql.contains("LIMIT 3"), "必须按 size+1 探测下一页：" + sql);
    }

    @Test
    @DisplayName("广告定向：按 id DESC 游标分页且保留 targetingType 过滤")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void targetingUseKeysetPagination() {
        AdTargetingMapper mapper = mock(AdTargetingMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(targeting(30L), targeting(29L), targeting(28L)));
        AdTargetingServiceImpl service = new AdTargetingServiceImpl();
        ReflectionTestUtils.setField(service, "adTargetingMapper", mapper);
        ReflectionTestUtils.setField(service, "tenantGuard", mock(AdTenantGuard.class));

        PageResult<AdTargeting> page = service.listByCampaign(
                1L, "camp-1", "CONTEXTUAL", PageRequest.of(2, PageRequest.encodeCursor(31L)));

        assertEquals(List.of(30L, 29L), page.items().stream().map(AdTargeting::getId).toList());
        assertTrue(page.truncated());
        assertEquals(PageRequest.encodeCursor(29L), page.nextCursor());

        ArgumentCaptor<LambdaQueryWrapper<AdTargeting>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        String sql = captor.getValue().getCustomSqlSegment();
        assertTrue(sql.contains("targeting_type"), "缺少 targetingType 过滤：" + sql);
        assertTrue(sql.contains("id <"), "缺少 keyset 游标条件：" + sql);
        assertTrue(sql.contains("LIMIT 3"), "必须按 size+1 探测下一页：" + sql);
    }

    @Test
    @DisplayName("搜索词：按 reportDate,id 复合游标分页，避免同日重复/遗漏")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void searchTermsUseCompositeCursor() {
        AdSearchTermMapper mapper = mock(AdSearchTermMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(
                term(LocalDate.of(2026, 9, 27), 100L),
                term(LocalDate.of(2026, 9, 27), 99L),
                term(LocalDate.of(2026, 9, 26), 98L)));
        SearchTermServiceImpl service = new SearchTermServiceImpl();
        ReflectionTestUtils.setField(service, "adSearchTermMapper", mapper);

        PageResult<AdSearchTerm> page = service.listSearchTerms(
                1L, null, null,
                PageRequest.of(2, PageRequest.encodeCursor("2026-09-27|101")));

        assertEquals(List.of(100L, 99L), page.items().stream().map(AdSearchTerm::getId).toList());
        assertTrue(page.truncated());
        assertEquals(PageRequest.encodeCursor("2026-09-27|99"), page.nextCursor());

        ArgumentCaptor<LambdaQueryWrapper<AdSearchTerm>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        String sql = captor.getValue().getCustomSqlSegment();
        assertTrue(sql.contains("report_date <"), "缺少日期 keyset 条件：" + sql);
        assertTrue(sql.contains("id <"), "缺少同日 id 次序条件：" + sql);
        assertTrue(sql.contains("LIMIT 3"), "必须按 size+1 探测下一页：" + sql);
    }

    @Test
    @DisplayName("出单词库：按 totalOrders,id 复合游标分页")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void convertingTermsUseCompositeCursor() {
        ConvertingTermMapper mapper = mock(ConvertingTermMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(
                converting(10, 3L), converting(10, 2L), converting(9, 1L)));
        SearchTermServiceImpl service = new SearchTermServiceImpl();
        ReflectionTestUtils.setField(service, "convertingTermMapper", mapper);

        PageResult<ConvertingTerm> page = service.listConvertingTerms(
                1L, null, PageRequest.of(2, PageRequest.encodeCursor("10|4")));

        assertEquals(List.of(3L, 2L), page.items().stream().map(ConvertingTerm::getId).toList());
        assertTrue(page.truncated());
        assertEquals(PageRequest.encodeCursor("10|2"), page.nextCursor());

        ArgumentCaptor<LambdaQueryWrapper<ConvertingTerm>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        String sql = captor.getValue().getCustomSqlSegment();
        assertTrue(sql.contains("total_orders <"), "缺少订单数 keyset 条件：" + sql);
        assertTrue(sql.contains("id <"), "缺少同日 id 次序条件：" + sql);
        assertTrue(sql.contains("LIMIT 3"), "必须按 size+1 探测下一页：" + sql);
    }

    @Test
    @DisplayName("ASIN 反查：按 organicRank,id 复合游标分页")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void asinReverseUsesCompositeCursor() {
        AdAsinKeywordMapper mapper = mock(AdAsinKeywordMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(
                asinKeyword(1, 2L), asinKeyword(1, 3L), asinKeyword(2, 4L)));
        SearchTermServiceImpl service = new SearchTermServiceImpl();
        ReflectionTestUtils.setField(service, "adAsinKeywordMapper", mapper);

        PageResult<AdAsinKeyword> page = service.reverseLookupAsin(
                1L, "B000000001", PageRequest.of(2, PageRequest.encodeCursor("1|1")));

        assertEquals(List.of(2L, 3L), page.items().stream().map(AdAsinKeyword::getId).toList());
        assertTrue(page.truncated());
        assertEquals(PageRequest.encodeCursor("1|3"), page.nextCursor());

        ArgumentCaptor<LambdaQueryWrapper<AdAsinKeyword>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        String sql = captor.getValue().getCustomSqlSegment();
        assertTrue(sql.contains("organic_rank >"), "缺少自然排名 keyset 条件：" + sql);
        assertTrue(sql.contains("id >"), "缺少同排名 id 次序条件：" + sql);
        assertTrue(sql.contains("LIMIT 3"), "必须按 size+1 探测下一页：" + sql);
    }

    @Test
    @DisplayName("自动规则：按 priority,id 复合游标分页，保留优先级排序")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void autoRulesUseCompositeCursor() {
        AdAutoRuleMapper mapper = mock(AdAutoRuleMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(
                autoRule(10, 10L), autoRule(10, 9L), autoRule(8, 8L)));
        AdAutoRuleServiceImpl service = new AdAutoRuleServiceImpl();
        ReflectionTestUtils.setField(service, "adAutoRuleMapper", mapper);

        PageResult<AdAutoRule> page = service.listRules(
                1L, null, PageRequest.of(2, PageRequest.encodeCursor("10|11")));

        assertEquals(List.of(10L, 9L), page.items().stream().map(AdAutoRule::getId).toList());
        assertTrue(page.truncated());
        assertEquals(PageRequest.encodeCursor("10|9"), page.nextCursor());

        ArgumentCaptor<LambdaQueryWrapper<AdAutoRule>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(captor.capture());
        String sql = captor.getValue().getCustomSqlSegment();
        assertTrue(sql.contains("priority <"), "缺少优先级 keyset 条件：" + sql);
        assertTrue(sql.contains("id <"), "缺少同优先级 id 次序条件：" + sql);
        assertTrue(sql.contains("LIMIT 3"), "必须按 size+1 探测下一页：" + sql);
    }

    private static void register(Class<?> entityClass) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
    }

    private static BidSchedule schedule(Long id) {
        BidSchedule value = new BidSchedule();
        value.setId(id);
        value.setShopId(1L);
        return value;
    }

    private static AdCampaignExt campaign(Long id) {
        AdCampaignExt value = new AdCampaignExt();
        value.setId(id);
        value.setShopId(1L);
        value.setAdType("SP");
        return value;
    }

    private static AdCreative creative(Long id) {
        AdCreative value = new AdCreative();
        value.setId(id);
        value.setCampaignId("camp-1");
        return value;
    }

    private static AdTargeting targeting(Long id) {
        AdTargeting value = new AdTargeting();
        value.setId(id);
        value.setCampaignId("camp-1");
        value.setTargetingType("CONTEXTUAL");
        return value;
    }

    private static AdSearchTerm term(LocalDate date, Long id) {
        AdSearchTerm value = new AdSearchTerm();
        value.setId(id);
        value.setShopId(1L);
        value.setReportDate(date);
        return value;
    }

    private static ConvertingTerm converting(Integer orders, Long id) {
        ConvertingTerm value = new ConvertingTerm();
        value.setId(id);
        value.setShopId(1L);
        value.setStatus("ACTIVE");
        value.setTotalOrders(orders);
        return value;
    }

    private static AdAsinKeyword asinKeyword(Integer rank, Long id) {
        AdAsinKeyword value = new AdAsinKeyword();
        value.setId(id);
        value.setShopId(1L);
        value.setAsin("B000000001");
        value.setOrganicRank(rank);
        return value;
    }

    private static AdAutoRule autoRule(Integer priority, Long id) {
        AdAutoRule value = new AdAutoRule();
        value.setId(id);
        value.setShopId(1L);
        value.setPriority(priority);
        return value;
    }
}