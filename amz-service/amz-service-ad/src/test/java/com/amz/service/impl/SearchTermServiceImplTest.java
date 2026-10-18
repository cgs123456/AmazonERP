package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.AdAsinKeywordMapper;
import com.amz.mapper.AdSearchTermMapper;
import com.amz.mapper.ConvertingTermMapper;
import com.amz.model.AdAsinKeyword;
import com.amz.model.AdSearchTerm;
import com.amz.model.ConvertingTerm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("搜索词服务分页、幂等与多租户隔离测试")
class SearchTermServiceImplTest {

    private static final int SCAN_BATCH_SIZE = 500;

    private SearchTermServiceImpl service;
    private AdSearchTermMapper searchTermMapper;
    private ConvertingTermMapper convertingTermMapper;
    private AdAsinKeywordMapper asinKeywordMapper;

    /**
     * 纯 Mockito 测试没有 MyBatis 启动流程，LambdaQueryWrapper 取列名时会抛
     * "can not find lambda cache for this entity"。注册表元信息后才能读到
     * 真实拼出来的查询参数（§S33.81/§S33.82 同款处理）。
     */
    @BeforeAll
    static void initMybatisTableInfo() {
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, AdSearchTerm.class);
    }

    @BeforeEach
    void setUp() {
        service = new SearchTermServiceImpl();
        searchTermMapper = mock(AdSearchTermMapper.class);
        convertingTermMapper = mock(ConvertingTermMapper.class);
        asinKeywordMapper = mock(AdAsinKeywordMapper.class);
        ReflectionTestUtils.setField(service, "adSearchTermMapper", searchTermMapper);
        ReflectionTestUtils.setField(service, "convertingTermMapper", convertingTermMapper);
        ReflectionTestUtils.setField(service, "adAsinKeywordMapper", asinKeywordMapper);
    }

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("保存搜索词时拒绝请求体中的他店 shopId")
    void saveSearchTermRejectsForeignShopInBody() {
        authenticateForShop(1L);
        AdSearchTerm term = new AdSearchTerm();
        term.setShopId(2L);

        assertThrows(CodeErrorException.class, () -> service.saveSearchTerm(term));
        verifyNoInteractions(searchTermMapper);
    }

    @Test
    @DisplayName("搜索词分析按 keyset 分批扫描并汇总全部行")
    void analyzeSearchTermsScansAllBoundedPages() {
        List<AdSearchTerm> firstPage = IntStream.rangeClosed(1, SCAN_BATCH_SIZE)
                .mapToObj(i -> term((long) i, "alpha", 0, "1.00", 0, 2000))
                .toList();
        AdSearchTerm converting = term(501L, "beta", 1, "2.00", 10, 100);
        converting.setSales(new BigDecimal("20.00"));
        when(searchTermMapper.selectList(any())).thenReturn(firstPage, List.of(converting));

        Map<String, Object> result = service.analyzeSearchTerms(1L, null, 7);

        assertEquals(501, ((Number) result.get("scannedRows")).intValue());
        assertEquals(501, ((Number) result.get("totalSearchTerms")).intValue());
        assertEquals(500, ((Number) result.get("wasteTerms")).intValue());
        assertEquals(1, ((Number) result.get("convertingTerms")).intValue());
        assertFalse((Boolean) result.get("truncated"));
        verify(searchTermMapper, times(2)).selectList(any());
    }

    @Test
    @DisplayName("搜索词聚类按 keyset 分批扫描并汇总全部行")
    void clusterSearchTermsScansAllBoundedPages() {
        List<AdSearchTerm> firstPage = IntStream.rangeClosed(1, SCAN_BATCH_SIZE)
                .mapToObj(i -> term((long) i, "alpha", 0, "1.00", 1, 10))
                .toList();
        AdSearchTerm second = term(501L, "alpha beta", 1, "2.00", 1, 10);
        when(searchTermMapper.selectList(any())).thenReturn(firstPage, List.of(second));

        Map<String, Object> result = service.clusterSearchTerms(1L, null, 7);

        assertEquals(501, ((Number) result.get("scannedRows")).intValue());
        assertEquals(2, ((Number) result.get("totalClusters")).intValue());
        verify(searchTermMapper, times(2)).selectList(any());
    }

    @Test
    @DisplayName("出单词提取按批次扫描并以窗口快照幂等覆盖累计值")
    void extractConvertingTermsScansAllPagesAndUpsertsInBatches() {
        List<AdSearchTerm> firstPage = IntStream.rangeClosed(1, SCAN_BATCH_SIZE)
                .mapToObj(i -> term((long) i, "alpha", 1, "1.00", 10, 100))
                .peek(t -> t.setSales(new BigDecimal("10.00")))
                .toList();
        AdSearchTerm second = term(501L, "beta", 2, "2.00", 10, 100);
        second.setSales(new BigDecimal("20.00"));
        when(searchTermMapper.selectList(any())).thenReturn(firstPage, List.of(second));

        List<ConvertingTerm> result = service.extractConvertingTerms(1L, 7);

        assertEquals(2, result.size());
        ConvertingTerm alpha = result.stream()
                .filter(t -> "alpha".equals(t.getSearchTerm()))
                .findFirst()
                .orElseThrow();
        assertEquals(500, alpha.getTotalOrders());
        assertEquals(new BigDecimal("5000.00"), alpha.getTotalSales());
        verify(convertingTermMapper).upsertBatch(anyList());
        verify(convertingTermMapper, never()).selectOne(any());
        verify(searchTermMapper, times(2)).selectList(any());
    }

    @Test
    @DisplayName("同名搜索词跨活动必须分别累计，不能串活动合并")
    void extractConvertingTermsKeepsCampaignDimension() {
        AdSearchTerm first = term(1L, " Wireless Earbuds ", 1, "1.00", 1, 10);
        first.setCampaignId("C1");
        first.setSales(new BigDecimal("10.00"));
        AdSearchTerm second = term(2L, "wireless earbuds", 2, "2.00", 2, 20);
        second.setCampaignId("C2");
        second.setSales(new BigDecimal("20.00"));
        when(searchTermMapper.selectList(any())).thenReturn(List.of(first, second));

        List<ConvertingTerm> result = service.extractConvertingTerms(1L, 7);

        assertEquals(2, result.size());
        assertEquals(Set.of("C1", "C2"),
                result.stream().map(ConvertingTerm::getCampaignId).collect(Collectors.toSet()));
        assertTrue(result.stream().allMatch(row -> "wireless earbuds".equals(row.getSearchTerm())));
        ArgumentCaptor<List<ConvertingTerm>> captor = ArgumentCaptor.forClass(List.class);
        verify(convertingTermMapper).upsertBatch(captor.capture());
        assertEquals(2, captor.getValue().size());
    }

    @Test
    @DisplayName("批量保存 ASIN 关键词按块查询并以集合批量写入")
    void saveAsinKeywordsUsesBoundedBatchLookupAndWrites() {
        authenticateForShop(1L);
        List<AdAsinKeyword> keywords = IntStream.rangeClosed(1, 1001)
                .mapToObj(i -> asinKeyword(i))
                .toList();
        List<AdAsinKeyword> saved = service.saveAsinKeywords(keywords);

        assertEquals(1001, saved.size());
        verify(asinKeywordMapper, times(3)).upsertBatch(anyList());
        verify(asinKeywordMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("ASIN 反查批量保存按店铺 ASIN 关键词规范化并原子 upsert")
    void saveAsinKeywordsNormalizesBusinessKey() {
        authenticateForShop(1L);
        AdAsinKeyword first = new AdAsinKeyword();
        first.setShopId(1L);
        first.setAsin(" b000000001 ");
        first.setKeyword(" Wireless Earbuds ");
        AdAsinKeyword second = new AdAsinKeyword();
        second.setShopId(1L);
        second.setAsin("B000000001");
        second.setKeyword("wireless earbuds");
        second.setOrganicRank(9);

        List<AdAsinKeyword> saved = service.saveAsinKeywords(List.of(first, second));

        assertEquals(1, saved.size());
        assertEquals("B000000001", saved.get(0).getAsin());
        assertEquals("wireless earbuds", saved.get(0).getKeyword());
        assertEquals(9, saved.get(0).getOrganicRank());
        verify(asinKeywordMapper).upsertBatch(anyList());
    }

    @Test
    @DisplayName("批量保存 ASIN 关键词时任一越权项都会在写库前整体拒绝")
    void saveAsinKeywordsRejectsWholeBatchBeforeAnyWrite() {
        authenticateForShop(1L);
        AdAsinKeyword authorized = new AdAsinKeyword();
        authorized.setShopId(1L);
        authorized.setAsin("B000000001");
        authorized.setKeyword("authorized");
        AdAsinKeyword foreign = new AdAsinKeyword();
        foreign.setShopId(2L);
        foreign.setAsin("B000000002");
        foreign.setKeyword("foreign");

        assertThrows(CodeErrorException.class,
                () -> service.saveAsinKeywords(List.of(authorized, foreign)));
        verifyNoInteractions(asinKeywordMapper);
    }

    @Test
    @DisplayName("保存搜索词在无认证上下文时必须 fail-closed")
    void saveSearchTermWithoutAuthenticatedContextFailsClosed() {
        AdSearchTerm term = new AdSearchTerm();
        term.setShopId(1L);

        assertThrows(CodeErrorException.class, () -> service.saveSearchTerm(term));
        verifyNoInteractions(searchTermMapper);
    }

    /**
     * §S33.86：窗口「近 N 天」必须是含今天共 N 个自然日。
     * <p>
     * 本类三处分析入口原用 {@code now.minusDays(N)}（含今天共 N+1 天），
     * 而同模块 {@code AdServiceImpl} 与 {@code AdReportSyncScheduler} 全用
     * {@code minusDays(N-1)}（含今天共 N 天）。前端下拉明写「近 7 天」，
     * 用户看到的却是 8 天的数据——ACoS 的分母（销售额）被多算一天。
     * <p>
     * 断言扫描下界的<b>实际日期</b>，不是「有没有调用 mapper」：
     * 只断言调用发生，窗口差一天照样通过。
     */
    @Test
    @DisplayName("分析窗口含今天共 N 天：7 天的下界必须是 today-6，不是 today-7")
    void analysisWindowIncludesTodayExactly() {
        when(searchTermMapper.selectList(any())).thenReturn(List.of());

        service.analyzeSearchTerms(1L, null, 7);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(searchTermMapper).selectList(captor.capture());
        // MyBatis-Plus 参数表惰性填充，必须先触发 SQL 片段生成
        captor.getValue().getCustomSqlSegment();
        LocalDate expected = LocalDate.now().minusDays(6);
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(expected),
                "7 天窗口的下界必须是 today-6（含今天共 7 天），实际参数表="
                        + captor.getValue().getParamNameValuePairs());
        assertFalse(captor.getValue().getParamNameValuePairs().containsValue(LocalDate.now().minusDays(7)),
                "today-7 是 8 天窗口，会把 ACoS 分母多算一天；实际参数表="
                        + captor.getValue().getParamNameValuePairs());
    }

    /**
     * §S33.86：花钱零销售的词必须计入 highAcosTerms。
     * <p>
     * 库里的 {@code acos} 列只在 {@code sales > 0} 时赋值（见 calculateMetrics）。
     * 旧判定 {@code acos != null && acos >= 40} 把「零销售」整个漏掉；
     * 这类词又因 {@code clicks > 0} 进不了 wasteTerms（那里要求 0 点击）——
     * 于是最该处理的词不出现在任何汇总计数里，运营看 {@code highAcosTerms=0}
     * 会以为没有高 ACoS 词。
     */
    @Test
    @DisplayName("花钱零销售的词计入高 ACoS，而不是从所有分类里消失")
    void zeroSalesWithSpendCountsAsHighAcos() {
        AdSearchTerm burning = term(1L, "burning", 0, "50.00", 10, 2000);
        burning.setSales(BigDecimal.ZERO);
        burning.setAcos(null);          // sales=0 → 库里不赋值，这正是漏计的来源
        burning.setCr(BigDecimal.ZERO);
        when(searchTermMapper.selectList(any())).thenReturn(List.of(burning));

        Map<String, Object> result = service.analyzeSearchTerms(1L, null, 7);

        assertEquals(1, ((Number) result.get("highAcosTerms")).intValue(),
                "花了 50 元零销售 = ACoS 无穷大，必须计入高 ACoS，不能因为 acos 列为 null 就漏掉");
        assertEquals(0, ((Number) result.get("wasteTerms")).intValue(),
                "它不是「零点击」类浪费，两个分类各管一件事，不要混为一谈");
    }

    @Test
    @DisplayName("出单词提取与聚类共用同一窗口口径")
    void allAnalysisEntriesShareWindow() {
        when(searchTermMapper.selectList(any())).thenReturn(List.of());

        service.extractConvertingTerms(1L, 30);
        service.clusterSearchTerms(1L, null, 30);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(searchTermMapper, times(2)).selectList(captor.capture());
        LocalDate expected = LocalDate.now().minusDays(29);
        for (var wrapper : captor.getAllValues()) {
            wrapper.getCustomSqlSegment();
            assertTrue(wrapper.getParamNameValuePairs().containsValue(expected),
                    "30 天窗口的下界必须是 today-29（含今天共 30 天），实际="
                            + wrapper.getParamNameValuePairs());
        }
    }

    private static AdSearchTerm term(Long id, String searchTerm, int orders, String cost, int clicks, long impressions) {
        AdSearchTerm term = new AdSearchTerm();
        term.setId(id);
        term.setShopId(1L);
        term.setCampaignId("campaign-1");
        term.setSearchTerm(searchTerm);
        term.setOrders(orders);
        term.setCost(new BigDecimal(cost));
        term.setSales(BigDecimal.ZERO);
        term.setClicks((long) clicks);
        term.setImpressions(impressions);
        term.setReportDate(LocalDate.of(2026, 9, 1));
        return term;
    }

    private static AdAsinKeyword asinKeyword(int index) {
        AdAsinKeyword keyword = new AdAsinKeyword();
        keyword.setShopId(1L);
        keyword.setAsin("B" + String.format("%09d", index));
        keyword.setKeyword("keyword-" + index);
        keyword.setOrganicRank(index);
        return keyword;
    }

    private static void authenticateForShop(Long shopId) {
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(shopId));
    }
}