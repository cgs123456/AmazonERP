package com.amz.service.impl;

import com.amz.client.feign.AdServiceFeignClient;
import com.amz.client.feign.FinanceServiceFeignClient;
import com.amz.client.feign.OrderServiceFeignClient;
import com.amz.dto.DashboardReport;
import com.amz.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 数据报表服务真实实现。
 * <p>
 * 仅在 {@code spring.profiles.active} 非 {@code mock} 时生效。
 * <p>
 * 通过 Feign 调用 order / product / ad / finance 微服务聚合数据：
 * <ul>
 *   <li>{@link OrderServiceFeignClient}：订单列表、月度利润汇总、利润报告（含每日 revenue）</li>
 *   <li>{@link AdServiceFeignClient}：广告汇总（花费、ACoS）</li>
 *   <li>{@link FinanceServiceFeignClient}：店铺利润（CNY）</li>
 * </ul>
 * 任一依赖服务不可用时降级为 0 / 空数组，保证报表可返回。
 * <p>
 * 多维报表（销售额趋势 / 店铺销售额分布）通过聚合订单/利润数据生成。
 */
@Slf4j
@Service
@Profile("!mock")
public class RealReportServiceImpl implements ReportService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 店铺分布饼图默认调色板（与前端 ECharts 主题对齐）。 */
    private static final String[] SHOP_DIST_COLORS = {
            "#5470C6", "#91CC75", "#FAC858", "#EE6666", "#73C0DE",
            "#3BA272", "#FC8452", "#9A60B4", "#EA7CCC", "#FFB000"
    };

    @Autowired
    private OrderServiceFeignClient orderFeignClient;

    @Autowired
    private AdServiceFeignClient adFeignClient;

    @Autowired
    private FinanceServiceFeignClient financeFeignClient;

    @Override
    public DashboardReport getDashboard(Long shopId, String dateRange) {
        int days = parseDays(dateRange);
        log.info("聚合生成仪表盘报表：shopId={} days={}", shopId, days);

        DashboardReport report = new DashboardReport();

        // 1. 财务服务：获取店铺利润（CNY 销售额）
        BigDecimal totalSales = fetchTotalSales(shopId, days);
        report.setTotalSales(totalSales);

        // 2. 订单服务：获取订单数
        Integer totalOrders = fetchTotalOrders(shopId);
        report.setTotalOrders(totalOrders);
        if (totalOrders != null && totalOrders > 0) {
            report.setAvgOrderValue(totalSales.divide(BigDecimal.valueOf(totalOrders), 2, RoundingMode.HALF_UP));
        }

        // 3. 广告服务：获取广告数据（用于 PPC 占比等，当前仅占位）
        Map<String, Object> adSummary = fetchAdSummary(shopId);

        // 4. 多维趋势：销售额按日聚合（直接复用 sales-trend 逻辑）
        Map<String, BigDecimal> salesTrendMap = new LinkedHashMap<>();
        for (Map<String, Object> item : getSalesTrend(shopId, days)) {
            Object day = item.get("day");
            Object value = item.get("value");
            if (day != null && value != null) {
                salesTrendMap.put(day.toString(), toBigDecimal(value));
            }
        }
        report.setSalesTrend(salesTrendMap);

        // 5. 占位：退货率 / 转化率 / 流量来源 / 品类销售 / Top 商品（依赖 spapi/ad 模块，当前不实现）
        report.setReturnRateTrend(new LinkedHashMap<>());
        report.setConversionTrend(new LinkedHashMap<>());
        report.setTrafficSource(new LinkedHashMap<>());
        report.setCategorySales(new LinkedHashMap<>());
        report.setTopProducts(Collections.emptyList());

        log.info("仪表盘报表聚合完成 shopId={} totalSales={} totalOrders={} adSummaryKeys={} salesTrendDays={}",
                shopId, totalSales, totalOrders,
                adSummary == null ? 0 : adSummary.size(),
                salesTrendMap.size());
        return report;
    }

    @Override
    public List<Map<String, Object>> getSalesTrend(Long shopId, Integer days) {
        int n = (days == null || days <= 0) ? 7 : days;
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(n - 1L);

        // 优先通过利润报告聚合（含每日 revenue，最准确）
        Map<String, BigDecimal> dailyRevenue = fetchDailyRevenueFromProfitReport(shopId, start, end);

        // 利润报告无数据时降级到订单列表按日聚合
        if (dailyRevenue.isEmpty()) {
            dailyRevenue = fetchDailyRevenueFromOrders(shopId, n);
        }

        // 按时间顺序补齐缺失日期为 0，保证前端折线图 X 轴连续
        List<Map<String, Object>> result = new ArrayList<>(n);
        for (int i = n - 1; i >= 0; i--) {
            LocalDate day = end.minusDays(i);
            String key = day.format(FMT);
            BigDecimal value = dailyRevenue.getOrDefault(key, BigDecimal.ZERO)
                    .setScale(2, RoundingMode.HALF_UP);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("day", key);
            item.put("value", value);
            result.add(item);
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> getShopDistribution(Long shopId) {
        // 通过订单服务获取近 30 天订单（含 orders 明细），按 shopId 聚合 finalPrice
        Map<Long, BigDecimal> shopSales = fetchShopSalesFromOrders(shopId, 30);
        if (shopSales.isEmpty()) {
            log.info("店铺销售额分布无数据：shopId={}", shopId);
            return Collections.emptyList();
        }

        BigDecimal total = shopSales.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            return Collections.emptyList();
        }

        // 按 销售额 倒序，计算占比，分配颜色
        List<Map<String, Object>> result = new ArrayList<>(shopSales.size());
        int idx = 0;
        for (Map.Entry<Long, BigDecimal> e : sortByValueDesc(shopSales).entrySet()) {
            BigDecimal percent = e.getValue()
                    .multiply(BigDecimal.valueOf(100))
                    .divide(total, 2, RoundingMode.HALF_UP);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", "店铺 #" + e.getKey());
            item.put("shopId", e.getKey());
            item.put("sales", e.getValue().setScale(2, RoundingMode.HALF_UP));
            item.put("percent", percent);
            item.put("color", SHOP_DIST_COLORS[idx % SHOP_DIST_COLORS.length]);
            result.add(item);
            idx++;
        }
        return result;
    }

    // ===== Feign 聚合方法（含降级） =====

    /**
     * 通过 Feign 调用 finance 服务获取销售额（CNY）。
     * 服务不可用时降级为 0。
     */
    private BigDecimal fetchTotalSales(Long shopId, int days) {
        try {
            LocalDate end = LocalDate.now();
            LocalDate start = end.minusDays(days);
            Map<String, Object> resp = financeFeignClient.calculateProfit(shopId, start.format(FMT), end.format(FMT));
            return extractBigDecimal(resp);
        } catch (Exception e) {
            log.warn("调用 finance 服务获取销售额失败，降级为 0：shopId={}", shopId, e);
            return BigDecimal.ZERO;
        }
    }

    /**
     * 通过 Feign 调用 order 服务获取订单数。
     * 服务不可用时降级为 0。
     */
    private Integer fetchTotalOrders(Long shopId) {
        try {
            Map<String, Object> resp = orderFeignClient.getProfitSummary(shopId);
            return extractInt(resp);
        } catch (Exception e) {
            log.warn("调用 order 服务获取订单数失败，降级为 0：shopId={}", shopId, e);
            return 0;
        }
    }

    /**
     * 通过 Feign 调用 ad 服务获取广告汇总。
     * 服务不可用时返回 null。
     */
    private Map<String, Object> fetchAdSummary(Long shopId) {
        try {
            return adFeignClient.getShopSummary(shopId);
        } catch (Exception e) {
            log.warn("调用 ad 服务获取广告汇总失败，跳过：shopId={}", shopId, e);
            return null;
        }
    }

    /**
     * 通过利润报告聚合每日 revenue（首选数据源）。
     */
    private Map<String, BigDecimal> fetchDailyRevenueFromProfitReport(Long shopId, LocalDate start, LocalDate end) {
        Map<String, BigDecimal> dailyRevenue = new TreeMap<>();
        try {
            Map<String, Object> resp = orderFeignClient.getProfitReport(shopId, start.format(FMT), end.format(FMT));
            Object data = extractData(resp);
            if (!(data instanceof Map)) {
                return dailyRevenue;
            }
            Object reportsObj = ((Map<String, Object>) data).get("reports");
            if (!(reportsObj instanceof List)) {
                return dailyRevenue;
            }
            for (Object r : (List<?>) reportsObj) {
                if (!(r instanceof Map)) {
                    continue;
                }
                Map<String, Object> report = (Map<String, Object>) r;
                Object statDate = report.get("statDate");
                Object revenue = report.get("revenue");
                if (statDate == null || revenue == null) {
                    continue;
                }
                String day = statDate.toString();
                if (day.length() >= 10) {
                    day = day.substring(0, 10);
                }
                dailyRevenue.merge(day, toBigDecimal(revenue), BigDecimal::add);
            }
        } catch (Exception e) {
            log.warn("调用 order 服务获取利润报告失败，降级到订单列表聚合：shopId={}", shopId, e);
        }
        return dailyRevenue;
    }

    /**
     * 通过订单列表聚合每日销售额（降级数据源，当利润报告无数据时使用）。
     */
    private Map<String, BigDecimal> fetchDailyRevenueFromOrders(Long shopId, int days) {
        Map<String, BigDecimal> dailyRevenue = new TreeMap<>();
        try {
            Map<String, Object> resp = orderFeignClient.listOrders(shopId, days);
            Object data = extractData(resp);
            if (!(data instanceof Map)) {
                return dailyRevenue;
            }
            Object ordersObj = ((Map<String, Object>) data).get("orders");
            if (!(ordersObj instanceof List)) {
                return dailyRevenue;
            }
            for (Object o : (List<?>) ordersObj) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<String, Object> order = (Map<String, Object>) o;
                Object purchaseDate = order.get("purchaseDate");
                Object finalPrice = order.get("finalPrice");
                if (purchaseDate == null || finalPrice == null) {
                    continue;
                }
                String day = purchaseDate.toString();
                if (day.length() >= 10) {
                    day = day.substring(0, 10);
                }
                dailyRevenue.merge(day, toBigDecimal(finalPrice), BigDecimal::add);
            }
        } catch (Exception e) {
            log.warn("调用 order 服务获取订单列表失败，趋势数据返回 0：shopId={}", shopId, e);
        }
        return dailyRevenue;
    }

    /**
     * 通过订单列表按 shopId 聚合销售额。
     * 当 shopId 非空时仅返回该店铺；为空时聚合全部用户订单。
     */
    private Map<Long, BigDecimal> fetchShopSalesFromOrders(Long shopId, int days) {
        Map<Long, BigDecimal> shopSales = new LinkedHashMap<>();
        try {
            Map<String, Object> resp;
            if (shopId != null) {
                resp = orderFeignClient.listOrders(shopId, days);
            } else {
                // 全部店铺：调用 getOrderList（依赖 order 服务的 UserContext 传递）
                resp = orderFeignClient.getOrderList();
            }
            Object data = extractData(resp);
            List<?> orders = extractOrders(data);
            if (orders == null) {
                return shopSales;
            }
            for (Object o : orders) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<String, Object> order = (Map<String, Object>) o;
                Object sid = order.get("shopId");
                Object finalPrice = order.get("finalPrice");
                if (sid == null || finalPrice == null) {
                    continue;
                }
                Long key = toLong(sid);
                if (key == null) {
                    continue;
                }
                shopSales.merge(key, toBigDecimal(finalPrice), BigDecimal::add);
            }
        } catch (Exception e) {
            log.warn("调用 order 服务获取店铺销售分布失败，返回空：shopId={}", shopId, e);
        }
        return shopSales;
    }

    /**
     * 从 Result 包装的响应 Map 中提取 data 字段。
     * Result JSON 结构：{@code {"code":200, "message":"...", "data":...}}
     */
    private Object extractData(Map<String, Object> resp) {
        if (resp == null) {
            return null;
        }
        return resp.get("data");
    }

    /**
     * 从 data 中提取 orders 列表，兼容两种结构：
     * - data 本身是 List（getOrderList 返回 Result&lt;List&lt;Order&gt;&gt;）
     * - data 是 Map 且包含 orders 字段（listOrders 返回 summary）
     */
    @SuppressWarnings("unchecked")
    private List<?> extractOrders(Object data) {
        if (data instanceof List) {
            return (List<?>) data;
        }
        if (data instanceof Map) {
            Object orders = ((Map<String, Object>) data).get("orders");
            if (orders instanceof List) {
                return (List<?>) orders;
            }
        }
        return null;
    }

    private BigDecimal extractBigDecimal(Map<String, Object> resp) {
        Object data = extractData(resp);
        return toBigDecimal(data);
    }

    private Integer extractInt(Map<String, Object> resp) {
        Object data = extractData(resp);
        if (data == null) {
            return 0;
        }
        if (data instanceof Number) {
            return ((Number) data).intValue();
        }
        if (data instanceof List) {
            return ((List<?>) data).size();
        }
        // 月度利润汇总返回 List&lt;Map&gt;：以 SKU 数量近似订单数
        if (data instanceof Map) {
            Object summary = ((Map<String, Object>) data).get("summary");
            if (summary instanceof Number) {
                return ((Number) summary).intValue();
            }
        }
        try {
            return Integer.parseInt(data.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof Number) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private <V extends Comparable<V>> Map<Long, V> sortByValueDesc(Map<Long, V> map) {
        List<Map.Entry<Long, V>> list = new ArrayList<>(map.entrySet());
        list.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        Map<Long, V> sorted = new LinkedHashMap<>();
        for (Map.Entry<Long, V> e : list) {
            sorted.put(e.getKey(), e.getValue());
        }
        return sorted;
    }

    private int parseDays(String dateRange) {
        if (dateRange == null) return 7;
        switch (dateRange) {
            case "30d": return 30;
            case "90d": return 90;
            default: return 7;
        }
    }
}
