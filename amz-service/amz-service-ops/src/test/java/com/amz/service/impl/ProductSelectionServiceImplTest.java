package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.mapper.KeywordResearchMapper;
import com.amz.mapper.SelectionOpportunityMapper;
import com.amz.model.KeywordResearch;
import com.amz.model.SelectionOpportunity;
import com.amz.result.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 选品分析服务单元测试（纯 Mockito，不依赖数据库）。
 * <p>
 * 回归一：ThreadLocalRandom#setSeed 恒抛 UnsupportedOperationException，
 * 曾导致 analyzeMarket / analyzeCompetitors / researchKeyword 三条链路必崩。
 * <p>
 * 回归二：这三条链路的正文是 keyword/asin 哈希播种出来的模拟数，落库字段里
 * shopId 曾经「取不到上下文就写 1L」——那等于把无主调用方的假数据挂到 1 号店名下。
 * 现在缺上下文直接拒绝，且两条没有 UI 入口的造数端点只在 mock 档产出。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("选品分析服务单元测试")
class ProductSelectionServiceImplTest {

    @Mock
    private SelectionOpportunityMapper opportunityMapper;

    @Mock
    private KeywordResearchMapper keywordResearchMapper;

    @Mock
    private Environment environment;

    @InjectMocks
    private ProductSelectionServiceImpl selectionService;

    @BeforeEach
    void setUp() {
        // 真实调用方（前端经网关）总带着店铺上下文，测试按同一前提跑
        UserContext.setShopId(7L);
        profiles("mock");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void profiles(String... active) {
        when(environment.getActiveProfiles()).thenReturn(active);
    }

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
    @DisplayName("analyzeMarket：落库归属必须是上下文店铺，不能偷偷记到 1 号店")
    void analyzeMarketWritesContextShop() {
        selectionService.analyzeMarket("wireless earbuds", "US");

        ArgumentCaptor<SelectionOpportunity> opp = ArgumentCaptor.forClass(SelectionOpportunity.class);
        verify(opportunityMapper, org.mockito.Mockito.times(5)).insert(opp.capture());
        for (SelectionOpportunity row : opp.getAllValues()) {
            assertEquals(7L, row.getShopId(), "机会行的 shopId 必须来自上下文");
        }
        ArgumentCaptor<KeywordResearch> research = ArgumentCaptor.forClass(KeywordResearch.class);
        verify(keywordResearchMapper).insert(research.capture());
        assertEquals(7L, research.getValue().getShopId());
    }

    @Test
    @DisplayName("analyzeMarket：没有店铺上下文时拒绝，一条都不写")
    void analyzeMarketRequiresShopContext() {
        UserContext.clear();

        Result result = selectionService.analyzeMarket("wireless earbuds", "US");

        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().contains("店铺"), "要说清缺的是店铺上下文：" + result.getMessage());
        verify(opportunityMapper, never()).insert(any(SelectionOpportunity.class));
        verify(keywordResearchMapper, never()).insert(any(KeywordResearch.class));
    }

    @Test
    @DisplayName("analyzeCompetitors：mock 档返回目标 ASIN 与竞品明细")
    void analyzeCompetitorsReturnsCompetitors() {
        Result result = selectionService.analyzeCompetitors("B08X4TEST", "US");
        assertEquals(200, result.getCode());
        Map<?, ?> data = (Map<?, ?>) result.getData();
        assertEquals("B08X4TEST", data.get("targetAsin"));
        assertTrue(((List<?>) data.get("competitors")).size() >= 5);
        assertNotNull(data.get("differentiation"));
    }

    @Test
    @DisplayName("analyzeCompetitors：非 mock 档拒绝——竞品是 asin 哈希播种的假数据")
    void analyzeCompetitorsRefusedOutsideMock() {
        profiles("prod");

        Result result = selectionService.analyzeCompetitors("B08X4TEST", "US");

        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().contains("mock"), "拒绝原因要指向档位：" + result.getMessage());
    }

    @Test
    @DisplayName("researchKeyword：mock 档落库并返回调研记录")
    void researchKeywordPersistsAndReturns() {
        Result result = selectionService.researchKeyword("yoga mat", "US");
        assertEquals(200, result.getCode());
        assertNotNull(result.getData());
        verify(keywordResearchMapper).insert(any(KeywordResearch.class));
    }

    @Test
    @DisplayName("researchKeyword：非 mock 档拒绝且不落库")
    void researchKeywordRefusedOutsideMock() {
        profiles("prod");

        Result result = selectionService.researchKeyword("yoga mat", "US");

        assertEquals(400, result.getCode());
        verify(keywordResearchMapper, never()).insert(any(KeywordResearch.class));
    }

    @Test
    @DisplayName("researchKeyword：没有店铺上下文时拒绝，不写别人的店")
    void researchKeywordRequiresShopContext() {
        UserContext.clear();

        Result result = selectionService.researchKeyword("yoga mat", "US");

        assertEquals(400, result.getCode());
        verify(keywordResearchMapper, never()).insert(any(KeywordResearch.class));
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
