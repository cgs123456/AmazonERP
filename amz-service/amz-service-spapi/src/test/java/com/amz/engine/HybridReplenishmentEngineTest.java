package com.amz.engine;

import com.amz.engine.ml.MlDemandPredictor;
import com.amz.engine.ml.MlPrediction;
import com.amz.mapper.PromotionCalendarMapper;
import com.amz.mapper.SalesHistoryMapper;
import com.amz.mapper.SeasonalIndexMapper;
import com.amz.model.ReplenishmentSuggestion;
import com.amz.model.SalesHistory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 规则 + LightGBM 混合补货引擎单元测试。
 * <p>
 * 覆盖场景：
 * <ul>
 *   <li>ML 模型未加载 → 纯规则路径，blendStrategy=RULE_ONLY</li>
 *   <li>模型已加载但 CV ≤ 0.6 → 纯规则路径</li>
 *   <li>模型已加载且 CV > 0.6 → ML 混合路径，blendStrategy=HYBRID_ML_70_RULE_30</li>
 *   <li>ML 混合后补货量非负兜底</li>
 *   <li>ML 预测结果字段正确回填（mlPredictedDemand / mlConfidence）</li>
 * </ul>
 * <p>
 * 说明：HybridReplenishmentEngine 继承 ReplenishmentEngine，父类与子类各持有一份
 * salesHistoryMapper（父类字段为 private，子类无法访问故单独声明）。测试中向父子
 * 两份字段注入同一 mock 实例，保证 super.generateSuggestion 与子类 CV 计算读取一致数据。
 */
@DisplayName("HybridReplenishmentEngine 混合补货引擎测试")
class HybridReplenishmentEngineTest {

    private SalesHistoryMapper salesHistoryMapper;
    private SeasonalIndexMapper seasonalIndexMapper;
    private PromotionCalendarMapper promotionCalendarMapper;
    private MlDemandPredictor predictor;
    private HybridReplenishmentEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        salesHistoryMapper = mock(SalesHistoryMapper.class);
        seasonalIndexMapper = mock(SeasonalIndexMapper.class);
        promotionCalendarMapper = mock(PromotionCalendarMapper.class);
        predictor = mock(MlDemandPredictor.class);

        engine = new HybridReplenishmentEngine();

        // 父类 ReplenishmentEngine 的 private 字段（super.generateSuggestion 使用）。
        // 注意：子类 HybridReplenishmentEngine 也声明了同名 salesHistoryMapper 字段，
        // 故无法用 ReflectionTestUtils.setField(engine, "salesHistoryMapper", ...)，
        // 因为它会优先匹配子类字段，导致父类字段未被注入。改用原生反射分别注入父子两份字段。
        Field parentSalesHistoryMapper = ReplenishmentEngine.class.getDeclaredField("salesHistoryMapper");
        parentSalesHistoryMapper.setAccessible(true);
        parentSalesHistoryMapper.set(engine, salesHistoryMapper);

        Field seasonalIndexMapperField = ReplenishmentEngine.class.getDeclaredField("seasonalIndexMapper");
        seasonalIndexMapperField.setAccessible(true);
        seasonalIndexMapperField.set(engine, seasonalIndexMapper);

        Field promotionCalendarMapperField = ReplenishmentEngine.class.getDeclaredField("promotionCalendarMapper");
        promotionCalendarMapperField.setAccessible(true);
        promotionCalendarMapperField.set(engine, promotionCalendarMapper);

        // 子类 HybridReplenishmentEngine 自身的 private 字段
        Field childSalesHistoryMapper = HybridReplenishmentEngine.class.getDeclaredField("salesHistoryMapper");
        childSalesHistoryMapper.setAccessible(true);
        childSalesHistoryMapper.set(engine, salesHistoryMapper);

        ReflectionTestUtils.setField(engine, "predictor", predictor);
    }

    /** 构造单条销量记录 */
    private SalesHistory hist(LocalDate date, int qty) {
        SalesHistory h = new SalesHistory();
        h.setSaleDate(date);
        h.setQuantity(qty);
        return h;
    }

    /** 构造高波动销量（CV ≈ 1.13，远超 0.6 阈值） */
    private List<SalesHistory> volatileSales() {
        LocalDate today = LocalDate.now();
        return Arrays.asList(
                hist(today, 1),
                hist(today.minusDays(1), 50),
                hist(today.minusDays(2), 2),
                hist(today.minusDays(3), 60),
                hist(today.minusDays(4), 3)
        );
    }

    @Test
    @DisplayName("ML 模型未加载 → 走纯规则路径，blendStrategy=RULE_ONLY，ML 字段为 null")
    void testModelNotLoadedRuleOnly() {
        when(predictor.isModelLoaded()).thenReturn(false);
        when(salesHistoryMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(seasonalIndexMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(promotionCalendarMapper.selectList(any())).thenReturn(Collections.emptyList());

        ReplenishmentSuggestion s = engine.generateSuggestion(
                1L, "SKU-NOMODEL", "B0NO", "ELECTRONICS", 0, 14);

        assertEquals("RULE_ONLY", s.getBlendStrategy(), "模型未加载应走纯规则路径");
        assertNull(s.getMlPredictedDemand(), "纯规则路径不应有 ML 预测值");
        assertNull(s.getMlConfidence());
        // 零销量零库存 → LOW
        assertEquals("LOW", s.getUrgencyLevel());
        assertEquals(0, s.getSuggestedReplenishQty());
    }

    @Test
    @DisplayName("模型已加载但 CV ≤ 0.6（稳定销量）→ 纯规则路径")
    void testLowCVRuleOnly() {
        when(predictor.isModelLoaded()).thenReturn(true);
        LocalDate today = LocalDate.now();
        // 全 10 的稳定销量，覆盖完整 30 天窗口（CV 计算为零填充口径：
        // 缺失日期按 0 计，稀疏记录会被判为高波动）
        List<SalesHistory> stable = new java.util.ArrayList<>();
        for (int i = 0; i <= 30; i++) {
            stable.add(hist(today.minusDays(i), 10));
        }
        when(salesHistoryMapper.selectList(any())).thenReturn(stable);
        when(seasonalIndexMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(promotionCalendarMapper.selectList(any())).thenReturn(Collections.emptyList());

        // 库存需显著高于备货期需求（日销 10 × safety × leadTime14 ≈ 160+），
        // B2 量纲修复后 leadTimeDemand=日均×leadTimeDays，100 已不足以覆盖
        ReplenishmentSuggestion s = engine.generateSuggestion(
                1L, "SKU-STABLE", "B0ST", "ELECTRONICS", 5_000, 14);

        assertEquals("RULE_ONLY", s.getBlendStrategy(), "CV 低应走纯规则路径");
        assertNull(s.getMlPredictedDemand());
        // 稳定销量 + 库存充足 → 建议补货量 0
        assertEquals(0, s.getSuggestedReplenishQty());
    }

    @Test
    @DisplayName("模型已加载且 CV > 0.6（高波动）→ ML 混合路径，blendStrategy=HYBRID_ML_70_RULE_30")
    void testHighCVHybridPath() {
        when(predictor.isModelLoaded()).thenReturn(true);
        when(salesHistoryMapper.selectList(any())).thenReturn(volatileSales());
        when(seasonalIndexMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(promotionCalendarMapper.selectList(any())).thenReturn(Collections.emptyList());

        // ML 预测：14 天需求 200，置信度 0.85
        when(predictor.predict(any())).thenReturn(new MlPrediction(200.0, 0.85, "lightgbm-v1"));

        ReplenishmentSuggestion s = engine.generateSuggestion(
                1L, "SKU-VOLATILE", "B0VO", "ELECTRONICS", 0, 14);

        assertEquals("HYBRID_ML_70_RULE_30", s.getBlendStrategy(), "高波动应启用 ML 混合");
        assertEquals(200.0, s.getMlPredictedDemand(), 0.0001, "ML 预测需求应回填");
        assertEquals(0.85, s.getMlConfidence(), 0.0001, "ML 置信度应回填");
        assertTrue(s.getSuggestedReplenishQty() >= 0, "混合后补货量应非负");
        // blended = 0.7×200 + 0.3×ruleQty，ruleQty 来自规则路径（零库存高波动，必然 > 0）
        // 因此 blended 应明显 > 0.3×ruleQty，至少 > 100（因 0.7×200=140）
        assertTrue(s.getSuggestedReplenishQty() >= 100, "ML 权重 0.7×200=140 应主导补货量");
    }

    @Test
    @DisplayName("ML 混合：当 ML 预测需求 + 规则量均较大时，应取 round(0.7×ml + 0.3×rule) 精确值")
    void testHybridBlendedValue() {
        when(predictor.isModelLoaded()).thenReturn(true);
        when(salesHistoryMapper.selectList(any())).thenReturn(volatileSales());
        when(seasonalIndexMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(promotionCalendarMapper.selectList(any())).thenReturn(Collections.emptyList());

        // ML 预测需求 100，置信度 0.9
        when(predictor.predict(any())).thenReturn(new MlPrediction(100.0, 0.9, "v1"));

        ReplenishmentSuggestion s = engine.generateSuggestion(
                1L, "SKU-BLEND", "B0BL", "ELECTRONICS", 0, 14);

        // 取规则路径下的 ruleQty 做交叉验证：构造同样入参跑一次纯规则（通过临时关掉模型）
        when(predictor.isModelLoaded()).thenReturn(false);
        ReplenishmentSuggestion ruleOnly = engine.generateSuggestion(
                1L, "SKU-BLEND", "B0BL", "ELECTRONICS", 0, 14);
        int ruleQty = ruleOnly.getSuggestedReplenishQty();

        int expected = Math.max(0, (int) Math.round(0.7 * 100.0 + 0.3 * ruleQty));
        assertEquals(expected, s.getSuggestedReplenishQty(), "混合补货量应等于 round(0.7×ml + 0.3×rule)");
        assertEquals("HYBRID_ML_70_RULE_30", s.getBlendStrategy());
    }

    @Test
    @DisplayName("ML 混合：ML 预测极小且规则量为 0 时，补货量应兜底为 0（非负）")
    void testHybridNonNegativeFloor() {
        when(predictor.isModelLoaded()).thenReturn(true);
        when(salesHistoryMapper.selectList(any())).thenReturn(volatileSales());
        when(seasonalIndexMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(promotionCalendarMapper.selectList(any())).thenReturn(Collections.emptyList());

        // ML 预测需求 0，置信度 0.5
        when(predictor.predict(any())).thenReturn(new MlPrediction(0.0, 0.5, "v1"));

        // 库存极大 → 规则路径 ruleQty=0；blended = 0.7×0 + 0.3×0 = 0
        ReplenishmentSuggestion s = engine.generateSuggestion(
                1L, "SKU-FLOOR", "B0FL", "ELECTRONICS", 1_000_000, 14);

        assertEquals(0, s.getSuggestedReplenishQty(), "混合结果应兜底为 0");
        assertEquals("HYBRID_ML_70_RULE_30", s.getBlendStrategy());
        assertEquals(0.0, s.getMlPredictedDemand(), 0.0001);
    }

    @Test
    @DisplayName("混合补货建议应回填 SKU/ASIN/库存/紧急程度等元信息")
    void testSuggestionMetadataBackfilled() {
        when(predictor.isModelLoaded()).thenReturn(true);
        when(salesHistoryMapper.selectList(any())).thenReturn(volatileSales());
        when(seasonalIndexMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(promotionCalendarMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(predictor.predict(any())).thenReturn(new MlPrediction(200.0, 0.85, "v1"));

        ReplenishmentSuggestion s = engine.generateSuggestion(
                9L, "SKU-META", "B0META", "ELECTRONICS", 0, 14);

        assertEquals(9L, s.getShopId());
        assertEquals("SKU-META", s.getSku());
        assertEquals("B0META", s.getAsin());
        assertEquals(0, s.getCurrentTotalStock());
        assertNotNull(s.getBlendStrategy());
        // 高波动零库存 → URGENT
        assertEquals("URGENT", s.getUrgencyLevel());
    }
}
