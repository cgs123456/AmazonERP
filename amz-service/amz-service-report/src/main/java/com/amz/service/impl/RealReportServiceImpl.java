package com.amz.service.impl;

import com.amz.client.feign.AdServiceFeignClient;
import com.amz.client.feign.FinanceServiceFeignClient;
import com.amz.client.feign.OrderServiceFeignClient;
import com.amz.dto.DashboardReport;
import com.amz.service.ReportService;
import com.amz.util.MapArgUtils;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 数据报表服务真实实现。
 * <p>
 * 仅在 {@code spring.profiles.active} 非 {@code mock} 时生效。
 * <p>
 * 通过 Feign 调用 order / product / ad / finance 微服务聚合数据：
 * <ul>
 *   <li>{@link OrderServiceFeignClient}：订单列表（含订单总数）、利润报告（含每日 revenue）</li>
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
        // 每次调用独立收集降级来源：@Service 是单例，用实例字段会让并发请求互相污染
        Set<String> degraded = new LinkedHashSet<>();

        // 1. 财务服务：获取店铺利润（CNY 销售额）
        BigDecimal totalSales = fetchTotalSales(shopId, days, degraded);
        report.setTotalSales(totalSales);

        // 2. 订单服务：获取订单数（与 totalSales 同窗口）
        Integer totalOrders = fetchTotalOrders(shopId, days, degraded);
        report.setTotalOrders(totalOrders);
        if (totalOrders != null && totalOrders > 0) {
            report.setAvgOrderValue(totalSales.divide(BigDecimal.valueOf(totalOrders), 2, RoundingMode.HALF_UP));
        }

        // 3. 广告服务：获取广告数据（用于 PPC 占比等，当前仅占位）
        Map<String, Object> adSummary = fetchAdSummary(shopId, degraded);

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

        report.setDegradedSources(new ArrayList<>(degraded));
        if (!degraded.isEmpty()) {
            log.warn("仪表盘存在兜底指标，这些数值不代表业务事实：shopId={} degraded={} totalSales={} totalOrders={}",
                    shopId, degraded, totalSales, totalOrders);
        }

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

    /** 降级来源标识，写进 {@code DashboardReport.degradedSources}。 */
    private static final String SOURCE_FINANCE = "finance";
    private static final String SOURCE_ORDER = "order";
    private static final String SOURCE_AD = "ad";

    /**
     * 把"下游没取到数"的来源记进降级集合。
     * <p>
     * 必须存在：本模块三个 Feign 客户端的 fallbackFactory 返回 {@code Collections.emptyMap()}，
     * 而熔断装配是开着的 —— 下游不可用时<b>不会抛异常</b>，调用方的 catch 根本不触发，
     * 于是 totalSales=0、totalOrders=0 与"这个周期确实没卖"在响应里完全同形。
     * 看板拿假零做经营决策比直接报错更糟。
     */
    private void markDegraded(Set<String> degraded, String source, Map<String, Object> resp, String outcome) {
        degraded.add(source);
        if (resp == null || resp.isEmpty()) {
            log.warn("{} 服务无响应体（fallback 兜底），{}：不是业务事实", source, outcome);
            return;
        }
        Object code = resp.get("code");
        if (code instanceof Number && ((Number) code).intValue() != 200) {
            log.warn("{} 服务返回非 200 业务码 code={} message={}，{}", source, code, resp.get("message"), outcome);
            return;
        }
        log.warn("{} 服务响应不合契约 keys={}，{}", source, resp.keySet(), outcome);
    }

    /** 取响应信封的 code；不是数字或没有则返回 null（裸 Map 直出同样视为不合契约）。 */
    private static Integer codeOf(Map<String, Object> resp) {
        Object code = resp == null ? null : resp.get("code");
        return code instanceof Number ? ((Number) code).intValue() : null;
    }

    /**
     * 响应是否属于"没取到数"而不是"取到了空值"。
     * <p>
     * {@code code=200} 且 data 为空是<b>合法业务空值</b>（该周期确实没有记录），不计降级；
     * 空信封（fallback 的 {@code emptyMap()}）、非 200、或压根没有 Result 信封才算降级。
     */
    private static boolean degradedEnvelope(Map<String, Object> resp) {
        if (resp == null || resp.isEmpty()) {
            return true;
        }
        Integer code = codeOf(resp);
        return code == null || code != 200;
    }

    /**
     * 通过 Feign 调用 finance 服务获取销售额（CNY）。
     * 服务不可用时降级为 0。
     */
    private BigDecimal fetchTotalSales(Long shopId, int days, Set<String> degraded) {
        try {
            LocalDate end = LocalDate.now();
            LocalDate start = end.minusDays(days);
            Map<String, Object> resp = financeFeignClient.calculateProfit(shopId, start.format(FMT), end.format(FMT));
            if (degradedEnvelope(resp)) {
                // 空信封 / 非 200 / 无 Result 信封：0 是兜底值，不是"这个周期没有销售"
                markDegraded(degraded, SOURCE_FINANCE, resp, "销售额按 0 处理");
                return BigDecimal.ZERO;
            }
            return toBigDecimal(extractData(resp));
        } catch (Exception e) {
            degraded.add(SOURCE_FINANCE);
            log.warn("调用 finance 服务获取销售额失败，降级为 0：shopId={}", shopId, e);
            return BigDecimal.ZERO;
        }
    }

    /**
     * 通过 Feign 调用 order 服务获取订单数。
     * <p>
     * 取 {@code /order/list} 的 {@code data.total}——它是同一时间窗内订单表的分页总数，
     * 与 {@code fetchTotalSales} 的窗口口径一致。此前用的是
     * {@code /order/profit/summary/{shopId}} 的**返回行数**，而该接口按 (sku, 月份) 分组：
     * 行数不是订单数，也不随 dateRange 变化，导致 totalOrders 与
     * avgOrderValue = totalSales / totalOrders 同时失真。
     * 服务不可用时降级为 0。
     */
    private Integer fetchTotalOrders(Long shopId, int days, Set<String> degraded) {
        try {
            Map<String, Object> resp = orderFeignClient.listOrders(shopId, days);
            if (degradedEnvelope(resp)) {
                markDegraded(degraded, SOURCE_ORDER, resp, "订单数按 0 处理：shopId=" + shopId + " days=" + days);
                return 0;
            }
            Object data = extractData(resp);
            if (data instanceof Map) {
                Object total = ((Map<?, ?>) data).get("total");
                if (total instanceof Number) {
                    return ((Number) total).intValue();
                }
                degraded.add(SOURCE_ORDER);
                log.warn("order 服务的 data 里没有可用的 total 字段，订单数按 0 处理：shopId={} data.keys={}",
                        shopId, ((Map<?, ?>) data).keySet());
                return 0;
            }
            if (data != null) {
                degraded.add(SOURCE_ORDER);
                log.warn("order 服务的 data 不是对象，订单数按 0 处理：shopId={} data={}", shopId, data);
            }
            return 0;
        } catch (Exception e) {
            degraded.add(SOURCE_ORDER);
            log.warn("调用 order 服务获取订单数失败，降级为 0：shopId={}", shopId, e);
            return 0;
        }
    }

    /**
     * 通过 Feign 调用 ad 服务获取广告汇总。
     * 服务不可用时返回 null。
     */
    private Map<String, Object> fetchAdSummary(Long shopId, Set<String> degraded) {
        try {
            Map<String, Object> resp = adFeignClient.getShopSummary(shopId);
            if (degradedEnvelope(resp)) {
                markDegraded(degraded, SOURCE_AD, resp, "广告指标留空");
                return null;
            }
            return resp;
        } catch (Exception e) {
            degraded.add(SOURCE_AD);
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

    /**
     * 金额一律经 {@code new BigDecimal(String)} 解析，不得走 double 中转
     * （0.1 会变成 0.1000000000000000055511...）；精度口径与缺失值语义由 MapArgUtils 统一，
     * 见 MapArgUtilsBigDecimalTest。
     */
    private BigDecimal toBigDecimal(Object value) {
        return MapArgUtils.toBigDecimal(value, BigDecimal.ZERO);
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
