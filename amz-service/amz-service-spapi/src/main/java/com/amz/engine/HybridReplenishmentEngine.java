package com.amz.engine;

import com.amz.engine.ml.MlDemandPredictor;
import com.amz.engine.ml.MlPrediction;
import com.amz.engine.ml.ReplenishmentFeatures;
import com.amz.mapper.SalesHistoryMapper;
import com.amz.model.ReplenishmentSuggestion;
import com.amz.model.SalesHistory;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 规则 + LightGBM 混合补货引擎。
 * <p>
 * 继承 {@link ReplenishmentEngine}，在规则引擎基础上叠加 ML 预测。
 * <pre>
 * 混合策略：
 *   1. 先调 super.generateSuggestion() 得到规则结果
 *   2. 如果 ML 模型已加载 且 CV > 0.6（高波动异常 pattern）：
 *      - 构建 9 维特征 → ONNX 推理得到 ML 预测需求量
 *      - 混合补货量 = ML 预测 × 0.7 + 规则建议 × 0.3
 *      - 记录 mlPredictedDemand / mlConfidence / blendStrategy=HYBRID_ML_70_RULE_30
 *   3. 否则（模型未加载或 CV ≤ 0.6）：直接返回规则结果，blendStrategy=RULE_ONLY
 * </pre>
 * 使用 @Primary 确保注入时优先选择混合引擎而非纯规则引擎。
 */
@Slf4j
@Component
@Primary
public class HybridReplenishmentEngine extends ReplenishmentEngine {

    /**
     * ML 触发阈值：CV 超过此值时启用 ML 混合预测。
     */
    private static final double ML_CV_THRESHOLD = 0.6;

    /**
     * ML 预测权重。
     */
    private static final double ML_WEIGHT = 0.7;

    /**
     * 规则预测权重。
     */
    private static final double RULE_WEIGHT = 0.3;

    /**
     * 混合策略标识：ML + 规则。
     */
    private static final String STRATEGY_HYBRID = "HYBRID_ML_70_RULE_30";

    /**
     * 策略标识：纯规则。
     */
    private static final String STRATEGY_RULE_ONLY = "RULE_ONLY";

    @Autowired
    private MlDemandPredictor predictor;

    /**
     * 父类的 salesHistoryMapper 为 private，子类无法访问，此处单独注入。
     */
    @Autowired
    private SalesHistoryMapper salesHistoryMapper;

    /**
     * 生成单个 SKU 的混合补货建议。
     * <p>
     * 先执行规则引擎，再根据 CV 和模型状态决定是否叠加 ML 预测。
     *
     * @param shopId            店铺 ID
     * @param sku               卖家 SKU
     * @param asin              ASIN
     * @param category          类目
     * @param currentTotalStock 当前总库存
     * @param leadTimeDays      采购周期（天）
     * @return 补货建议实体（含 ML 混合信息）
     */
    @Override
    public ReplenishmentSuggestion generateSuggestion(Long shopId, String sku, String asin,
                                                      String category, int currentTotalStock,
                                                      int leadTimeDays) {
        // 1. 先调规则引擎得到规则结果
        ReplenishmentSuggestion suggestion = super.generateSuggestion(
                shopId, sku, asin, category, currentTotalStock, leadTimeDays);

        // 2. 查询销量数据，计算 CV 和特征
        List<SalesHistory> histories = queryLast30DaysSalesHistory(shopId, sku);
        List<Integer> dailySales = extractQuantities(histories);
        double cv = calculateCV(dailySales);

        // 3. 判断是否使用 ML（模型未加载或 CV 未达阈值 → 纯规则）
        if (!predictor.isModelLoaded() || cv <= ML_CV_THRESHOLD) {
            suggestion.setBlendStrategy(STRATEGY_RULE_ONLY);
            log.debug("混合补货 → 纯规则路径: shopId={} sku={} cv={} modelLoaded={}",
                    shopId, sku, cv, predictor.isModelLoaded());
            return suggestion;
        }

        // 4. 构建 ML 特征
        LocalDate today = LocalDate.now();
        double dailyAvg7 = computeDailyAvg(histories, 7);
        double dailyAvg30 = computeDailyAvg(histories, 30);
        double trendSlope = computeTrendSlope(dailySales);
        double seasonalIndex = suggestion.getSeasonalIndex() != null
                ? suggestion.getSeasonalIndex().doubleValue() : 1.0;
        double promotionMultiplier = suggestion.getPromotionMultiplier() != null
                ? suggestion.getPromotionMultiplier().doubleValue() : 1.0;

        ReplenishmentFeatures features = ReplenishmentFeatures.builder()
                .dailyAvg7(dailyAvg7)
                .dailyAvg30(dailyAvg30)
                .cv(cv)
                .trendSlope(trendSlope)
                .seasonalIndex(seasonalIndex)
                .promotionMultiplier(promotionMultiplier)
                .currentStock(currentTotalStock)
                .leadTimeDays(leadTimeDays)
                .month(today.getMonthValue())
                .build();

        // 5. ML 预测
        MlPrediction mlPrediction = predictor.predict(features);

        // 6. 混合：ML 70% + 规则 30%（仅对 suggestedReplenishQty）
        int ruleQty = suggestion.getSuggestedReplenishQty() != null
                ? suggestion.getSuggestedReplenishQty() : 0;
        double blendedQty = ML_WEIGHT * mlPrediction.getPredictedDemand()
                + RULE_WEIGHT * ruleQty;
        int blendedInt = Math.max(0, (int) Math.round(blendedQty));

        suggestion.setSuggestedReplenishQty(blendedInt);
        suggestion.setMlPredictedDemand(mlPrediction.getPredictedDemand());
        suggestion.setMlConfidence(mlPrediction.getConfidence());
        suggestion.setBlendStrategy(STRATEGY_HYBRID);

        log.info("混合补货 → ML 混合路径: shopId={} sku={} cv={} mlDemand={} ruleQty={} blended={} confidence={}",
                shopId, sku, cv, mlPrediction.getPredictedDemand(), ruleQty, blendedInt,
                mlPrediction.getConfidence());

        return suggestion;
    }

    /**
     * 查询最近 30 天的销量历史（按日期升序）。
     *
     * @param shopId 店铺 ID
     * @param sku    卖家 SKU
     * @return 销量历史列表
     */
    private List<SalesHistory> queryLast30DaysSalesHistory(Long shopId, String sku) {
        LocalDate today = LocalDate.now();
        LocalDate start30 = today.minusDays(30);
        return salesHistoryMapper.selectList(
                new LambdaQueryWrapper<SalesHistory>()
                        .eq(SalesHistory::getShopId, shopId)
                        .eq(SalesHistory::getSku, sku)
                        .ge(SalesHistory::getSaleDate, start30)
                        .le(SalesHistory::getSaleDate, today)
                        .orderByAsc(SalesHistory::getSaleDate));
    }

    /**
     * 从销量历史中提取每日销量列表。
     *
     * @param histories 销量历史列表
     * @return 每日销量列表（null 数量按 0 处理）
     */
    private List<Integer> extractQuantities(List<SalesHistory> histories) {
        List<Integer> result = new ArrayList<>(histories.size());
        for (SalesHistory h : histories) {
            result.add(h.getQuantity() == null ? 0 : h.getQuantity());
        }
        return result;
    }

    /**
     * 计算最近 N 天的日均销量。
     * <p>
     * 与规则引擎保持一致：缺失日期按 0 销量处理，除以固定天数。
     *
     * @param histories 销量历史列表（最近 30 天）
     * @param days      天数（7 或 30）
     * @return 日均销量
     */
    private double computeDailyAvg(List<SalesHistory> histories, int days) {
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(days);
        int sum = 0;
        for (SalesHistory h : histories) {
            if (h.getSaleDate() != null && !h.getSaleDate().isBefore(start)) {
                sum += (h.getQuantity() == null ? 0 : h.getQuantity());
            }
        }
        return (double) sum / days;
    }

    /**
     * 简单线性回归计算销量趋势斜率。
     * <p>
     * x = 天索引（0, 1, 2, ..., n-1），y = 日销量。
     * slope = (n·Σxy - Σx·Σy) / (n·Σx² - (Σx)²)
     *
     * @param dailySales 每日销量列表（按日期升序）
     * @return 趋势斜率；数据不足或分母为 0 时返回 0.0
     */
    private double computeTrendSlope(List<Integer> dailySales) {
        int n = dailySales.size();
        if (n < 2) {
            return 0.0;
        }
        double sumX = 0.0;
        double sumY = 0.0;
        double sumXY = 0.0;
        double sumX2 = 0.0;
        for (int i = 0; i < n; i++) {
            double x = i;
            double y = dailySales.get(i) == null ? 0 : dailySales.get(i);
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
        }
        double denominator = n * sumX2 - sumX * sumX;
        if (denominator == 0.0) {
            return 0.0;
        }
        return (n * sumXY - sumX * sumY) / denominator;
    }
}
