package com.amz.service.impl;

import com.amz.mapper.KeywordResearchMapper;
import com.amz.mapper.SelectionOpportunityMapper;
import com.amz.model.SelectionOpportunity;
import com.amz.result.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 选品分析服务单元测试（纯 Mockito，不依赖数据库）。
 * <p>
 * 回归：ThreadLocalRandom#setSeed 恒抛 UnsupportedOperationException，
 * 曾导致 analyzeMarket / analyzeCompetitors / researchKeyword 三条链路必崩。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("选品分析服务单元测试")
class ProductSelectionServiceImplTest {

    @Mock
    private SelectionOpportunityMapper opportunityMapper;

    @Mock
    private KeywordResearchMapper keywordResearchMapper;

    @InjectMocks
    private ProductSelectionServiceImpl selectionService;

    @Test
    @DisplayName("analyzeMarket：同一种子可复现，不抛异常且生成 5 个机会")
    void analyzeMarketDoesNotThrow() {
        Result first = selectionService.analyzeMarket("wireless earbuds", "US");
        Result second = selectionService.analyzeMarket("wireless earbuds", "US");

        assertEquals(200, first.getCode());
        Map<?, ?> summary = (Map<?, ?>) first.getData();
        assertNotNull(summary);
        assertEquals(5, ((List<?>) summary.get("opportunities")).size());
        assertEquals("wireless earbuds", summary.get("keyword"));
        // 同一种子结果稳定可复现
        assertEquals(((Map<?, ?>) second.getData()).get("marketSize"),
                summary.get("marketSize"));
    }

    @Test
    @DisplayName("analyzeMarket：marketplace 为空时默认 US")
    void analyzeMarketDefaultsMarketplace() {
        Result result = selectionService.analyzeMarket("yoga mat", null);
        assertEquals(200, result.getCode());
        assertEquals("US", ((Map<?, ?>) result.getData()).get("marketplace"));
    }

    @Test
    @DisplayName("analyzeCompetitors：返回目标 ASIN 与竞品明细")
    void analyzeCompetitorsReturnsCompetitors() {
        Result result = selectionService.analyzeCompetitors("B08X4TEST", "US");
        assertEquals(200, result.getCode());
        Map<?, ?> data = (Map<?, ?>) result.getData();
        assertEquals("B08X4TEST", data.get("targetAsin"));
        assertTrue(((List<?>) data.get("competitors")).size() >= 5);
        assertNotNull(data.get("differentiation"));
    }

    @Test
    @DisplayName("researchKeyword：落库并返回调研记录")
    void researchKeywordPersistsAndReturns() {
        Result result = selectionService.researchKeyword("yoga mat", "US");
        assertEquals(200, result.getCode());
        assertNotNull(result.getData());
    }

    @Test
    @DisplayName("findOpportunities：透传 mapper 列表")
    void findOpportunitiesDelegatesToMapper() {
        SelectionOpportunity opp = new SelectionOpportunity();
        opp.setAsin("B08X4TEST");
        when(opportunityMapper.selectList(any())).thenReturn(List.of(opp));

        Result result = selectionService.findOpportunities(1L, null, "score", 20);
        assertEquals(200, result.getCode());
        assertEquals(1, ((List<?>) result.getData()).size());
    }
}
