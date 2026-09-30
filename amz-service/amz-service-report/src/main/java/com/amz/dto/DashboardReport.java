package com.amz.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 仪表盘综合报表 DTO（多维度可视化数据结构）。
 * <p>
 * 前端基于此数据渲染：
 * <ul>
 *   <li>核心指标卡片（KPI Card）：销售额/订单数/转化率/退货率</li>
 *   <li>销售额趋势折线图（salesTrend）</li>
 *   <li>流量来源饼图（trafficSource）</li>
 *   <li>品类销售柱状图（categorySales）</li>
 *   <li>退货率趋势（returnRateTrend）</li>
 * </ul>
 */
@Data
public class DashboardReport {

    /** 核心指标 */
    private BigDecimal totalSales;
    private Integer totalOrders;
    private BigDecimal conversionRate;   // 转化率（%）
    private BigDecimal returnRate;       // 退货率（%）
    private BigDecimal avgOrderValue;    // 客单价

    /** 销售额趋势（按天）：key=日期，value=销售额 */
    private Map<String, BigDecimal> salesTrend;

    /** 流量来源占比：key=来源，value=占比（%） */
    private Map<String, BigDecimal> trafficSource;

    /** 品类销售：key=品类，value=销售额 */
    private Map<String, BigDecimal> categorySales;

    /** 退货率趋势（按天）：key=日期，value=退货率（%） */
    private Map<String, BigDecimal> returnRateTrend;

    /** 转化率趋势（按天）：key=日期，value=转化率（%） */
    private Map<String, BigDecimal> conversionTrend;

    /** Top 10 畅销商品 */
    private List<TopProduct> topProducts;

    /**
     * 本次聚合里<b>没能真正取到数</b>的下游来源（如 {@code finance} / {@code order} / {@code ad}）。
     * <p>
     * 存在的意义：这些下游不可用时，指标仍会返回 0 / 空——那是 fallback 的兜底值，
     * 与"这个周期确实没有销售"在 JSON 里长得一模一样，经营决策会拿假零当真。
     * 空数组 = 所有来源都真实取到过数据；非空 = 对应指标是兜底值，不代表业务事实。
     */
    private List<String> degradedSources = new ArrayList<>();

    /**
     * 畅销商品明细。
     */
    @Data
    public static class TopProduct {
        private String asin;
        private String name;
        private Integer salesCount;
        private BigDecimal salesAmount;
    }
}
