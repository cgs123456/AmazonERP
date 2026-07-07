package com.amz.analytics;

import com.amz.model.AdReport;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 广告性能分析器：计算 ACoS / ROAS / CTR / CR / CPC，并给出健康度分级。
 * <p>
 * 分级阈值（参考领星/船长BI 行业经验）：
 * <ul>
 *   <li>EXCELLENT：ACoS &lt; 15%</li>
 *   <li>HEALTHY：15% ≤ ACoS &lt; 25%</li>
 *   <li>WARNING：25% ≤ ACoS &lt; 40%</li>
 *   <li>CRITICAL：ACoS ≥ 40%（吃掉大部分利润，需立即干预）</li>
 * </ul>
 */
@Component
public class AdPerformanceAnalyzer {

    /** ACoS 分级阈值（百分比） */
    private static final BigDecimal ACOS_EXCELLENT = new BigDecimal("15");
    private static final BigDecimal ACOS_HEALTHY = new BigDecimal("25");
    private static final BigDecimal ACOS_WARNING = new BigDecimal("40");

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * 填充报表派生指标（ACoS/ROAS/CTR/CR/CPC），原地修改 report。
     * 容错：避免除零。
     */
    public void fillDerivedMetrics(AdReport r) {
        if (r == null) {
            return;
        }
        // ACoS = cost / sales × 100
        if (r.getSales() != null && r.getSales().compareTo(BigDecimal.ZERO) > 0) {
            r.setAcos(r.getCost().divide(r.getSales(), 4, RoundingMode.HALF_UP)
                    .multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP));
            r.setRoas(r.getSales().divide(r.getCost(), 2, RoundingMode.HALF_UP));
        } else {
            r.setAcos(null);
            r.setRoas(null);
        }
        // CTR = clicks / impressions × 100
        if (r.getImpressions() != null && r.getImpressions() > 0 && r.getClicks() != null) {
            r.setCtr(BigDecimal.valueOf(r.getClicks())
                    .divide(BigDecimal.valueOf(r.getImpressions()), 4, RoundingMode.HALF_UP)
                    .multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP));
        }
        // CR = orders / clicks × 100
        if (r.getClicks() != null && r.getClicks() > 0 && r.getOrders() != null) {
            r.setCr(BigDecimal.valueOf(r.getOrders())
                    .divide(BigDecimal.valueOf(r.getClicks()), 4, RoundingMode.HALF_UP)
                    .multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP));
        }
        // CPC = cost / clicks
        if (r.getClicks() != null && r.getClicks() > 0 && r.getCost() != null) {
            r.setCpc(r.getCost().divide(BigDecimal.valueOf(r.getClicks()), 2, RoundingMode.HALF_UP));
        }
    }

    /**
     * 对批量报表填充派生指标。
     */
    public void analyzeAll(List<AdReport> reports) {
        if (reports == null) {
            return;
        }
        reports.forEach(this::fillDerivedMetrics);
    }

    /**
     * 按 ACoS 给出健康度分级。
     *
     * @return EXCELLENT / HEALTHY / WARNING / CRITICAL / UNKNOWN
     */
    public String gradeByAcos(AdReport r) {
        if (r == null || r.getAcos() == null) {
            return "UNKNOWN";
        }
        BigDecimal acos = r.getAcos();
        if (acos.compareTo(ACOS_EXCELLENT) < 0) {
            return "EXCELLENT";
        } else if (acos.compareTo(ACOS_HEALTHY) < 0) {
            return "HEALTHY";
        } else if (acos.compareTo(ACOS_WARNING) < 0) {
            return "WARNING";
        }
        return "CRITICAL";
    }

    /**
     * 汇总店铺整体广告指标（聚合所有活动）。
     */
    public AdReport summarize(List<AdReport> reports) {
        if (reports == null || reports.isEmpty()) {
            return null;
        }
        AdReport sum = new AdReport();
        long impressions = 0, clicks = 0;
        int orders = 0;
        BigDecimal cost = BigDecimal.ZERO, sales = BigDecimal.ZERO;
        for (AdReport r : reports) {
            if (r.getImpressions() != null) impressions += r.getImpressions();
            if (r.getClicks() != null) clicks += r.getClicks();
            if (r.getOrders() != null) orders += r.getOrders();
            if (r.getCost() != null) cost = cost.add(r.getCost());
            if (r.getSales() != null) sales = sales.add(r.getSales());
        }
        sum.setImpressions(impressions);
        sum.setClicks(clicks);
        sum.setOrders(orders);
        sum.setCost(cost);
        sum.setSales(sales);
        fillDerivedMetrics(sum);
        return sum;
    }
}
