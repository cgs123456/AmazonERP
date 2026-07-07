package com.amz.engine;

import com.amz.mapper.PromotionCalendarMapper;
import com.amz.mapper.SalesHistoryMapper;
import com.amz.mapper.SeasonalIndexMapper;
import com.amz.model.PromotionCalendar;
import com.amz.model.ReplenishmentSuggestion;
import com.amz.model.SalesHistory;
import com.amz.model.SeasonalIndex;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 智能补货引擎。
 * <p>
 * 基于销量历史、变异系数、季节性指数、促销日历四大维度计算 SKU 补货建议。
 * <pre>
 * 算法：
 *   baseline       = 7天日均 × 0.7 + 30天日均 × 0.3
 *   safetyFactor   = 1.10 / 1.20 / 1.35（CV &lt; 0.3 / 0.3-0.6 / &gt; 0.6）
 *   seasonalIndex  = amz_seasonal_index WHERE category=? AND month=当前月
 *   promoMultiplier= amz_promotion_calendar 未来14天是否有促销
 *   adjusted       = baseline × safetyFactor × seasonalIndex × promoMultiplier
 *   leadTimeDemand = adjusted × leadTimeDays / 14
 *   suggested      = max(0, ceil(adjusted + leadTimeDemand - currentTotalStock))
 *   stockoutDate   = today + ceil(currentTotalStock / baseline)
 *   urgencyLevel   = suggested==0 ? LOW
 *                    : baseline×14 &gt; currentTotalStock ? URGENT : NORMAL
 * </pre>
 */
@Component
public class ReplenishmentEngine {

    private static final Logger log = LoggerFactory.getLogger(ReplenishmentEngine.class);

    /**
     * 7 天日均权重。
     */
    private static final BigDecimal WEIGHT_7_DAYS = BigDecimal.valueOf(0.7);

    /**
     * 30 天日均权重。
     */
    private static final BigDecimal WEIGHT_30_DAYS = BigDecimal.valueOf(0.3);

    /**
     * 预测窗口（天）。
     */
    private static final int FORECAST_WINDOW_DAYS = 14;

    /**
     * 默认季节性指数（查表无果时使用）。
     */
    private static final BigDecimal DEFAULT_SEASONAL_INDEX = BigDecimal.ONE;

    /**
     * 默认促销乘数（无促销时使用）。
     */
    private static final BigDecimal DEFAULT_PROMOTION_MULTIPLIER = BigDecimal.ONE;

    /**
     * CV 阈值：低波动上限。
     */
    private static final double CV_LOW = 0.3;

    /**
     * CV 阈值：中波动上限。
     */
    private static final double CV_MID = 0.6;

    /**
     * 安全系数：低波动。
     */
    private static final BigDecimal SAFETY_LOW = BigDecimal.valueOf(1.10);

    /**
     * 安全系数：中波动。
     */
    private static final BigDecimal SAFETY_MID = BigDecimal.valueOf(1.20);

    /**
     * 安全系数：高波动。
     */
    private static final BigDecimal SAFETY_HIGH = BigDecimal.valueOf(1.35);

    @Autowired
    private SalesHistoryMapper salesHistoryMapper;

    @Autowired
    private SeasonalIndexMapper seasonalIndexMapper;

    @Autowired
    private PromotionCalendarMapper promotionCalendarMapper;

    /**
     * 生成单个 SKU 的补货建议。
     *
     * @param shopId            店铺 ID
     * @param sku               卖家 SKU
     * @param asin              ASIN
     * @param category          类目（用于查季节性指数）
     * @param currentTotalStock 当前总库存
     * @param leadTimeDays      采购周期（天）
     * @return 补货建议实体（未落库）
     */
    public ReplenishmentSuggestion generateSuggestion(Long shopId, String sku, String asin,
                                                      String category, int currentTotalStock,
                                                      int leadTimeDays) {
        LocalDate today = LocalDate.now();
        ReplenishmentSuggestion suggestion = new ReplenishmentSuggestion();
        suggestion.setShopId(shopId);
        suggestion.setSku(sku);
        suggestion.setAsin(asin);
        suggestion.setStatDate(today);
        suggestion.setCurrentTotalStock(currentTotalStock);

        // 1. 基线需求 = 7天日均 × 0.7 + 30天日均 × 0.3
        BigDecimal baseline = forecastNext14Days(shopId, sku);
        suggestion.setBaselineDemand(baseline);

        // 2. CV 变异系数 → 安全系数
        List<Integer> dailySales = queryLast30DaysDailySales(shopId, sku);
        double cv = calculateCV(dailySales);
        BigDecimal safetyFactor = getSafetyFactor(cv);
        suggestion.setSafetyFactor(safetyFactor);

        // 3. 季节性指数
        BigDecimal seasonalIndex = getSeasonalIndex(category, today.getMonthValue());
        suggestion.setSeasonalIndex(seasonalIndex);

        // 4. 促销乘数
        BigDecimal promotionMultiplier = getActivePromotionMultiplier(category);
        suggestion.setPromotionMultiplier(promotionMultiplier);

        // 5. 调整后需求 = baseline × 安全系数 × 季节性 × 促销
        BigDecimal adjusted = baseline
                .multiply(safetyFactor)
                .multiply(seasonalIndex)
                .multiply(promotionMultiplier)
                .setScale(4, RoundingMode.HALF_UP);

        // 6. 备货期内需求 = adjusted × leadTimeDays / 14
        BigDecimal leadTimeDemand = adjusted
                .multiply(BigDecimal.valueOf(leadTimeDays))
                .divide(BigDecimal.valueOf(FORECAST_WINDOW_DAYS), 4, RoundingMode.HALF_UP);

        // 7. 建议补货量 = max(0, ceil(adjusted + leadTimeDemand - currentTotalStock))
        long rawSuggested = (long) Math.ceil(
                adjusted.add(leadTimeDemand)
                        .subtract(BigDecimal.valueOf(currentTotalStock))
                        .doubleValue());
        int suggested = rawSuggested < 0 ? 0 : (int) rawSuggested;
        suggestion.setSuggestedReplenishQty(suggested);

        // 8. 预计断货日期 = today + ceil(currentTotalStock / baseline)
        if (baseline.compareTo(BigDecimal.ZERO) > 0) {
            long daysToStockout = (long) Math.ceil(
                    BigDecimal.valueOf(currentTotalStock)
                            .divide(baseline, 4, RoundingMode.HALF_UP)
                            .doubleValue());
            suggestion.setEstimatedStockoutDate(today.plusDays(daysToStockout));
        } else {
            // 无销量数据，无法预估断货日期
            suggestion.setEstimatedStockoutDate(null);
        }

        // 9. 紧急程度判定
        String urgencyLevel;
        if (suggested == 0) {
            urgencyLevel = "LOW";
        } else if (baseline.multiply(BigDecimal.valueOf(FORECAST_WINDOW_DAYS))
                .compareTo(BigDecimal.valueOf(currentTotalStock)) > 0) {
            urgencyLevel = "URGENT";
        } else {
            urgencyLevel = "NORMAL";
        }
        suggestion.setUrgencyLevel(urgencyLevel);

        log.debug("generateSuggestion shopId={} sku={} baseline={} cv={} safety={} seasonal={} promo={} "
                        + "adjusted={} suggested={} urgency={}",
                shopId, sku, baseline, cv, safetyFactor, seasonalIndex, promotionMultiplier,
                adjusted, suggested, urgencyLevel);
        return suggestion;
    }

    /**
     * 计算变异系数 CV = stddev / mean。
     *
     * @param dailySales 每日销量列表
     * @return CV 值，无数据或均值为 0 时返回 0.0
     */
    public double calculateCV(List<Integer> dailySales) {
        if (dailySales == null || dailySales.isEmpty()) {
            return 0.0;
        }
        int n = dailySales.size();
        double sum = 0.0;
        for (Integer q : dailySales) {
            sum += (q == null ? 0 : q);
        }
        double mean = sum / n;
        if (mean == 0.0) {
            return 0.0;
        }
        double variance = 0.0;
        for (Integer q : dailySales) {
            double diff = (q == null ? 0 : q) - mean;
            variance += diff * diff;
        }
        variance /= n;
        double stddev = Math.sqrt(variance);
        return stddev / mean;
    }

    /**
     * 根据 CV 变异系数返回安全系数。
     * <ul>
     *   <li>CV &lt; 0.3   → 1.10（低波动）</li>
     *   <li>0.3 ≤ CV ≤ 0.6 → 1.20（中波动）</li>
     *   <li>CV &gt; 0.6   → 1.35（高波动）</li>
     * </ul>
     *
     * @param cv 变异系数
     * @return 安全系数
     */
    public BigDecimal getSafetyFactor(double cv) {
        if (cv < CV_LOW) {
            return SAFETY_LOW;
        }
        if (cv <= CV_MID) {
            return SAFETY_MID;
        }
        return SAFETY_HIGH;
    }

    /**
     * 查 SalesHistory 表计算 baseline 基线需求（7天日均 × 0.7 + 30天日均 × 0.3）。
     *
     * @param shopId 店铺 ID
     * @param sku    卖家 SKU
     * @return 基线日均需求；无数据返回 0
     */
    public BigDecimal forecastNext14Days(Long shopId, String sku) {
        LocalDate today = LocalDate.now();
        LocalDate start30 = today.minusDays(30);
        LocalDate start7 = today.minusDays(7);

        List<SalesHistory> histories = salesHistoryMapper.selectList(
                new LambdaQueryWrapper<SalesHistory>()
                        .eq(SalesHistory::getShopId, shopId)
                        .eq(SalesHistory::getSku, sku)
                        .ge(SalesHistory::getSaleDate, start30)
                        .le(SalesHistory::getSaleDate, today));

        int sum7 = 0;
        int sum30 = 0;
        for (SalesHistory h : histories) {
            int qty = h.getQuantity() == null ? 0 : h.getQuantity();
            sum30 += qty;
            if (h.getSaleDate() != null && !h.getSaleDate().isBefore(start7)) {
                sum7 += qty;
            }
        }

        BigDecimal daily7 = BigDecimal.valueOf(sum7)
                .divide(BigDecimal.valueOf(7), 4, RoundingMode.HALF_UP);
        BigDecimal daily30 = BigDecimal.valueOf(sum30)
                .divide(BigDecimal.valueOf(30), 4, RoundingMode.HALF_UP);
        return daily7.multiply(WEIGHT_7_DAYS)
                .add(daily30.multiply(WEIGHT_30_DAYS))
                .setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 查询最近 30 天的每日销量列表，用于 CV 计算。
     *
     * @param shopId 店铺 ID
     * @param sku    卖家 SKU
     * @return 每日销量列表
     */
    private List<Integer> queryLast30DaysDailySales(Long shopId, String sku) {
        LocalDate today = LocalDate.now();
        LocalDate start30 = today.minusDays(30);
        List<SalesHistory> histories = salesHistoryMapper.selectList(
                new LambdaQueryWrapper<SalesHistory>()
                        .eq(SalesHistory::getShopId, shopId)
                        .eq(SalesHistory::getSku, sku)
                        .ge(SalesHistory::getSaleDate, start30)
                        .le(SalesHistory::getSaleDate, today));
        List<Integer> result = new ArrayList<>(histories.size());
        for (SalesHistory h : histories) {
            result.add(h.getQuantity() == null ? 0 : h.getQuantity());
        }
        return result;
    }

    /**
     * 查询类目在指定月份的季节性指数。
     *
     * @param category 类目
     * @param month    月份 1-12
     * @return 季节性指数；查表无果返回 1.0
     */
    public BigDecimal getSeasonalIndex(String category, int month) {
        if (category == null) {
            return DEFAULT_SEASONAL_INDEX;
        }
        List<SeasonalIndex> list = seasonalIndexMapper.selectList(
                new LambdaQueryWrapper<SeasonalIndex>()
                        .eq(SeasonalIndex::getCategory, category)
                        .eq(SeasonalIndex::getMonth, month));
        if (list.isEmpty() || list.get(0).getSeasonalIndex() == null) {
            return DEFAULT_SEASONAL_INDEX;
        }
        return list.get(0).getSeasonalIndex();
    }

    /**
     * 查询未来 14 天内是否有促销活动，返回最大促销乘数。
     * <p>
     * 重叠条件：promotion.start_date ≤ today+14 AND promotion.end_date ≥ today。
     * 无促销时返回 1.0。
     *
     * @param category 类目（当前促销日历未按类目区分，参数保留以备扩展）
     * @return 促销乘数
     */
    public BigDecimal getActivePromotionMultiplier(String category) {
        LocalDate today = LocalDate.now();
        LocalDate windowEnd = today.plusDays(FORECAST_WINDOW_DAYS);
        List<PromotionCalendar> promotions = promotionCalendarMapper.selectList(
                new LambdaQueryWrapper<PromotionCalendar>()
                        .le(PromotionCalendar::getStartDate, windowEnd)
                        .ge(PromotionCalendar::getEndDate, today));
        if (promotions.isEmpty()) {
            return DEFAULT_PROMOTION_MULTIPLIER;
        }
        // 多个促销重叠时取最大乘数，按最激进的备货策略处理
        BigDecimal max = DEFAULT_PROMOTION_MULTIPLIER;
        for (PromotionCalendar p : promotions) {
            if (p.getMultiplier() != null && p.getMultiplier().compareTo(max) > 0) {
                max = p.getMultiplier();
            }
        }
        return max;
    }
}
