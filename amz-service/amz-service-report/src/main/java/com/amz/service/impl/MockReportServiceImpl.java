package com.amz.service.impl;

import com.amz.dto.DashboardReport;
import com.amz.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 数据报表服务模拟实现。
 * <p>
 * 生成符合可视化要求的结构化数据（基于 ThreadLocalRandom）。
 * 仅在 {@code spring.profiles.active=mock} 时生效。
 * <p>
 * 生产环境应使用 {@link RealReportServiceImpl} 通过 Feign 聚合各微服务真实数据。
 */
@Slf4j
@Service
@Profile("mock")
public class MockReportServiceImpl implements ReportService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 店铺分布饼图默认调色板（与 RealReportServiceImpl 对齐）。 */
    private static final String[] SHOP_DIST_COLORS = {
            "#5470C6", "#91CC75", "#FAC858", "#EE6666", "#73C0DE",
            "#3BA272", "#FC8452", "#9A60B4", "#EA7CCC", "#FFB000"
    };

    @Override
    public DashboardReport getDashboard(Long shopId, String dateRange) {
        int days = parseDays(dateRange);
        log.info("生成仪表盘报表（模拟）：shopId={} days={}", shopId, days);

        DashboardReport report = new DashboardReport();

        // 核心指标
        report.setTotalSales(BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(5000, 50000))
                .setScale(2, RoundingMode.HALF_UP));
        report.setTotalOrders(ThreadLocalRandom.current().nextInt(100, 1000));
        report.setConversionRate(BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(5, 20))
                .setScale(2, RoundingMode.HALF_UP));
        report.setReturnRate(BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(1, 8))
                .setScale(2, RoundingMode.HALF_UP));
        report.setAvgOrderValue(report.getTotalSales()
                .divide(BigDecimal.valueOf(report.getTotalOrders()), 2, RoundingMode.HALF_UP));

        // 趋势数据（按天）
        report.setSalesTrend(genDailyTrend(days, 100, 2000));
        report.setReturnRateTrend(genDailyTrend(days, 1, 10));
        report.setConversionTrend(genDailyTrend(days, 5, 25));

        // 流量来源占比
        Map<String, BigDecimal> traffic = new LinkedHashMap<>();
        traffic.put("自然搜索", BigDecimal.valueOf(45.0));
        traffic.put("PPC广告", BigDecimal.valueOf(30.0));
        traffic.put("站外引流", BigDecimal.valueOf(15.0));
        traffic.put("直接访问", BigDecimal.valueOf(10.0));
        report.setTrafficSource(traffic);

        // 品类销售
        Map<String, BigDecimal> category = new LinkedHashMap<>();
        category.put("电子产品", BigDecimal.valueOf(15000));
        category.put("家居用品", BigDecimal.valueOf(8500));
        category.put("运动户外", BigDecimal.valueOf(6200));
        category.put("美妆个护", BigDecimal.valueOf(4300));
        report.setCategorySales(category);

        // Top 10 畅销商品
        ArrayList<DashboardReport.TopProduct> top = new ArrayList<>();
        String[] names = {"无线蓝牙耳机", "瑜伽垫", "保温杯", "手机支架", "LED 台灯",
                "便携充电宝", "厨房刀具套装", "行李箱", "太阳镜", "背包"};
        for (int i = 0; i < 10; i++) {
            DashboardReport.TopProduct p = new DashboardReport.TopProduct();
            p.setAsin("B0" + (1000000 + i));
            p.setName(names[i]);
            p.setSalesCount(ThreadLocalRandom.current().nextInt(50, 500));
            p.setSalesAmount(BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(500, 5000))
                    .setScale(2, RoundingMode.HALF_UP));
            top.add(p);
        }
        report.setTopProducts(top);

        return report;
    }

    @Override
    public List<Map<String, Object>> getSalesTrend(Long shopId, Integer days) {
        int n = (days == null || days <= 0) ? 7 : days;
        Map<String, BigDecimal> trend = genDailyTrend(n, 100, 2000);
        List<Map<String, Object>> result = new ArrayList<>(n);
        for (Map.Entry<String, BigDecimal> e : trend.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("day", e.getKey());
            item.put("value", e.getValue());
            result.add(item);
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> getShopDistribution(Long shopId) {
        // 模拟 4 个店铺分布
        double[] percents = {42.5, 28.3, 18.6, 10.6};
        List<Map<String, Object>> result = new ArrayList<>(percents.length);
        for (int i = 0; i < percents.length; i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", "店铺 #" + (i + 1));
            item.put("shopId", (long) (i + 1));
            item.put("sales", BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(2000, 15000))
                    .setScale(2, RoundingMode.HALF_UP));
            item.put("percent", BigDecimal.valueOf(percents[i]).setScale(2, RoundingMode.HALF_UP));
            item.put("color", SHOP_DIST_COLORS[i % SHOP_DIST_COLORS.length]);
            result.add(item);
        }
        return result;
    }

    /**
     * 生成按天的趋势数据。
     */
    private Map<String, BigDecimal> genDailyTrend(int days, double min, double max) {
        Map<String, BigDecimal> trend = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();
        for (int i = days - 1; i >= 0; i--) {
            String date = today.minusDays(i).format(FMT);
            BigDecimal value = BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(min, max))
                    .setScale(2, RoundingMode.HALF_UP);
            trend.put(date, value);
        }
        return trend;
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
