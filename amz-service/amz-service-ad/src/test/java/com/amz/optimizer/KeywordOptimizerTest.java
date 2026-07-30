package com.amz.optimizer;

import com.amz.analytics.AdPerformanceAnalyzer;
import com.amz.model.AdKeyword;
import com.amz.model.AdReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

/**
 * 关键词优化器单元测试。
 * <p>
 * 覆盖场景：
 * <ul>
 *   <li>null 入参（keywords / reports）应返回空列表</li>
 *   <li>高 ACoS + 低 CR（转化极差）→ PAUSE</li>
 *   <li>高 ACoS + 有 CR（成本高但有转化）→ DECREASE_BID，建议价 = bid × 0.8</li>
 *   <li>低 ACoS + 高 CR（优质词）→ INCREASE_BID，建议价 = bid × 1.3</li>
 *   <li>高曝光 + 零点击（CTR 极低）→ NEGATIVE</li>
 *   <li>无匹配报表 / acos 为 null → OBSERVE 数据不足</li>
 *   <li>表现平稳 → OBSERVE</li>
 *   <li>关键词匹配大小写不敏感</li>
 * </ul>
 * <p>
 * 说明：AdPerformanceAnalyzer.analyzeAll 为 void 方法，此处 mock 为 no-op，
 * 直接在 AdReport 上预置派生指标（acos/cr/clicks/impressions），以隔离优化器分支逻辑。
 */
@DisplayName("KeywordOptimizer 关键词优化器测试")
class KeywordOptimizerTest {

    private AdPerformanceAnalyzer analyzer;
    private KeywordOptimizer optimizer;

    @BeforeEach
    void setUp() {
        analyzer = mock(AdPerformanceAnalyzer.class);
        // void 方法默认 no-op，显式声明增强可读性
        doNothing().when(analyzer).analyzeAll(org.mockito.ArgumentMatchers.anyList());

        optimizer = new KeywordOptimizer();
        ReflectionTestUtils.setField(optimizer, "analyzer", analyzer);
    }

    private AdKeyword kw(String keyword, String bid) {
        AdKeyword k = new AdKeyword();
        k.setKeyword(keyword);
        k.setBid(new BigDecimal(bid));
        return k;
    }

    private AdReport report(String keyword, String acos, String cr, Long clicks, Long impressions) {
        AdReport r = new AdReport();
        r.setKeyword(keyword);
        r.setAcos(acos != null ? new BigDecimal(acos) : null);
        r.setCr(cr != null ? new BigDecimal(cr) : BigDecimal.ZERO);
        r.setClicks(clicks);
        r.setImpressions(impressions);
        return r;
    }

    @Test
    @DisplayName("keywords 为 null 应返回空列表")
    void testNullKeywords() {
        assertTrue(optimizer.optimize(null, Collections.emptyList()).isEmpty());
    }

    @Test
    @DisplayName("reports 为 null 应返回空列表")
    void testNullReports() {
        assertTrue(optimizer.optimize(Collections.singletonList(kw("shoes", "1.0")), null).isEmpty());
    }

    @Test
    @DisplayName("无匹配报表 → OBSERVE 数据不足")
    void testNoMatchingReportObserve() {
        AdKeyword k = kw("wireless earbuds", "1.00");
        // 报表关键词不匹配
        AdReport r = report("other keyword", "50", "5", 10L, 1000L);

        List<KeywordOptimizer.Suggestion> result = optimizer.optimize(
                Collections.singletonList(k), Collections.singletonList(r));

        assertEquals(1, result.size());
        assertEquals("OBSERVE", result.get(0).getAction());
        assertTrue(result.get(0).getReason().contains("数据不足"));
    }

    @Test
    @DisplayName("匹配报表但 acos 为 null → OBSERVE 数据不足")
    void testNullAcosObserve() {
        AdKeyword k = kw("earbuds", "1.00");
        AdReport r = report("earbuds", null, "5", 10L, 1000L);

        List<KeywordOptimizer.Suggestion> result = optimizer.optimize(
                Collections.singletonList(k), Collections.singletonList(r));

        assertEquals("OBSERVE", result.get(0).getAction());
    }

    @Test
    @DisplayName("高 ACoS(≥40) + 低 CR(<3) → PAUSE")
    void testHighAcosLowCrPause() {
        AdKeyword k = kw("cheap case", "2.00");
        AdReport r = report("cheap case", "55", "1", 50L, 5000L);

        List<KeywordOptimizer.Suggestion> result = optimizer.optimize(
                Collections.singletonList(k), Collections.singletonList(r));

        KeywordOptimizer.Suggestion s = result.get(0);
        assertEquals("PAUSE", s.getAction());
        assertEquals(new BigDecimal("2.00"), s.getCurrentBid());
        assertTrue(s.getReason().contains("PAUSE") || s.getReason().contains("暂停") || s.getReason().contains("ACoS"));
    }

    @Test
    @DisplayName("高 ACoS(≥40) + 有 CR(≥3) → DECREASE_BID，建议价 = bid × 0.8")
    void testHighAcosWithCrDecreaseBid() {
        AdKeyword k = kw("bluetooth speaker", "5.00");
        AdReport r = report("bluetooth speaker", "45", "8", 100L, 8000L);

        List<KeywordOptimizer.Suggestion> result = optimizer.optimize(
                Collections.singletonList(k), Collections.singletonList(r));

        KeywordOptimizer.Suggestion s = result.get(0);
        assertEquals("DECREASE_BID", s.getAction());
        assertEquals(0, new BigDecimal("4.00").compareTo(s.getSuggestedBid()), "5.00 × 0.8 = 4.00");
    }

    @Test
    @DisplayName("ACoS=40 边界应归高 ACoS 分支")
    void testAcosBoundaryAt40() {
        AdKeyword k = kw("boundary", "10.00");
        // acos=40（compareTo >= 0 命中高 ACoS），cr=3（≥ LOW_CR=3 → 不走 PAUSE，走 DECREASE_BID）
        AdReport r = report("boundary", "40", "3", 30L, 3000L);

        List<KeywordOptimizer.Suggestion> result = optimizer.optimize(
                Collections.singletonList(k), Collections.singletonList(r));

        assertEquals("DECREASE_BID", result.get(0).getAction());
    }

    @Test
    @DisplayName("低 ACoS(<15) + 高 CR(≥10) → INCREASE_BID，建议价 = bid × 1.3")
    void testLowAcosHighCrIncreaseBid() {
        AdKeyword k = kw("premium headphones", "10.00");
        AdReport r = report("premium headphones", "8", "15", 200L, 20000L);

        List<KeywordOptimizer.Suggestion> result = optimizer.optimize(
                Collections.singletonList(k), Collections.singletonList(r));

        KeywordOptimizer.Suggestion s = result.get(0);
        assertEquals("INCREASE_BID", s.getAction());
        assertEquals(0, new BigDecimal("13.00").compareTo(s.getSuggestedBid()), "10.00 × 1.3 = 13.00");
    }

    @Test
    @DisplayName("高曝光(>1000) + 零点击 → NEGATIVE 否词")
    void testHighImpressionZeroClickNegative() {
        AdKeyword k = kw("broad term", "0.50");
        AdReport r = report("broad term", "20", "0", 0L, 5000L);

        List<KeywordOptimizer.Suggestion> result = optimizer.optimize(
                Collections.singletonList(k), Collections.singletonList(r));

        KeywordOptimizer.Suggestion s = result.get(0);
        assertEquals("NEGATIVE", s.getAction());
        assertTrue(s.getReason().contains("CTR") || s.getReason().contains("否"));
    }

    @Test
    @DisplayName("中段 ACoS(15-40) → OBSERVE 表现平稳")
    void testMiddleAcosObserve() {
        AdKeyword k = kw("stable term", "1.00");
        AdReport r = report("stable term", "25", "6", 50L, 5000L);

        List<KeywordOptimizer.Suggestion> result = optimizer.optimize(
                Collections.singletonList(k), Collections.singletonList(r));

        assertEquals("OBSERVE", result.get(0).getAction());
        assertTrue(result.get(0).getReason().contains("观察"));
    }

    @Test
    @DisplayName("关键词匹配大小写不敏感（report 大写也能匹配 keyword 小写）")
    void testCaseInsensitiveMatch() {
        AdKeyword k = kw("Wireless Earbuds", "1.00");
        AdReport r = report("wireless earbuds", "50", "1", 20L, 2000L);

        List<KeywordOptimizer.Suggestion> result = optimizer.optimize(
                Collections.singletonList(k), Collections.singletonList(r));

        assertEquals("PAUSE", result.get(0).getAction(), "大小写不敏感应命中并走到 PAUSE 分支");
    }

    @Test
    @DisplayName("批量优化：多个关键词应分别给出独立建议")
    void testBatchOptimize() {
        AdKeyword k1 = kw("good word", "10.00");
        AdKeyword k2 = kw("bad word", "2.00");
        AdKeyword k3 = kw("new word", "1.00");

        AdReport r1 = report("good word", "8", "15", 200L, 20000L);   // INCREASE_BID
        AdReport r2 = report("bad word", "60", "1", 50L, 5000L);      // PAUSE
        // k3 无匹配报表 → OBSERVE

        List<KeywordOptimizer.Suggestion> result = optimizer.optimize(
                Arrays.asList(k1, k2, k3), Arrays.asList(r1, r2));

        assertEquals(3, result.size());
        assertEquals("INCREASE_BID", result.get(0).getAction());
        assertEquals("PAUSE", result.get(1).getAction());
        assertEquals("OBSERVE", result.get(2).getAction());
    }
}
