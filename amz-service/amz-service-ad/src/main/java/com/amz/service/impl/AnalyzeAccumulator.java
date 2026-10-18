package com.amz.service.impl;

import com.amz.model.AdSearchTerm;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.stream.Collectors;

/**
 * 搜索词分析的单遍累加器：一次扫描同时产出分类计数、总额与 TOP-N 明细。
 * <p>
 * 从 {@link SearchTermServiceImpl} 拆出（§S33.86）：这是纯粹的统计聚合，
 * 不碰数据库、不碰租户上下文，只吃 {@link AdSearchTerm} 吐计数与列表。
 * 拆出后服务类只管扫描编排与响应组装。
 * <p>
 * 分类口径（三者在业务上互不替代，判定条件也不同）：
 * <ul>
 *   <li><b>converting</b>：有订单（orders &gt; 0）；</li>
 *   <li><b>waste</b>：零点击且曝光超过阈值——钱花了但没人点；</li>
 *   <li><b>highAcos</b>：ACoS ≥ 40，<b>或</b>花了钱却零销售（ACoS 为无穷大）。
 *       后者必须单列：库里的 acos 列在 sales ≤ 0 时不赋值，只看
 *       {@code acos != null} 会把最该处理的词整个漏掉（§S33.86）。</li>
 * </ul>
 */
final class AnalyzeAccumulator {

    static final BigDecimal HIGH_ACOS = new BigDecimal("40");
    static final BigDecimal LOW_CR = new BigDecimal("3");
    static final long WASTE_IMPRESSION_THRESHOLD = 1000;
    static final int TOP_LIMIT = 10;

    private long totalTerms;
    private long convertingTerms;
    private long wasteTerms;
    private long highAcosTerms;
    private long lowCrTerms;
    private BigDecimal totalCost = BigDecimal.ZERO;
    private BigDecimal totalSales = BigDecimal.ZERO;
    private BigDecimal wasteCost = BigDecimal.ZERO;
    private final PriorityQueue<AdSearchTerm> topConverting = new PriorityQueue<>(
            Comparator.comparingInt(AnalyzeAccumulator::safeOrders)
                    .thenComparing(t -> safeMoney(t.getSales())));
    private final PriorityQueue<AdSearchTerm> topWaste = new PriorityQueue<>(
            Comparator.comparing((AdSearchTerm t) -> safeMoney(t.getCost())));

    void accept(AdSearchTerm term) {
        if (term == null) {
            return;
        }
        totalTerms++;
        totalCost = totalCost.add(safeMoney(term.getCost()));
        totalSales = totalSales.add(safeMoney(term.getSales()));

        if (safeOrders(term) > 0) {
            convertingTerms++;
            if (term.getCr() != null && term.getCr().compareTo(LOW_CR) < 0) {
                lowCrTerms++;
            }
            offer(topConverting, term, TOP_LIMIT);
        }
        if ((term.getClicks() == null || term.getClicks() == 0)
                && term.getImpressions() != null && term.getImpressions() > WASTE_IMPRESSION_THRESHOLD) {
            wasteTerms++;
            wasteCost = wasteCost.add(safeMoney(term.getCost()));
        }
        // §S33.86：花了钱、零销售额的词，ACoS 是「无穷大」而不是「没有 ACoS」。
        // 库里的 acos 列在 sales<=0 时不赋值（calculateMetrics 只在 sales>0 时计算），
        // 旧判定 `acos != null && acos >= 40` 会把它整个漏掉；而这类词又因 clicks>0
        // 进不了 wasteTerms（那里要求 0 点击）——于是最该处理的词不出现在任何汇总计数里，
        // 只在 topWaste 明细列表露脸。运营看 highAcosTerms=0 会以为没有高 ACoS 词。
        boolean spentWithoutSales = safeOrders(term) == 0
                && term.getCost() != null && term.getCost().compareTo(BigDecimal.ZERO) > 0
                && safeMoney(term.getSales()).compareTo(BigDecimal.ZERO) <= 0;
        if (spentWithoutSales
                || (term.getAcos() != null && term.getAcos().compareTo(HIGH_ACOS) >= 0)) {
            highAcosTerms++;
        }
        if (safeOrders(term) == 0 && term.getCost() != null
                && term.getCost().compareTo(BigDecimal.ZERO) > 0) {
            offer(topWaste, term, TOP_LIMIT);
        }
    }

    long totalTerms() {
        return totalTerms;
    }

    long convertingTerms() {
        return convertingTerms;
    }

    long wasteTerms() {
        return wasteTerms;
    }

    long highAcosTerms() {
        return highAcosTerms;
    }

    long lowCrTerms() {
        return lowCrTerms;
    }

    BigDecimal totalCost() {
        return totalCost;
    }

    BigDecimal totalSales() {
        return totalSales;
    }

    BigDecimal wasteCost() {
        return wasteCost;
    }

    List<Map<String, Object>> topConvertingTerms() {
        return topConverting.stream()
                .sorted(Comparator.comparingInt(AnalyzeAccumulator::safeOrders)
                        .thenComparing(t -> safeMoney(t.getSales()))
                        .reversed())
                .map(term -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("searchTerm", term.getSearchTerm());
                    map.put("orders", term.getOrders());
                    map.put("sales", term.getSales());
                    map.put("cost", term.getCost());
                    map.put("acos", term.getAcos());
                    return map;
                })
                .collect(Collectors.toList());
    }

    List<Map<String, Object>> topWasteTerms() {
        return topWaste.stream()
                .sorted(Comparator.comparing((AdSearchTerm t) -> safeMoney(t.getCost())).reversed())
                .map(term -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("searchTerm", term.getSearchTerm());
                    map.put("cost", term.getCost());
                    map.put("impressions", term.getImpressions());
                    map.put("clicks", term.getClicks());
                    return map;
                })
                .collect(Collectors.toList());
    }

    private static <T> void offer(PriorityQueue<T> queue, T value, int limit) {
        queue.offer(value);
        if (queue.size() > limit) {
            queue.poll();
        }
    }

    private static int safeOrders(AdSearchTerm term) {
        return term.getOrders() != null ? term.getOrders() : 0;
    }

    private static BigDecimal safeMoney(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
