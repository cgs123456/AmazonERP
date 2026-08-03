package com.amz.controller;

import com.amz.annotation.ShopScoped;
import com.amz.model.*;
import com.amz.result.Result;
import com.amz.service.ReportUpgradeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 报表升级 REST 端点。
 * <p>
 * 覆盖：利润核算/库存周转/销售趋势/经营概览
 */
@RestController
@RequestMapping("/report/v2")
public class ReportUpgradeController {

    @Autowired
    private ReportUpgradeService reportUpgradeService;

    // ==================== 利润核算 ====================

    /** 保存利润明细 */
    @ShopScoped
    @PostMapping("/profit")
    public Result<ProfitDetail> saveProfit(@RequestBody ProfitDetail detail) {
        return Result.success(reportUpgradeService.saveProfitDetail(detail));
    }

    /** 查询利润明细列表 */
    @ShopScoped
    @GetMapping("/profit/list/{shopId}")
    public Result<List<ProfitDetail>> listProfit(@PathVariable Long shopId,
                                                   @RequestParam(required = false) String asin,
                                                   @RequestParam(required = false) String startDate,
                                                   @RequestParam(required = false) String endDate) {
        return Result.success(reportUpgradeService.listProfitDetails(shopId, asin, startDate, endDate));
    }

    /** 利润汇总（按 ASIN 维度） */
    @ShopScoped
    @GetMapping("/profit/summary/{shopId}")
    public Result<Map<String, Object>> profitSummary(@PathVariable Long shopId,
                                                      @RequestParam(required = false) String startDate,
                                                      @RequestParam(required = false) String endDate) {
        return Result.success(reportUpgradeService.profitSummaryByAsin(shopId, startDate, endDate));
    }

    // ==================== 库存周转 ====================

    /** 保存库存周转数据 */
    @ShopScoped
    @PostMapping("/inventory-turnover")
    public Result<InventoryTurnover> saveTurnover(@RequestBody InventoryTurnover turnover) {
        return Result.success(reportUpgradeService.saveInventoryTurnover(turnover));
    }

    /** 查询库存周转列表 */
    @ShopScoped
    @GetMapping("/inventory-turnover/list/{shopId}")
    public Result<List<InventoryTurnover>> listTurnover(@PathVariable Long shopId,
                                                         @RequestParam(required = false) String asin) {
        return Result.success(reportUpgradeService.listInventoryTurnover(shopId, asin));
    }

    /** 呆滞库存分析 */
    @ShopScoped
    @GetMapping("/inventory-turnover/dead-stock/{shopId}")
    public Result<Map<String, Object>> deadStock(@PathVariable Long shopId) {
        return Result.success(reportUpgradeService.deadStockAnalysis(shopId));
    }

    // ==================== 销售趋势 ====================

    /** 保存销售日报 */
    @ShopScoped
    @PostMapping("/sales-daily")
    public Result<SalesDaily> saveSalesDaily(@RequestBody SalesDaily salesDaily) {
        return Result.success(reportUpgradeService.saveSalesDaily(salesDaily));
    }

    /** 查询销售趋势 */
    @ShopScoped
    @GetMapping("/sales-daily/list/{shopId}")
    public Result<List<SalesDaily>> listSalesDaily(@PathVariable Long shopId,
                                                    @RequestParam(required = false) String asin,
                                                    @RequestParam(required = false) String startDate,
                                                    @RequestParam(required = false) String endDate) {
        return Result.success(reportUpgradeService.listSalesDaily(shopId, asin, startDate, endDate));
    }

    /** 销售环比/同比 */
    @ShopScoped
    @GetMapping("/sales-daily/comparison/{shopId}")
    public Result<Map<String, Object>> salesComparison(@PathVariable Long shopId,
                                                        @RequestParam(required = false) String asin,
                                                        @RequestParam(required = false) String currentDate,
                                                        @RequestParam(defaultValue = "30") Integer compareDays) {
        return Result.success(reportUpgradeService.salesComparison(shopId, asin, currentDate, compareDays));
    }

    // ==================== 经营概览 ====================

    /** 保存经营概览 */
    @ShopScoped
    @PostMapping("/business-overview")
    public Result<BusinessOverview> saveOverview(@RequestBody BusinessOverview overview) {
        return Result.success(reportUpgradeService.saveBusinessOverview(overview));
    }

    /** 查询经营概览 */
    @ShopScoped
    @GetMapping("/business-overview/list/{shopId}")
    public Result<List<BusinessOverview>> listOverview(@PathVariable Long shopId,
                                                        @RequestParam(required = false) String startDate,
                                                        @RequestParam(required = false) String endDate) {
        return Result.success(reportUpgradeService.listBusinessOverview(shopId, startDate, endDate));
    }

    /** 店铺综合看板 */
    @ShopScoped
    @GetMapping("/dashboard/{shopId}")
    public Result<Map<String, Object>> dashboard(@PathVariable Long shopId) {
        return Result.success(reportUpgradeService.shopDashboard(shopId));
    }
}
