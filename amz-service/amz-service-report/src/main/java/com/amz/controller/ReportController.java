package com.amz.controller;
import com.amz.annotation.ShopScoped;

import com.amz.dto.DashboardReport;
import com.amz.result.Result;
import com.amz.service.ReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据报表 REST 端点。
 */
@RestController
@RequestMapping("/report")
public class ReportController {

    @Autowired
    private ReportService reportService;

    /**
     * 获取店铺仪表盘综合报表（多维度可视化）。
     * GET /report/dashboard/{shopId}?dateRange=7d
     * <p>
     * 返回数据包含：
     * - 核心指标（销售额/订单/转化率/退货率）
     * - 销售额趋势、转化率趋势、退货率趋势（折线图）
     * - 流量来源占比（饼图）
     * - 品类销售（柱状图）
     * - Top 10 畅销商品
     */
    @ShopScoped
    @GetMapping("/dashboard/{shopId}")
    public Result<DashboardReport> getDashboard(
            @PathVariable Long shopId,
            @RequestParam(defaultValue = "7d") String dateRange) {
        return Result.success(reportService.getDashboard(shopId, dateRange));
    }

    /**
     * 销售额趋势（按日聚合，供前端折线图）。
     * GET /report/dashboard/sales-trend?shopId=&days=7
     * <p>
     * 响应元素结构：{@code { "day": "yyyy-MM-dd", "value": 1234.56 }}
     */
    @ShopScoped
    @GetMapping("/dashboard/sales-trend")
    public Result<List<Map<String, Object>>> getSalesTrend(
            @RequestParam(required = false) Long shopId,
            @RequestParam(defaultValue = "7") Integer days) {
        return Result.success(reportService.getSalesTrend(shopId, days));
    }

    /**
     * 店铺销售额分布（按销售额占比，供前端饼图）。
     * GET /report/dashboard/shop-distribution?shopId=
     * <p>
     * 响应元素结构：{@code { "name": "店铺 #1", "percent": 45.6, "color": "#5470C6" }}
     */
    @ShopScoped
    @GetMapping("/dashboard/shop-distribution")
    public Result<List<Map<String, Object>>> getShopDistribution(
            @RequestParam(required = false) Long shopId) {
        return Result.success(reportService.getShopDistribution(shopId));
    }

    /**
     * 获取店铺核心 KPI 指标（销售额/订单数/转化率/退货率/客单价）。
     * GET /report/dashboard/kpi?shopId=&dateRange=7d
     * <p>
     * 从 getDashboard 综合报表中提取 KPI 卡片部分单独返回。
     */
    @ShopScoped
    @GetMapping("/dashboard/kpi")
    public Result<Map<String, Object>> getKpi(
            @RequestParam Long shopId,
            @RequestParam(defaultValue = "7d") String dateRange) {
        if (shopId == null) {
            return Result.failure("shopId must not be null");
        }
        DashboardReport report = reportService.getDashboard(shopId, dateRange);
        Map<String, Object> kpi = new HashMap<>();
        kpi.put("shopId", shopId);
        kpi.put("dateRange", dateRange);
        kpi.put("totalSales", report.getTotalSales());
        kpi.put("totalOrders", report.getTotalOrders());
        kpi.put("conversionRate", report.getConversionRate());
        kpi.put("returnRate", report.getReturnRate());
        kpi.put("avgOrderValue", report.getAvgOrderValue());
        return Result.success(kpi);
    }
}
