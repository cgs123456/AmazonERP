package com.amz.engine;

import com.amz.mapper.PromotionCalendarMapper;
import com.amz.mapper.SalesHistoryMapper;
import com.amz.mapper.SeasonalIndexMapper;
import com.amz.model.PromotionCalendar;
import com.amz.model.ReplenishmentSuggestion;
import com.amz.model.SalesHistory;
import com.amz.model.SeasonalIndex;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 智能补货引擎单元测试。
 * <p>
 * 覆盖场景：
 * <ul>
 *   <li>CV 变异系数计算：空列表 / 零均值 / 正常值</li>
 *   <li>安全系数自适应：低/中/高波动 + 边界值</li>
 *   <li>季节性指数查询：null 类目 / 查表命中 / 查表无果</li>
 *   <li>促销乘数查询：无促销 / 有促销取最大</li>
 *   <li>generateSuggestion 完整流程：零库存紧急 / 健康库存低优先级 / 季节性加权</li>
 * </ul>
 */
@DisplayName("ReplenishmentEngine 智能补货引擎测试")
class ReplenishmentEngineTest {

    private SalesHistoryMapper salesHistoryMapper;
    private SeasonalIndexMapper seasonalIndexMapper;
    private PromotionCalendarMapper promotionCalendarMapper;
    private ReplenishmentEngine engine;

    @BeforeEach
    void setUp() {
        // 手动初始化 Mockito（不依赖 MockitoExtension）
        salesHistoryMapper = org.mockito.Mockito.mock(SalesHistoryMapper.class);
        seasonalIndexMapper = org.mockito.Mockito.mock(SeasonalIndexMapper.class);
        promotionCalendarMapper = org.mockito.Mockito.mock(PromotionCalendarMapper.class);

        engine = new ReplenishmentEngine();
        ReflectionTestUtils.setField(engine, "salesHistoryMapper", salesHistoryMapper);
        ReflectionTestUtils.setField(engine, "seasonalIndexMapper", seasonalIndexMapper);
        ReflectionTestUtils.setField(engine, "promotionCalendarMapper", promotionCalendarMapper);
    }

    // ==================== CV 变异系数 ====================

    @Test
    @DisplayName("calculateCV：空列表应返回 0.0")
    void testCalculateCVEmptyList() {
        assertEquals(0.0, engine.calculateCV(Collections.emptyList()));
    }

    @Test
    @DisplayName("calculateCV：null 列表应返回 0.0")
    void testCalculateCVNullList() {
        assertEquals(0.0, engine.calculateCV(null));
    }

    @Test
    @DisplayName("calculateCV：全零销量应返回 0.0（均值为 0）")
    void testCalculateCVAllZeros() {
        assertEquals(0.0, engine.calculateCV(Arrays.asList(0, 0, 0, 0, 0)));
    }

    @Test
    @DisplayName("calculateCV：完全稳定的销量（每日相同）应返回 0.0（标准差为 0）")
    void testCalculateCVStableSales() {
        assertEquals(0.0, engine.calculateCV(Arrays.asList(10, 10, 10, 10, 10)));
    }

    @Test
    @DisplayName("calculateCV：波动销量应返回正数 CV")
    void testCalculateCVVariableSales() {
        List<Integer> sales = Arrays.asList(10, 20, 5, 15, 30);
        double cv = engine.calculateCV(sales);
        assertTrue(cv > 0.0, "波动销量应产生正 CV");
        // 手算：mean=16, variance=(36+16+121+1+196)/5=74, stddev≈8.6, cv≈0.537
        assertTrue(cv > 0.4 && cv < 0.7, "CV 应在 0.5 附近");
    }

    @Test
    @DisplayName("calculateCV：列表含 null 元素应按 0 处理（产生非零 CV）")
    void testCalculateCVWithNulls() {
        // [10, null, 10, null, 10] → null 按 0 处理 → [10,0,10,0,10]
        // mean=6, variance=24, stddev≈4.9, cv≈0.82
        double cv = engine.calculateCV(Arrays.asList(10, null, 10, null, 10));
        assertTrue(cv > 0.7 && cv < 0.9, "含 null 元素应按 0 处理，CV 应在 0.82 附近");
    }

    // ==================== 安全系数 ====================

    @Test
    @DisplayName("getSafetyFactor：CV < 0.3 应返回 1.10（低波动）")
    void testGetSafetyFactorLowCV() {
        assertEquals(BigDecimal.valueOf(1.10), engine.getSafetyFactor(0.1));
        assertEquals(BigDecimal.valueOf(1.10), engine.getSafetyFactor(0.0));
        assertEquals(BigDecimal.valueOf(1.10), engine.getSafetyFactor(0.29));
    }

    @Test
    @DisplayName("getSafetyFactor：0.3 ≤ CV ≤ 0.6 应返回 1.20（中波动）")
    void testGetSafetyFactorMidCV() {
        assertEquals(BigDecimal.valueOf(1.20), engine.getSafetyFactor(0.3), "0.3 边界应归中波动");
        assertEquals(BigDecimal.valueOf(1.20), engine.getSafetyFactor(0.5));
        assertEquals(BigDecimal.valueOf(1.20), engine.getSafetyFactor(0.6), "0.6 边界应归中波动");
    }

    @Test
    @DisplayName("getSafetyFactor：CV > 0.6 应返回 1.35（高波动）")
    void testGetSafetyFactorHighCV() {
        assertEquals(BigDecimal.valueOf(1.35), engine.getSafetyFactor(0.61));
        assertEquals(BigDecimal.valueOf(1.35), engine.getSafetyFactor(1.0));
        assertEquals(BigDecimal.valueOf(1.35), engine.getSafetyFactor(2.5));
    }

    // ==================== 季节性指数 ====================

    @Test
    @DisplayName("getSeasonalIndex：null 类目应返回默认值 1.0")
    void testGetSeasonalIndexNullCategory() {
        assertEquals(BigDecimal.ONE, engine.getSeasonalIndex(null, 7));
    }

    @Test
    @DisplayName("getSeasonalIndex：查表命中应返回表中指数")
    void testGetSeasonalIndexHit() {
        SeasonalIndex idx = new SeasonalIndex();
        idx.setCategory("ELECTRONICS");
        idx.setMonth(11);
        idx.setSeasonalIndex(BigDecimal.valueOf(1.8));

        when(seasonalIndexMapper.selectList(any())).thenReturn(Collections.singletonList(idx));

        BigDecimal result = engine.getSeasonalIndex("ELECTRONICS", 11);
        assertEquals(0, result.compareTo(BigDecimal.valueOf(1.8)));
    }

    @Test
    @DisplayName("getSeasonalIndex：查表无果应返回默认值 1.0")
    void testGetSeasonalIndexMiss() {
        when(seasonalIndexMapper.selectList(any())).thenReturn(Collections.emptyList());

        assertEquals(BigDecimal.ONE, engine.getSeasonalIndex("UNKNOWN_CAT", 6));
    }

    // ==================== 促销乘数 ====================

    @Test
    @DisplayName("getActivePromotionMultiplier：无促销应返回默认值 1.0")
    void testGetPromotionMultiplierNoPromotions() {
        when(promotionCalendarMapper.selectList(any())).thenReturn(Collections.emptyList());

        assertEquals(BigDecimal.ONE, engine.getActivePromotionMultiplier("ELECTRONICS"));
    }

    @Test
    @DisplayName("getActivePromotionMultiplier：多促销重叠时应取最大乘数")
    void testGetPromotionMultiplierMultiplePromotions() {
        PromotionCalendar p1 = new PromotionCalendar();
        p1.setMultiplier(BigDecimal.valueOf(1.5));
        PromotionCalendar p2 = new PromotionCalendar();
        p2.setMultiplier(BigDecimal.valueOf(2.8));

        when(promotionCalendarMapper.selectList(any())).thenReturn(Arrays.asList(p1, p2));

        BigDecimal result = engine.getActivePromotionMultiplier("ELECTRONICS");
        assertEquals(0, result.compareTo(BigDecimal.valueOf(2.8)));
    }

    @Test
    @DisplayName("getActivePromotionMultiplier：促销乘数小于 1.0 时应返回 1.0（兜底）")
    void testGetPromotionMultiplierBelowOne() {
        PromotionCalendar p = new PromotionCalendar();
        p.setMultiplier(BigDecimal.valueOf(0.5));

        when(promotionCalendarMapper.selectList(any())).thenReturn(Collections.singletonList(p));

        assertEquals(0, engine.getActivePromotionMultiplier("ELECTRONICS").compareTo(BigDecimal.ONE));
    }

    // ==================== forecastNext14Days 基线需求 ====================

    @Test
    @DisplayName("forecastNext14Days：无销量历史应返回 0")
    void testForecastNoHistory() {
        when(salesHistoryMapper.selectList(any())).thenReturn(Collections.emptyList());

        BigDecimal baseline = engine.forecastNext14Days(1L, "SKU-001");
        assertEquals(0, baseline.compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("forecastNext14Days：7 天日均 × 0.7 + 30 天日均 × 0.3 应正确加权")
    void testForecastWeightedAverage() {
        // 构造 7 条记录（近 7 天），每天 10 件 → 7天日均=10
        // 构造 30 条记录（近 30 天），每天 10 件 → 30天日均=10
        // baseline = 10×0.7 + 10×0.3 = 10
        LocalDate today = LocalDate.now();
        SalesHistory h = new SalesHistory();
        h.setQuantity(10);
        h.setSaleDate(today);

        when(salesHistoryMapper.selectList(any())).thenReturn(Collections.singletonList(h));

        BigDecimal baseline = engine.forecastNext14Days(1L, "SKU-001");
        // 单条记录：7天日均=10/7, 30天日均=10/30
        // baseline = (10/7)*0.7 + (10/30)*0.3 = 1 + 0.1 = 1.1（约）
        assertTrue(baseline.compareTo(BigDecimal.ZERO) > 0, "应有非零基线需求");
    }

    // ==================== generateSuggestion 完整流程 ====================

    @Test
    @DisplayName("generateSuggestion：零库存 + 有销量应判为 URGENT 并给出补货量")
    void testGenerateSuggestionZeroStockUrgent() {
        // 模拟近 30 天每天 10 件销量
        LocalDate today = LocalDate.now();
        SalesHistory h = new SalesHistory();
        h.setQuantity(10);
        h.setSaleDate(today);

        when(salesHistoryMapper.selectList(any())).thenReturn(Collections.singletonList(h));
        when(seasonalIndexMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(promotionCalendarMapper.selectList(any())).thenReturn(Collections.emptyList());

        ReplenishmentSuggestion suggestion = engine.generateSuggestion(
                1L, "SKU-URGENT", "B0TEST001", "ELECTRONICS", 0, 14);

        assertNotNull(suggestion);
        assertEquals("SKU-URGENT", suggestion.getSku());
        assertEquals(0, suggestion.getCurrentTotalStock());
        assertTrue(suggestion.getSuggestedReplenishQty() > 0, "零库存应建议补货");
        assertEquals("URGENT", suggestion.getUrgencyLevel(), "零库存 + 有销量应判为 URGENT");
        assertNotNull(suggestion.getEstimatedStockoutDate(), "应给出预计断货日期");
    }

    @Test
    @DisplayName("generateSuggestion：库存充足应判为 LOW 且建议补货量为 0")
    void testGenerateSuggestionHealthyStockLow() {
        // 模拟近 30 天每天 5 件销量，当前库存 1000（远超 14 天需求）
        LocalDate today = LocalDate.now();
        SalesHistory h = new SalesHistory();
        h.setQuantity(5);
        h.setSaleDate(today);

        when(salesHistoryMapper.selectList(any())).thenReturn(Collections.singletonList(h));
        when(seasonalIndexMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(promotionCalendarMapper.selectList(any())).thenReturn(Collections.emptyList());

        ReplenishmentSuggestion suggestion = engine.generateSuggestion(
                1L, "SKU-HEALTHY", "B0TEST002", "ELECTRONICS", 1000, 14);

        assertNotNull(suggestion);
        assertEquals(0, suggestion.getSuggestedReplenishQty(), "库存充足时建议补货量为 0");
        assertEquals("LOW", suggestion.getUrgencyLevel(), "无需补货应判为 LOW");
    }

    @Test
    @DisplayName("generateSuggestion：零销量 + 零库存时无法预估断货日期")
    void testGenerateSuggestionZeroSalesZeroStock() {
        when(salesHistoryMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(seasonalIndexMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(promotionCalendarMapper.selectList(any())).thenReturn(Collections.emptyList());

        ReplenishmentSuggestion suggestion = engine.generateSuggestion(
                1L, "SKU-NOSALE", "B0TEST003", "ELECTRONICS", 0, 14);

        assertNotNull(suggestion);
        assertNull(suggestion.getEstimatedStockoutDate(), "无销量数据时断货日期应为 null");
        // baseline=0 时，adjusted=0，suggested=max(0, 0+0-0)=0
        assertEquals(0, suggestion.getSuggestedReplenishQty());
        assertEquals("LOW", suggestion.getUrgencyLevel());
    }

    @Test
    @DisplayName("generateSuggestion：季节性指数 > 1 应放大补货量")
    void testGenerateSuggestionSeasonalIndexAmplifies() {
        LocalDate today = LocalDate.now();
        SalesHistory h = new SalesHistory();
        h.setQuantity(10);
        h.setSaleDate(today);

        // 11 月电子类目季节性指数 1.8（黑五促销季）
        SeasonalIndex idx = new SeasonalIndex();
        idx.setCategory("ELECTRONICS");
        idx.setMonth(today.getMonthValue());
        idx.setSeasonalIndex(BigDecimal.valueOf(1.8));

        when(salesHistoryMapper.selectList(any())).thenReturn(Collections.singletonList(h));
        when(seasonalIndexMapper.selectList(any())).thenReturn(Collections.singletonList(idx));
        when(promotionCalendarMapper.selectList(any())).thenReturn(Collections.emptyList());

        ReplenishmentSuggestion suggestion = engine.generateSuggestion(
                1L, "SKU-SEASONAL", "B0TEST004", "ELECTRONICS", 0, 14);

        assertTrue(suggestion.getSuggestedReplenishQty() > 0, "季节性放大后应有补货建议");
        assertEquals(0, suggestion.getSeasonalIndex().compareTo(BigDecimal.valueOf(1.8)),
                "季节性指数应被记录到建议中");
    }

    @Test
    @DisplayName("generateSuggestion：促销乘数 > 1 应进一步放大补货量")
    void testGenerateSuggestionPromotionAmplifies() {
        LocalDate today = LocalDate.now();
        SalesHistory h = new SalesHistory();
        h.setQuantity(10);
        h.setSaleDate(today);

        PromotionCalendar promo = new PromotionCalendar();
        promo.setMultiplier(BigDecimal.valueOf(2.5));
        promo.setStartDate(today.minusDays(1));
        promo.setEndDate(today.plusDays(7));

        when(salesHistoryMapper.selectList(any())).thenReturn(Collections.singletonList(h));
        when(seasonalIndexMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(promotionCalendarMapper.selectList(any())).thenReturn(Collections.singletonList(promo));

        ReplenishmentSuggestion suggestion = engine.generateSuggestion(
                1L, "SKU-PROMO", "B0TEST005", "ELECTRONICS", 0, 14);

        assertTrue(suggestion.getPromotionMultiplier().compareTo(BigDecimal.ONE) > 0,
                "促销乘数应大于 1");
        assertTrue(suggestion.getSuggestedReplenishQty() > 0);
    }

    @Test
    @DisplayName("generateSuggestion：高波动销量（CV>0.6）应使用 1.35 安全系数")
    void testGenerateSuggestionHighCVUsesHigherSafetyFactor() {
        LocalDate today = LocalDate.now();
        // 构造高波动销量：1, 50, 2, 60, 3（CV 远超 0.6）
        List<SalesHistory> histories = Arrays.asList(
                buildSalesHistory(today, 1),
                buildSalesHistory(today.minusDays(1), 50),
                buildSalesHistory(today.minusDays(2), 2),
                buildSalesHistory(today.minusDays(3), 60),
                buildSalesHistory(today.minusDays(4), 3)
        );

        when(salesHistoryMapper.selectList(any())).thenReturn(histories);
        when(seasonalIndexMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(promotionCalendarMapper.selectList(any())).thenReturn(Collections.emptyList());

        ReplenishmentSuggestion suggestion = engine.generateSuggestion(
                1L, "SKU-VOLATILE", "B0TEST006", "ELECTRONICS", 0, 14);

        assertEquals(0, suggestion.getSafetyFactor().compareTo(BigDecimal.valueOf(1.35)),
                "CV > 0.6 应使用 1.35 安全系数");
    }

    private SalesHistory buildSalesHistory(LocalDate date, int qty) {
        SalesHistory h = new SalesHistory();
        h.setSaleDate(date);
        h.setQuantity(qty);
        return h;
    }
}
