package com.amz.service.impl;

import com.amz.dto.DashboardReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据报表服务（模拟实现）单元测试（纯逻辑，无外部依赖）。
 * <p>
 * 验证仪表盘数据结构完整性、趋势数据天数解析、店铺分布生成等核心逻辑。
 * 因数据基于 ThreadLocalRandom 生成，断言聚焦结构与范围而非具体值。
 */
@DisplayName("数据报表服务（模拟）单元测试")
class MockReportServiceImplTest {

    private final MockReportServiceImpl reportService = new MockReportServiceImpl();

    @Test
    @DisplayName("仪表盘 - 默认 7 天 → 核心指标 + 趋势 + Top10 完整")
    void testGetDashboardDefault7Days() {
        DashboardReport report = reportService.getDashboard(1L, null);

        assertNotNull(report);
        assertNotNull(report.getTotalSales(), "总销售额不应为 null");
        assertTrue(report.getTotalSales().compareTo(BigDecimal.ZERO) > 0, "总销售额应 > 0");
        assertTrue(report.getTotalOrders() >= 100 && report.getTotalOrders() < 1000,
                "订单数应在 100~1000 范围");
        assertNotNull(report.getAvgOrderValue(), "客单价不应为 null");
        assertEquals(7, report.getSalesTrend().size(), "默认 7 天趋势");
        assertEquals(7, report.getReturnRateTrend().size());
        assertEquals(7, report.getConversionTrend().size());
        assertEquals(4, report.getTrafficSource().size(), "流量来源应 4 个");
        assertEquals(4, report.getCategorySales().size(), "品类销售应 4 个");
        assertEquals(10, report.getTopProducts().size(), "Top 商品应 10 个");
    }

    @Test
    @DisplayName("仪表盘 - 30d → 趋势数据 30 天")
    void testGetDashboard30Days() {
        DashboardReport report = reportService.getDashboard(1L, "30d");

        assertEquals(30, report.getSalesTrend().size(), "30d 应有 30 天趋势");
        assertEquals(30, report.getConversionTrend().size());
    }

    @Test
    @DisplayName("仪表盘 - 90d → 趋势数据 90 天")
    void testGetDashboard90Days() {
        DashboardReport report = reportService.getDashboard(1L, "90d");

        assertEquals(90, report.getSalesTrend().size(), "90d 应有 90 天趋势");
    }

    @Test
    @DisplayName("仪表盘 - Top 商品结构完整")
    void testTopProductsStructure() {
        DashboardReport report = reportService.getDashboard(1L, "7d");

        for (DashboardReport.TopProduct p : report.getTopProducts()) {
            assertNotNull(p.getAsin(), "ASIN 不应为 null");
            assertNotNull(p.getName(), "商品名不应为 null");
            assertTrue(p.getSalesCount() >= 50 && p.getSalesCount() < 500,
                    "销量应在 50~500 范围");
            assertNotNull(p.getSalesAmount(), "销售额不应为 null");
        }
    }

    @Test
    @DisplayName("仪表盘 - 流量来源占比合计 100%")
    void testTrafficSourceSumsTo100() {
        DashboardReport report = reportService.getDashboard(1L, null);

        double total = report.getTrafficSource().values().stream()
                .mapToDouble(BigDecimal::doubleValue).sum();
        assertEquals(100.0, total, 0.01, "流量来源占比应合计 100%");
    }

    @Test
    @DisplayName("销售趋势 - days=7 → 返回 7 条日数据")
    void testGetSalesTrend7Days() {
        List<Map<String, Object>> trend = reportService.getSalesTrend(1L, 7);

        assertEquals(7, trend.size());
        for (Map<String, Object> item : trend) {
            assertNotNull(item.get("day"), "day 字段不应为 null");
            assertNotNull(item.get("value"), "value 字段不应为 null");
        }
    }

    @Test
    @DisplayName("销售趋势 - days=null → 默认 7 天")
    void testGetSalesTrendNullDays() {
        List<Map<String, Object>> trend = reportService.getSalesTrend(1L, null);

        assertEquals(7, trend.size(), "null 应默认 7 天");
    }

    @Test
    @DisplayName("销售趋势 - days=0 → 默认 7 天")
    void testGetSalesTrendZeroDays() {
        List<Map<String, Object>> trend = reportService.getSalesTrend(1L, 0);

        assertEquals(7, trend.size(), "0 应默认 7 天");
    }

    @Test
    @DisplayName("店铺分布 → 返回 4 个店铺且结构完整")
    void testGetShopDistribution() {
        List<Map<String, Object>> dist = reportService.getShopDistribution(1L);

        assertEquals(4, dist.size(), "应返回 4 个店铺");
        for (Map<String, Object> item : dist) {
            assertNotNull(item.get("name"), "name 不应为 null");
            assertNotNull(item.get("shopId"), "shopId 不应为 null");
            assertNotNull(item.get("sales"), "sales 不应为 null");
            assertNotNull(item.get("percent"), "percent 不应为 null");
            assertNotNull(item.get("color"), "color 不应为 null");
        }
    }

    @Test
    @DisplayName("店铺分布 - 百分比合计 100%")
    void testShopDistributionPercentSumsTo100() {
        List<Map<String, Object>> dist = reportService.getShopDistribution(1L);

        double total = dist.stream()
                .mapToDouble(m -> ((BigDecimal) m.get("percent")).doubleValue())
                .sum();
        assertEquals(100.0, total, 0.01, "店铺分布百分比应合计 100%");
    }
}
