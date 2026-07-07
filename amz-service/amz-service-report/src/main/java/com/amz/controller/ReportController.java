package com.amz.controller;

import com.amz.dto.DashboardReport;
import com.amz.result.Result;
import com.amz.service.ReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

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
    @GetMapping("/dashboard/{shopId}")
    public Result<DashboardReport> getDashboard(
            @PathVariable Long shopId,
            @RequestParam(defaultValue = "7d") String dateRange) {
        return Result.success(reportService.getDashboard(shopId, dateRange));
    }
}
